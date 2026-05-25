package com.nexus.fraud.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexus.fraud.lambda.classification.AlertClassifier;
import com.nexus.fraud.lambda.metrics.FraudMetricsEmitter;
import com.nexus.fraud.lambda.model.*;
import com.nexus.fraud.lambda.notification.FraudAlertNotifier;
import com.nexus.fraud.lambda.sar.SarEvaluator;
import com.nexus.fraud.lambda.storage.FraudAlertRepository;
import org.crac.Core;
import org.crac.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sns.SnsClient;

import java.util.*;

/**
 * Fraud Alert Handler — SQS batch trigger entry point.
 *
 * Processing pipeline per message:
 * 1. Parse FraudAlertEvent from SQS body
 * 2. Idempotency check (DynamoDB existence check on alertId)
 * 3. Classify: severity tier + alert category
 * 4. Store initial record (DynamoDB, conditionExpression=no-overwrite)
 * 5. Evaluate SAR criteria (CNBV 15-day deadline if triggered)
 * 6. Create SAR consideration record if required
 * 7. Emit CloudWatch metrics (BEFORE notifications — never blocked)
 * 8. Notify compliance team (SNS)
 * 9. Notify security ops (SNS)
 * 10. Update DynamoDB with notification confirmation IDs
 *
 * Error handling:
 * - Parse errors → BatchItemFailure → DLQ after 2 attempts
 * - DynamoDB errors → re-throw → SQS retry → DLQ after 2 attempts
 * - SNS errors → logged, processing continues (metrics already emitted)
 * - Both SNS failures → DLQ after 2 attempts on next invocation
 *
 * SnapStart: all clients initialized in static block → snapshot.
 * Fraud attacks are bursty; cold start of 5-15s is unacceptable.
 */
