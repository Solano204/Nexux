package com.nexus.notification.lambda.dispatch;

import com.amazonaws.xray.AWSXRay;
import com.nexus.notification.lambda.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.*;

import java.util.Map;

/**
 * SMS Dispatcher — delivers via AWS SNS direct SMS.
 *
 * AWS SNS routes to the appropriate carrier based on E.164 prefix.
 * Mexico (+52): Telmex/Telcel/AT&T México carrier routes.
 *
 * MessageType:
 * - Transactional (HIGH/CRITICAL): guaranteed delivery order,
 *   highest priority, higher cost. Financial alerts MUST use this.
 * - Promotional (NORMAL): best-effort, lower cost.
 *   Never use for security-sensitive messages.
 *
 * SenderID "NEXUSBNK":
 * - Displayed as the "from" name on recipient's phone.
 * - Not all carriers support custom sender IDs.
 *   Fallback: short code or long code number.
 * - Max 11 alphanumeric characters, no spaces.
 *
 * Character limit: hard truncation at 160 chars.
 * The local Plane A notification service should pre-validate
 * SMS length, but this Lambda enforces it defensively.
 */
public class SmsDispatcher {

    private static final Logger log =
            LoggerFactory.getLogger(SmsDispatcher.class);

    private static final int DEFAULT_MAX_LENGTH = 160;

    private final SnsClient sns;
    private final String smsSenderId;
    private final int maxLength;

    public SmsDispatcher(SnsClient sns, String smsSenderId,
                         int maxLength) {
        this.sns = sns;
        this.smsSenderId = smsSenderId;
        this.maxLength = maxLength > 0 ? maxLength : DEFAULT_MAX_LENGTH;
    }

    public DeliveryResult dispatch(DispatchRequest request) {
        long startMs = System.currentTimeMillis();
        var segment = AWSXRay.beginSubsegment("SNS.SendSMS");

        try {
            // ── Validate phone number ──────────────────────────
            String phone = request.toPhoneNumber();
            if (!isValidE164(phone)) {
                throw new DeliveryException("SMS",
                        "Invalid E.164 number: " + maskPhone(phone),
                        false);
            }

            // ── Validate + truncate SMS body ───────────────────
            String body = request.smsBody();
            if (body == null || body.isBlank()) {
                throw new DeliveryException("SMS",
                        "smsBody is required", false);
            }

            if (body.length() > maxLength) {
                body = body.substring(0, maxLength - 3) + "...";
                log.warn("SMS truncated to {} chars: userId={}",
                        maxLength, request.userId());
            }

            // ── Build SNS publish request ──────────────────────
            boolean isHighPriority = "HIGH".equals(request.priority())
                    || "CRITICAL".equals(request.priority());

            PublishRequest publishRequest = PublishRequest.builder()
                    .phoneNumber(phone)
                    .message(body)
                    .messageAttributes(Map.of(
                            // Sender ID — "from" name on recipient's phone
                            "AWS.SNS.SMS.SenderID",
                            MessageAttributeValue.builder()
                                    .dataType("String")
                                    .stringValue(smsSenderId)
                                    .build(),
                            // Transactional = highest priority for financial SMS
                            "AWS.SNS.SMS.SMSType",
                            MessageAttributeValue.builder()
                                    .dataType("String")
                                    .stringValue(isHighPriority
                                            ? "Transactional" : "Promotional")
                                    .build()
                    ))
                    .build();

            PublishResponse response =
                    sns.publish(publishRequest);

            long durationMs = System.currentTimeMillis() - startMs;
            segment.putMetadata("messageId", response.messageId());

            log.info("SMS delivered: notificationId={} " +
                            "snsMessageId={} phone={} durationMs={}",
                    request.notificationId(),
                    response.messageId(),
                    maskPhone(phone),
                    durationMs);

            return DeliveryResult.success("SMS",
                    response.messageId(), durationMs);

        } catch (InvalidParameterException e) {
            // Invalid phone number or number opted out
            segment.addException(e);
            throw new DeliveryException("SMS",
                    "SNS rejected SMS: " + e.getMessage(), false);

        } catch (SnsException e) {
            segment.addException(e);
            boolean transient_ = e.statusCode() >= 500;
            throw new DeliveryException("SMS",
                    "SNS SMS error " + e.statusCode() + ": " +
                            e.getMessage(), transient_);

        } finally {
            AWSXRay.endSubsegment();
        }
    }

    private boolean isValidE164(String phone) {
        // +[1-9][6-14 digits]
        return phone != null &&
                phone.matches("\\+[1-9]\\d{6,14}");
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) return "***";
        return phone.substring(0, 4) + "****" +
                phone.substring(phone.length() - 2);
    }
}