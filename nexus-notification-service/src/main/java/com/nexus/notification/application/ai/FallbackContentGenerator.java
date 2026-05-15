package com.nexus.notification.application.ai;

import com.nexus.notification.domain.model.NotificationContent;
import com.nexus.notification.domain.model.enums.NotificationEventType;
import com.nexus.notification.domain.model.enums.NotificationTone;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Fallback Content Generator — Template-based notification generation.
 *
 * Used when:
 * 1. OpenAI API is unavailable after retries
 * 2. AI returns invalid JSON
 * 3. AI response times out
 *
 * Always works — sacrifices personalization for reliability.
 * Produces grammatically correct, factually accurate notifications.
 */
@Component
public class FallbackContentGenerator {

    private static final Map<NotificationEventType, FallbackTemplate>
            TEMPLATES_ES = Map.of(

            NotificationEventType.TRANSACTION_COMPLETED,
            new FallbackTemplate(
                    "Transferencia completada",
                    "Tu transferencia de {currency} {amount} a {recipient} fue procesada exitosamente.",
                    "Transferencia de {currency} {amount} a {recipient} completada.",
                    "Ver transferencia", "/transactions/{transactionId}",
                    NotificationTone.POSITIVE
            ),

            NotificationEventType.TRANSACTION_FAILED,
            new FallbackTemplate(
                    "Transferencia no procesada",
                    "No fue posible procesar tu transferencia de {currency} {amount}. No se realizó ningún cargo.",
                    "Transferencia de {currency} {amount} no procesada. Sin cargos.",
                    "Ver detalles", "/transactions/{transactionId}",
                    NotificationTone.EMPATHETIC
            ),

            NotificationEventType.FRAUD_ALERT,
            new FallbackTemplate(
                    "Alerta de seguridad",
                    "Bloqueamos una transacción sospechosa de {currency} {amount} en su cuenta. Su dinero está seguro.",
                    "Transacción sospechosa de {currency} {amount} bloqueada. Su dinero está seguro.",
                    "Revisar actividad", "/security/activity",
                    NotificationTone.URGENT
            ),

            NotificationEventType.ACCOUNT_CREATED,
            new FallbackTemplate(
                    "¡Bienvenido a Nexus!",
                    "Tu cuenta {accountType} terminada en {last4} está lista. ¡Empieza a usar Nexus hoy!",
                    "Tu cuenta Nexus terminada en {last4} ya está activa.",
                    "Ver cuenta", "/accounts/{accountId}",
                    NotificationTone.CELEBRATORY
            ),

            NotificationEventType.KYC_APPROVED,
            new FallbackTemplate(
                    "Identidad verificada",
                    "¡Tu identidad fue verificada! Tu cuenta Nexus está completamente activa.",
                    "Identidad verificada. Tu cuenta está activa.",
                    "Empezar a usar Nexus", "/home",
                    NotificationTone.CELEBRATORY
            ),

            NotificationEventType.KYC_REJECTED,
            new FallbackTemplate(
                    "Verificación pendiente",
                    "No pudimos verificar tu identidad en este momento. Por favor intenta nuevamente con un documento vigente.",
                    "Verificación de identidad pendiente. Intenta nuevamente.",
                    "Intentar de nuevo", "/kyc/retry",
                    NotificationTone.EMPATHETIC
            )
    );

    public NotificationContent generate(
            NotificationEventType eventType,
            Map<String, String> context) {

        FallbackTemplate template = TEMPLATES_ES.getOrDefault(
                eventType,
                new FallbackTemplate(
                        "Notificación de Nexus",
                        "Hay una actualización en tu cuenta Nexus.",
                        "Actualización en tu cuenta Nexus.",
                        "Ver detalles", "/home",
                        NotificationTone.INFORMATIONAL
                )
        );

        // Substitute template variables
        String body = substituteVariables(template.body(), context);
        String shortBody = substituteVariables(
                template.shortBody(), context);
        String deepLink = substituteVariables(
                template.deepLinkPath(), context);

        String lang = context.getOrDefault("language", "es");

        return new NotificationContent(
                template.title(), body, shortBody,
                template.callToAction(), deepLink,
                template.tone(), lang,
                List.of(),
                false, null,
                context
        );
    }

    private String substituteVariables(String template,
                                       Map<String, String> context) {
        String result = template;
        for (Map.Entry<String, String> entry : context.entrySet()) {
            result = result.replace(
                    "{" + entry.getKey() + "}",
                    entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }

    private record FallbackTemplate(
            String title, String body, String shortBody,
            String callToAction, String deepLinkPath,
            NotificationTone tone
    ) {}
}