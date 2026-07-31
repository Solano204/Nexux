package com.nexus.notification.lambda.dispatch;

import com.nexus.notification.lambda.model.*;
import com.nexus.notification.lambda.template.EmailTemplateEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;
import software.amazon.awssdk.services.sesv2.model.MessageHeader;      // ← add this

/**
 * Email Dispatcher — delivers via AWS SES v2.
 *
 * Two-path rendering:
 * A) htmlBody pre-rendered by Plane A AI → wrap in _layout
 * B) Template name + variables → full Thymeleaf render
 *
 * CAN-SPAM compliance:
 * - List-Unsubscribe header via SES email tags
 * - Plain text fallback always included
 * - From address uses verified SES domain
 *
 * Error taxonomy:
 * - MessageRejectedException → permanent (invalid address, quota)
 * - SesV2Exception 5xx → transient (retry via SNS)
 * - SesV2Exception 4xx → permanent (bad request)
 */
public class EmailDispatcher {

    private static final Logger log =
            LoggerFactory.getLogger(EmailDispatcher.class);

    private final SesV2Client ses;
    private final EmailTemplateEngine templateEngine;
    private final String fromEmailAddress;
    private final String fromEmailName;
    private final String sesConfigurationSet;
    private final String unsubscribeBaseUrl;

    public EmailDispatcher(SesV2Client ses,
                           EmailTemplateEngine templateEngine,
                           String fromEmailAddress,
                           String fromEmailName,
                           String sesConfigurationSet,
                           String unsubscribeBaseUrl) {
        this.ses = ses;
        this.templateEngine = templateEngine;
        this.fromEmailAddress = fromEmailAddress;
        this.fromEmailName = fromEmailName;
        this.sesConfigurationSet = sesConfigurationSet;
        this.unsubscribeBaseUrl = unsubscribeBaseUrl;
    }

    public DeliveryResult dispatch(DispatchRequest request) {
        long startMs = System.currentTimeMillis();

        if (request.toEmail() == null || request.toEmail().isBlank()) {
            throw new DeliveryException("EMAIL",
                    "No recipient email on request: notificationId=" +
                            request.notificationId(), false);
        }

        try {
            // ── Render HTML ────────────────────────────────────
            String renderedHtml = templateEngine.render(request);

            // ── Plain text fallback ────────────────────────────
            String plainText = buildPlainText(request);

            // ── From address ───────────────────────────────────
            String fromAddress = String.format(
                    "%s <%s>", fromEmailName, fromEmailAddress);

            // ── Unsubscribe URL for CAN-SPAM List-Unsubscribe ─
            String unsubscribeUrl = unsubscribeBaseUrl +
                    "/unsubscribe?userId=" + request.userId() +
                    "&type=" + request.eventType();

            // ── Build SES request ──────────────────────────────
            SendEmailRequest sesRequest = SendEmailRequest.builder()
                    .fromEmailAddress(fromAddress)
                    .replyToAddresses("soporte@nexusbank.com")
                    .destination(Destination.builder()
                            .toAddresses(request.toEmail())
                            .build())
                    .content(EmailContent.builder()
                            .simple(Message.builder()
                                    .subject(Content.builder()
                                            .data(request.subject())
                                            .charset("UTF-8")
                                            .build())
                                    .body(Body.builder()
                                            .html(Content.builder()
                                                    .data(renderedHtml)
                                                    .charset("UTF-8")
                                                    .build())
                                            .text(Content.builder()
                                                    .data(plainText)
                                                    .charset("UTF-8")
                                                    .build())
                                            .build())
                                    .headers(
                                            // CAN-SPAM / GDPR list-unsubscribe
                                            MessageHeader.builder()
                                                    .name("List-Unsubscribe")
                                                    .value("<" + unsubscribeUrl + ">")
                                                    .build(),
                                            MessageHeader.builder()
                                                    .name("List-Unsubscribe-Post")
                                                    .value("List-Unsubscribe=One-Click")
                                                    .build())
                                    .build())
                            .build())
                    .configurationSetName(sesConfigurationSet)
                    // Tags flow into SES engagement tracking dashboard
                    .emailTags(
                            MessageTag.builder()
                                    .name("notificationId")
                                    .value(request.notificationId())
                                    .build(),
                            MessageTag.builder()
                                    .name("eventType")
                                    .value(request.eventType())
                                    .build(),
                            MessageTag.builder()
                                    .name("userId")
                                    .value(request.userId())
                                    .build(),
                            MessageTag.builder()
                                    .name("channel")
                                    .value("EMAIL")
                                    .build())
                    .build();

            SendEmailResponse sesResponse =
                    ses.sendEmail(sesRequest);

            long durationMs = System.currentTimeMillis() - startMs;

            log.info("Email delivered: notificationId={} " +
                            "sesMessageId={} to={} durationMs={}",
                    request.notificationId(),
                    sesResponse.messageId(),
                    maskEmail(request.toEmail()),
                    durationMs);

            return DeliveryResult.success("EMAIL",
                    sesResponse.messageId(), durationMs);

        } catch (MessageRejectedException e) {
            // SES rejected: invalid address, domain reputation
            throw new DeliveryException("EMAIL",
                    "SES rejected: " + e.getMessage(), false);

        } catch (MailFromDomainNotVerifiedException e) {
            throw new DeliveryException("EMAIL",
                    "SES domain not verified: " + e.getMessage(), false);

        } catch (AccountSuspendedException e) {
            // Critical — SES account-level suspension
            log.error("SES ACCOUNT SUSPENDED — escalate immediately");
            throw new DeliveryException("EMAIL",
                    "SES account suspended", false);

        } catch (SesV2Exception e) {
            boolean transient_ = e.statusCode() >= 500;
            throw new DeliveryException("EMAIL",
                    "SES error " + e.statusCode() + ": " + e.getMessage(),
                    transient_);
        }
    }

    private String buildPlainText(DispatchRequest request) {
        // Use textBody if provided, otherwise strip from HTML
        if (request.textBody() != null &&
                !request.textBody().isBlank()) {
            return request.textBody();
        }
        StringBuilder sb = new StringBuilder();
        sb.append(request.subject()).append("\n\n");
        if (request.pushBody() != null) {
            sb.append(request.pushBody()).append("\n\n");
        }
        if (request.deepLinkUrl() != null) {
            sb.append(request.callToAction() != null
                            ? request.callToAction() : "Ver en la app")
                    .append(": ")
                    .append(request.deepLinkUrl());
        }
        sb.append("\n\nNexus Bank - Ciudad de México, México");
        return sb.toString();
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        int at = email.indexOf('@');
        return email.substring(0, Math.min(3, at)) + "***" +
                email.substring(at);
    }
}