public class FraudAlertHandler
        implements RequestHandler<SQSEvent, SQSBatchResponse>,
        Resource {

    private static final Logger log =
            LoggerFactory.getLogger(FraudAlertHandler.class);

    // ── Static init — SnapStart snapshot ──────────────────
    private static final ObjectMapper MAPPER;
    private static final AlertClassifier CLASSIFIER;
    private static final FraudAlertRepository REPOSITORY;
    private static final SarEvaluator SAR_EVALUATOR;
    private static final FraudAlertNotifier NOTIFIER;
    private static final FraudMetricsEmitter METRICS;

    static {
        String region = System.getenv("AWS_REGION_NAME");

        MAPPER = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature
                        .WRITE_DATES_AS_TIMESTAMPS);

        DynamoDbClient dynamo = DynamoDbClient.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();

        SnsClient sns = SnsClient.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();

        CloudWatchClient cw = CloudWatchClient.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();

        String alertsTable = System.getenv("FRAUD_ALERTS_TABLE");
        String sarTable = System.getenv("SAR_CONSIDERATIONS_TABLE");
        String complianceTopic = System.getenv(
                "COMPLIANCE_SNS_TOPIC_ARN");
        String opsTopic = System.getenv("OPERATIONS_SNS_TOPIC_ARN");
        String cwNamespace = System.getenv("CLOUDWATCH_NAMESPACE");
        String env = System.getenv().getOrDefault("ENVIRONMENT", "dev");

        int sarThreshold = Integer.parseInt(
                System.getenv().getOrDefault(
                        "SAR_RISK_SCORE_THRESHOLD", "90"));
        Set<String> sarPatterns = new HashSet<>(Arrays.asList(
                System.getenv().getOrDefault(
                                "SAR_PATTERNS", "STRUCTURING,LAYERING")
                        .split(",")));

        CLASSIFIER = new AlertClassifier();
        REPOSITORY = new FraudAlertRepository(
                dynamo, alertsTable, sarTable, MAPPER);
        SAR_EVALUATOR = new SarEvaluator(sarThreshold, sarPatterns);
        NOTIFIER = new FraudAlertNotifier(
                sns, complianceTopic, opsTopic, MAPPER, env);
        METRICS = new FraudMetricsEmitter(cw, cwNamespace, env);

        log.info("FraudAlertHandler initialized: sarThreshold={} " +
                "env={}", sarThreshold, env);
    }

    public FraudAlertHandler() {
        Core.getGlobalContext().register(this);
    }

    @Override
    public void beforeCheckpoint(
            org.crac.Context<? extends Resource> ctx) {
        log.info("CRaC beforeCheckpoint: Fraud Alert Lambda");
    }

    @Override
    public void afterRestore(
            org.crac.Context<? extends Resource> ctx) {
        log.info("CRaC afterRestore: Fraud Alert Lambda ready");
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event,
                                          Context context) {

        List<SQSBatchResponse.BatchItemFailure> failures =
                new ArrayList<>();

        int total = event.getRecords().size();
        log.info("Fraud alert batch: size={} requestId={}",
                total, context.getAwsRequestId());

        for (SQSEvent.SQSMessage message : event.getRecords()) {
            String msgId = message.getMessageId();

            try {
                FraudAlertEvent alert = MAPPER.readValue(
                        message.getBody(), FraudAlertEvent.class);

                log.info("Processing fraud alert: msgId={} " +
                                "alertId={} riskScore={} userId={}",
                        msgId, alert.alertId(), alert.riskScore(),
                        alert.userId());

                processAlert(alert, msgId);

            } catch (JsonProcessingException e) {
                log.error("Parse failure: msgId={} body_start={} {}",
                        msgId,
                        message.getBody().substring(
                                0, Math.min(100, message.getBody().length())),
                        e.getMessage());
                failures.add(
                        SQSBatchResponse.BatchItemFailure.builder()
                                .withItemIdentifier(msgId).build());

            } catch (Exception e) {
                log.error("Alert processing failed: msgId={} {}",
                        msgId, e.getMessage(), e);
                failures.add(
                        SQSBatchResponse.BatchItemFailure.builder()
                                .withItemIdentifier(msgId).build());
            }
        }

        log.info("Fraud alert batch complete: total={} failures={}",
                total, failures.size());

        return SQSBatchResponse.builder()
                .withBatchItemFailures(failures)
                .build();
    }

    private void processAlert(FraudAlertEvent alert,
                              String sqsMessageId) {
        long start = System.currentTimeMillis();

        // ── Step 2: Idempotency ────────────────────────────────
        if (REPOSITORY.alertExists(alert.alertId())) {
            log.info("Duplicate alert suppressed: alertId={}",
                    alert.alertId());
            return;
        }

        // ── Step 3: Classify ───────────────────────────────────
        AlertClassification classification =
                CLASSIFIER.classify(alert);

        // ── Step 4: Store initial record ───────────────────────
        String alertId = REPOSITORY.storeAlert(
                alert, classification, sqsMessageId);

        // ── Step 5-6: SAR evaluation + record creation ─────────
        SarConsiderationResult sar = SAR_EVALUATOR.evaluate(
                alert, classification);

        if (sar.required()) {
            REPOSITORY.createSarConsideration(alert, alertId, sar);
        }

        // ── Step 7: CloudWatch metrics (before SNS) ────────────
        // Metrics must succeed even if SNS fails.
        METRICS.emit(alert, classification, sar);

        // ── Step 8: Compliance team notification ───────────────
        String complianceMsgId = NOTIFIER.notifyCompliance(
                alert, classification, sar, alertId);

        // ── Step 9: Security ops notification ──────────────────
        String opsMsgId = NOTIFIER.notifySecurityOps(
                alert, classification, alertId);

        // ── Step 10: Update DynamoDB with notification IDs ─────
        REPOSITORY.updateWithNotificationStatus(
                alertId, complianceMsgId, opsMsgId,
                sar.required(), sar.sarId());

        long durationMs = System.currentTimeMillis() - start;
        log.info("Alert processing complete: alertId={} " +
                        "severity={} category={} sarRequired={} ms={}",
                alertId, classification.severity(),
                classification.alertCategory(),
                sar.required(), durationMs);
    }
}