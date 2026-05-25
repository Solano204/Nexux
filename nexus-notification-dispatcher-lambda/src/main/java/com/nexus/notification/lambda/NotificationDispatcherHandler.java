package com.nexus.notification.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexus.notification.lambda.dispatch.*;
import com.nexus.notification.lambda.model.*;
import com.nexus.notification.lambda.status.DeliveryStatusReporter;
import com.nexus.notification.lambda.template.EmailTemplateEngine;
import org.crac.Core;
import org.crac.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Notification Dispatcher Handler — SNS trigger entry point.
 *
 * SnapStart lifecycle:
 * - Static init: all AWS clients built, Thymeleaf templates
 *   parsed and cached, dispatchers initialized
 * - Snapshot taken after static init completes
 * - CRaC afterRestore(): no time-sensitive state to refresh
 *   (no JWKS, no cached secrets — all via env vars)
 * - Cold start from snapshot: ~300ms vs 5-15s without SnapStart
 *
 * SNS event handling:
 * - Each SNS record = one DispatchRequest
 * - Per-record try/catch: one bad message doesn't stop others
 * - transient failures: re-throw → SNS retries with backoff
 * - permanent failures: log + report to SQS, no retry
 */
public class NotificationDispatcherHandler
        implements RequestHandler<SNSEvent, Void>, Resource {

    private static final Logger log =
            LoggerFactory.getLogger(NotificationDispatcherHandler.class);

    // ── Static init — captured in SnapStart snapshot ───────
    private static final ObjectMapper MAPPER;
    private static final EmailDispatcher EMAIL_DISPATCHER;
    private static final SmsDispatcher SMS_DISPATCHER;
    private static final PushDispatcher PUSH_DISPATCHER;
    private static final DeliveryStatusReporter STATUS_REPORTER;

    static {
        String region = System.getenv("AWS_REGION_NAME");

        MAPPER = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature
                        .WRITE_DATES_AS_TIMESTAMPS);

        SesV2Client ses = SesV2Client.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();

        SnsClient sns = SnsClient.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();

        SqsClient sqs = SqsClient.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();

        String unsubscribeBaseUrl = System.getenv(
                "UNSUBSCRIBE_BASE_URL");
        String fromEmail = System.getenv("FROM_EMAIL_ADDRESS");
        String fromName = System.getenv("FROM_EMAIL_NAME");
        String sesConfig = System.getenv("SES_CONFIGURATION_SET");
        String statusQueueUrl = System.getenv(
                "DELIVERY_STATUS_QUEUE_URL");
        String smsSenderId = System.getenv("SMS_SENDER_ID");
        int maxSms = Integer.parseInt(
                System.getenv().getOrDefault("MAX_SMS_LENGTH", "160"));

        // Template engine: pre-parses + caches all templates
        // These are in snapshot → zero parse cost per invocation
        EmailTemplateEngine templateEngine =
                new EmailTemplateEngine(unsubscribeBaseUrl);

        EMAIL_DISPATCHER = new EmailDispatcher(
                ses, templateEngine, fromEmail, fromName,
                sesConfig, unsubscribeBaseUrl);

        SMS_DISPATCHER = new SmsDispatcher(sns, smsSenderId, maxSms);
        PUSH_DISPATCHER = new PushDispatcher(sns, MAPPER);

        STATUS_REPORTER = new DeliveryStatusReporter(
                sqs, statusQueueUrl, MAPPER);

        log.info("Notification Dispatcher initialized");
    }

    public NotificationDispatcherHandler() {
        Core.getGlobalContext().register(this);
    }

    @Override
    public void beforeCheckpoint(
            org.crac.Context<? extends Resource> ctx) {
        log.info("CRaC beforeCheckpoint: no connections to close");
    }

    @Override
    public void afterRestore(
            org.crac.Context<? extends Resource> ctx) {
        log.info("CRaC afterRestore: Notification Dispatcher ready");
    }

    @Override
    public Void handleRequest(SNSEvent snsEvent, Context context) {

        int total = snsEvent.getRecords().size();
        int delivered = 0, failed = 0;

        log.info("Notification dispatch batch: size={} requestId={}",
                total, context.getAwsRequestId());

        for (SNSEvent.SNSRecord record : snsEvent.getRecords()) {

            String snsMessageId = record.getSNS().getMessageId();
            String body = record.getSNS().getMessage();

            try {
                DispatchRequest request = MAPPER.readValue(
                        body, DispatchRequest.class);

                DeliveryResult result = dispatch(request);
                STATUS_REPORTER.reportSuccess(request, result);
                delivered++;

            } catch (com.fasterxml.jackson.core
                             .JsonProcessingException e) {
                // Bad message format — do not retry
                log.error("Parse failure: snsMessageId={} error={}",
                        snsMessageId, e.getMessage());
                STATUS_REPORTER.reportParseError(snsMessageId,
                        e.getMessage());
                failed++;

            } catch (DeliveryException e) {
                if (e.isTransient()) {
                    // Re-throw: SNS retries with exponential backoff
                    // (immediately, after 1m, after 10m, after 1h)
                    log.error("Transient failure — will retry: " +
                                    "snsMessageId={} channel={} error={}",
                            snsMessageId, e.getChannel(), e.getMessage());
                    throw new RuntimeException(
                            "Transient delivery failure", e);
                }
                // Permanent failure — report, no retry
                log.warn("Permanent failure: snsMessageId={} " +
                                "channel={} endpointInvalid={} error={}",
                        snsMessageId, e.getChannel(),
                        e.isEndpointInvalid(), e.getMessage());

                // Try to report to status queue
                // We need the DispatchRequest for context —
                // try to parse body again to get userId/notifId
                tryReportPermanentFailure(body, e);
                failed++;

            } catch (Exception e) {
                log.error("Unexpected error: snsMessageId={} {}",
                        snsMessageId, e.getMessage(), e);
                failed++;
                // Re-throw unexpected exceptions for Lambda retry
                throw new RuntimeException(
                        "Unexpected dispatch error", e);
            }
        }

        log.info("Batch complete: delivered={} failed={}",
                delivered, failed);
        return null;
    }

    private DeliveryResult dispatch(DispatchRequest request) {
        return switch (request.channel()) {
            case "EMAIL" -> EMAIL_DISPATCHER.dispatch(request);
            case "SMS"   -> SMS_DISPATCHER.dispatch(request);
            case "PUSH"  -> PUSH_DISPATCHER.dispatch(request);
            default -> throw new DeliveryException(
                    request.channel(),
                    "Unsupported channel: " + request.channel(),
                    false);
        };
    }

    private void tryReportPermanentFailure(String rawBody,
                                           DeliveryException ex) {
        try {
            DispatchRequest req = MAPPER.readValue(
                    rawBody, DispatchRequest.class);
            STATUS_REPORTER.reportFailure(req, ex, 0);
        } catch (Exception e) {
            log.warn("Could not parse request for failure report: {}",
                    e.getMessage());
        }
    }
}