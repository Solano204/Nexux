package com.nexus.reporting;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexus.reporting.data.*;
import com.nexus.reporting.generator.*;
import com.nexus.reporting.metrics.ReportingMetricsEmitter;
import com.nexus.reporting.model.*;
import com.nexus.reporting.storage.S3ReportUploader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.*;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Daily report generation handler.
 *
 * Trigger: CloudWatch Events cron(0 12 * * ? *) = 6am Mexico City.
 * Input: {"reportDate":"YESTERDAY","reportTypes":["OPERATIONS","COMPLIANCE",...]}
 *
 * Pipeline:
 *   1. Resolve report date (YESTERDAY, TODAY, or specific YYYY-MM-DD)
 *   2. Query all DynamoDB tables once (consolidated DailyReportData)
 *   3. Generate each report type (with timeout guard)
 *   4. Emit CloudWatch completion metrics
 *   5. Publish SNS completion notification
 *
 * SnapStart: all clients initialized in static block.
 * 1024MB / 15min timeout / 2GB ephemeral storage.
 */
public class ReportingHandler
        implements RequestHandler<Map<String, Object>, ReportingResult> {

    private static final Logger log =
        LoggerFactory.getLogger(ReportingHandler.class);

    private static final ObjectMapper MAPPER;
    private static final ReportDataQueryService QUERY_SERVICE;
    private static final S3ReportUploader UPLOADER;
    private static final ReportingMetricsEmitter METRICS;
    private static final OperationsReportGenerator OPS_GEN;
    private static final ComplianceReportGenerator COMPLIANCE_GEN;
    private static final BusinessIntelligenceReportGenerator BI_GEN;
    private static final UserStatementGenerator STMT_GEN;
    private static final SnsClient SNS;
    private static final String SNS_TOPIC_ARN;
    private static final ZoneId TIMEZONE;

    static {
        String region = System.getenv("AWS_REGION") != null
            ? System.getenv("AWS_REGION") : "us-east-1";

        MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        DynamoDbClient dynamo = DynamoDbClient.builder()
            .region(Region.of(region))
            .httpClientBuilder(ApacheHttpClient.builder()
                .maxConnections(25)
                .socketTimeout(java.time.Duration.ofSeconds(30)))
            .build();

        S3Client s3 = S3Client.builder().region(Region.of(region)).build();

        SNS = SnsClient.builder().region(Region.of(region)).build();

        CloudWatchClient cw = CloudWatchClient.builder()
            .region(Region.of(region)).build();

        String bucket = env("REPORTS_BUCKET", "nexus-reports");
        String kmsKey = env("REPORTS_KMS_KEY_ARN", "");
        SNS_TOPIC_ARN = env("NOTIFICATION_SNS_TOPIC_ARN", "");
        String cwNamespace = env("CLOUDWATCH_NAMESPACE", "Nexus/Reporting");
        TIMEZONE = ZoneId.of(env("TIMEZONE", "America/Mexico_City"));

        QUERY_SERVICE = new ReportDataQueryService(dynamo);
        UPLOADER = new S3ReportUploader(s3, bucket, kmsKey);
        METRICS = new ReportingMetricsEmitter(cw, cwNamespace);
        OPS_GEN = new OperationsReportGenerator(UPLOADER, MAPPER);
        COMPLIANCE_GEN = new ComplianceReportGenerator(UPLOADER);
        BI_GEN = new BusinessIntelligenceReportGenerator(UPLOADER);
        STMT_GEN = new UserStatementGenerator(UPLOADER, MAPPER);

        log.info("ReportingHandler initialized: bucket={} tz={}", bucket, TIMEZONE);
    }

    @Override
    public ReportingResult handleRequest(Map<String, Object> event,
                                          Context context) {
        long startMs = System.currentTimeMillis();

        LocalDate reportDate = resolveDate(event);
        List<String> reportTypes = resolveTypes(event);

        log.info("Starting daily reports: date={} types={} remainingMs={}",
            reportDate, reportTypes, context.getRemainingTimeInMillis());

        // Query all data once
        DailyReportData data;
        try {
            data = QUERY_SERVICE.queryAll(reportDate);
        } catch (Exception e) {
            log.error("Data query failed — no reports can be generated: {}",
                e.getMessage(), e);
            long durationMs = System.currentTimeMillis() - startMs;
            List<String> errors = List.of("QUERY_FAILED: " + e.getMessage());
            List<ReportResult> results = reportTypes.stream()
                .map(t -> ReportResult.failed(t, "Data query failed: " + e.getMessage()))
                .toList();
            publishNotification(reportDate, results, errors, durationMs);
            return new ReportingResult(reportDate.toString(), results, errors,
                durationMs, "FAILED");
        }

        log.info("Data loaded: txns={} alerts={} users={}",
            data.transactionCount(), data.fraudAlertCount(),
            data.activeUserCount());

        List<ReportResult> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (String type : reportTypes) {
            if (context.getRemainingTimeInMillis() < 60_000) {
                log.warn("Timeout approaching, skipping: {}", type);
                errors.add("TIMEOUT_APPROACHING: " + type + " skipped");
                results.add(ReportResult.skipped(type, "Timeout approaching"));
                continue;
            }

            try {
                ReportResult result = switch (type) {
                    case "OPERATIONS" -> OPS_GEN.generate(reportDate, data);
                    case "COMPLIANCE" -> COMPLIANCE_GEN.generate(reportDate, data);
                    case "BUSINESS_INTELLIGENCE" -> BI_GEN.generate(reportDate, data);
                    case "USER_STATEMENTS" -> STMT_GEN.generate(reportDate, data);
                    default -> ReportResult.skipped(type, "Unknown type");
                };
                results.add(result);
                log.info("Report done: type={} files={} bytes={}",
                    type, result.generatedFiles().size(), result.totalBytes());
            } catch (Exception e) {
                log.error("Report failed: type={} error={}", type, e.getMessage(), e);
                errors.add(type + ": " + e.getMessage());
                results.add(ReportResult.failed(type, e.getMessage()));
            }
        }

        long durationMs = System.currentTimeMillis() - startMs;

        METRICS.emitCompletionMetrics(results, durationMs);
        publishNotification(reportDate, results, errors, durationMs);

        String status = errors.isEmpty() ? "SUCCESS" : "PARTIAL_SUCCESS";
        log.info("Reports complete: date={} status={} ms={}", reportDate, status, durationMs);

        return new ReportingResult(reportDate.toString(), results, errors, durationMs, status);
    }

    private LocalDate resolveDate(Map<String, Object> event) {
        Object v = event.get("reportDate");
        if (v == null || "YESTERDAY".equals(v.toString()))
            return LocalDate.now(TIMEZONE).minusDays(1);
        if ("TODAY".equals(v.toString()))
            return LocalDate.now(TIMEZONE);
        try { return LocalDate.parse(v.toString()); }
        catch (DateTimeParseException e) {
            log.warn("Invalid reportDate: {}, using yesterday", v);
            return LocalDate.now(TIMEZONE).minusDays(1);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> resolveTypes(Map<String, Object> event) {
        Object v = event.get("reportTypes");
        if (v instanceof List<?> list)
            return list.stream().map(Object::toString).toList();
        return List.of("OPERATIONS", "COMPLIANCE",
            "BUSINESS_INTELLIGENCE", "USER_STATEMENTS");
    }

    private void publishNotification(LocalDate date, List<ReportResult> results,
                                      List<String> errors, long durationMs) {
        boolean ok = errors.isEmpty();
        try {
            var notification = new ReportCompletionNotification(
                date.toString(), ok ? "SUCCESS" : "PARTIAL_SUCCESS",
                results.size(),
                (int) results.stream().filter(r -> "SUCCESS".equals(r.status())).count(),
                errors.size(), durationMs,
                results.stream().mapToLong(ReportResult::totalBytes).sum(),
                results.stream().flatMap(r -> r.generatedFiles().stream()).toList(),
                Instant.now().toString());

            String subject = ok
                ? String.format("Nexus Daily Reports Generated — %s", date)
                : String.format("Nexus Daily Reports Partial — %s (%d errors)", date, errors.size());

            SNS.publish(PublishRequest.builder()
                .topicArn(SNS_TOPIC_ARN)
                .subject(subject)
                .message(MAPPER.writeValueAsString(notification))
                .messageAttributes(Map.of("reportStatus",
                    MessageAttributeValue.builder()
                        .dataType("String").stringValue(notification.status()).build()))
                .build());
        } catch (Exception e) {
            log.warn("Notification publish failed: {}", e.getMessage());
        }
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v != null ? v : def;
    }
}
