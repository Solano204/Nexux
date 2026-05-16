package com.nexus.notification.lambda.template;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.*;

/**
 * Email Template Engine — Thymeleaf-based HTML rendering.
 *
 * SnapStart optimization:
 * The TemplateEngine is initialized in the static block → captured in
 * snapshot. All templates are pre-parsed during warmTemplateCache()
 * which also runs before the snapshot. When a SnapStart cold start
 * occurs, templates are already in the parsed cache — invocations
 * only substitute variables, not re-parse HTML.
 *
 * Template loading: classpath:/templates/email/{name}.html
 *
 * Tone system:
 * CSS class "tone-{TONE}" on the root div controls:
 * - CTA button color (green=POSITIVE, red=URGENT, amber=WARNING, etc.)
 * - Header accent stripe
 * The AI-generated notification tone is preserved through to visual design.
 */
public class EmailTemplateEngine {

    private static final Logger log =
            LoggerFactory.getLogger(EmailTemplateEngine.class);

    private static final List<String> ALL_TEMPLATES = List.of(
            "transaction-completed", "transaction-failed",
            "fraud-alert", "account-created",
            "kyc-approved", "kyc-rejected",
            "login-new-device", "generic-notification",
            "_layout"
    );

    private static final Map<String, String> EVENT_TYPE_TO_TEMPLATE =
            Map.ofEntries(
                    Map.entry("TRANSACTION_COMPLETED", "transaction-completed"),
                    Map.entry("TRANSACTION_FAILED",    "transaction-failed"),
                    Map.entry("FRAUD_ALERT",           "fraud-alert"),
                    Map.entry("ACCOUNT_FROZEN",        "fraud-alert"),
                    Map.entry("ACCOUNT_CREATED",       "account-created"),
                    Map.entry("KYC_APPROVED",          "kyc-approved"),
                    Map.entry("KYC_REJECTED",          "kyc-rejected"),
                    Map.entry("LOGIN_NEW_DEVICE",      "login-new-device"),
                    Map.entry("PASSWORD_CHANGED",      "login-new-device")
            );

    private final TemplateEngine engine;
    private final String unsubscribeBaseUrl;

    public EmailTemplateEngine(String unsubscribeBaseUrl) {
        this.unsubscribeBaseUrl = unsubscribeBaseUrl;

        ClassLoaderTemplateResolver resolver =
                new ClassLoaderTemplateResolver();
        resolver.setPrefix("/templates/email/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);
        resolver.setCacheTTLMs(null); // never expire — bundled in JAR

        engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);

        warmTemplateCache();
    }

    /**
     * Render the notification to HTML.
     *
     * Two paths:
     * A) htmlBody is pre-rendered: wrap in _layout only.
     * B) templateName/eventType drives template selection + full render.
     */
    public String render(com.nexus.notification.lambda.model
                                 .DispatchRequest request) {

        String templateName = resolveTemplateName(request);
        Context ctx = buildContext(request);

        if (request.htmlBody() != null &&
                !request.htmlBody().isBlank()) {
            // Path A: AI-generated content from Plane A — wrap in layout
            ctx.setVariable("bodyContent", request.htmlBody());
            return engine.process("_layout", ctx);
        }

        // Path B: render full template
        return engine.process(templateName, ctx);
    }

    private String resolveTemplateName(
            com.nexus.notification.lambda.model.DispatchRequest request) {
        if (request.templateName() != null &&
                !request.templateName().isBlank()) {
            return request.templateName();
        }
        return EVENT_TYPE_TO_TEMPLATE.getOrDefault(
                request.eventType(), "generic-notification");
    }

    private Context buildContext(
            com.nexus.notification.lambda.model.DispatchRequest request) {
        Context ctx = new Context(mapLocale(request.language()));

        // Variables available in ALL templates
        ctx.setVariable("tone",
                request.tone() != null ? request.tone() : "INFORMATIONAL");
        ctx.setVariable("callToAction",
                request.callToAction() != null
                        ? request.callToAction() : "Ver en la app");
        ctx.setVariable("deepLinkUrl",
                request.deepLinkUrl() != null
                        ? request.deepLinkUrl() : "https://app.nexusbank.com");
        ctx.setVariable("unsubscribeUrl",
                unsubscribeBaseUrl +
                        "/unsubscribe?userId=" + request.userId() +
                        "&type=" + request.eventType());
        ctx.setVariable("userId", request.userId());
        ctx.setVariable("eventType", request.eventType());

        // Metadata variables (userName, amount, currency, etc.)
        if (request.metadata() != null) {
            request.metadata().forEach(ctx::setVariable);
        }

        // Template-specific overrides
        if (request.templateVariables() != null) {
            request.templateVariables().forEach(ctx::setVariable);
        }

        return ctx;
    }

    private Locale mapLocale(String language) {
        if (language == null) return new Locale("es", "MX");
        return switch (language.toLowerCase()) {
            case "en" -> Locale.ENGLISH;
            case "es" -> new Locale("es", "MX");
            default   -> new Locale("es", "MX");
        };
    }

    /**
     * Pre-render all templates with dummy data.
     * Runs during static initialization → captured in SnapStart snapshot.
     * Cost: ~100ms at init. Benefit: zero parse latency per invocation.
     */
    private void warmTemplateCache() {
        Context warmup = new Context(new Locale("es", "MX"));
        warmup.setVariable("tone", "INFORMATIONAL");
        warmup.setVariable("callToAction", "warmup");
        warmup.setVariable("deepLinkUrl", "https://warmup.test");
        warmup.setVariable("unsubscribeUrl", "https://warmup.test");
        warmup.setVariable("bodyContent", "<p>warmup</p>");
        warmup.setVariable("userId", "warmup-user");
        warmup.setVariable("eventType", "WARMUP");
        warmup.setVariable("amount", "0.00");
        warmup.setVariable("currency", "MXN");
        warmup.setVariable("userName", "warmup");

        int cached = 0;
        for (String template : ALL_TEMPLATES) {
            try {
                engine.process(template, warmup);
                cached++;
            } catch (Exception e) {
                log.warn("Template warmup skipped: {}", template);
            }
        }
        log.info("Thymeleaf template cache warmed: {}/{} templates",
                cached, ALL_TEMPLATES.size());
    }
}