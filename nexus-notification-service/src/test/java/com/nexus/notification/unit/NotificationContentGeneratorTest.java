package com.nexus.notification.unit;

import com.nexus.notification.application.ai.FallbackContentGenerator;
import com.nexus.notification.application.ai.NotificationContentGenerator;
import com.nexus.notification.application.ai.NotificationLlmGateway;
import com.nexus.notification.domain.model.NotificationContent;
import com.nexus.notification.domain.model.UserNotificationPreferences;
import com.nexus.notification.domain.model.enums.NotificationEventType;
import com.nexus.notification.domain.model.enums.NotificationTone;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationContentGeneratorTest {

    @Mock private NotificationLlmGateway llmGateway;
    @Mock private FallbackContentGenerator fallbackGenerator;

    private NotificationContentGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new NotificationContentGenerator(llmGateway, fallbackGenerator,
                ObservationRegistry.NOOP, new SimpleMeterRegistry());
    }

    private UserNotificationPreferences prefs() {
        return UserNotificationPreferences.builder()
                .userId("user-1").language("es").timezone("America/Mexico_City").build();
    }

    @Test
    void returnsAiGeneratedContentOnSuccess() {
        NotificationContent aiContent = new NotificationContent(
                "Transferencia completada", "body", "short", "cta", "/path",
                NotificationTone.POSITIVE, "es", List.of("highlight"), false, null, null);
        when(llmGateway.generate(eq(NotificationEventType.TRANSACTION_COMPLETED), anyString(), anyMap()))
                .thenReturn(aiContent);

        NotificationContent result = generator.generate(
                NotificationEventType.TRANSACTION_COMPLETED, Map.of("amount", "100.00"), prefs());

        assertThat(result).isEqualTo(aiContent);
        verifyNoInteractions(fallbackGenerator);
    }

    @Test
    void fallsBackToTemplateWhenLlmThrows() {
        when(llmGateway.generate(any(), anyString(), anyMap()))
                .thenThrow(new RuntimeException("OpenAI timeout"));
        NotificationContent fallbackContent = new NotificationContent(
                "Transferencia completada", "body", "short", "cta", "/path",
                NotificationTone.POSITIVE, "es", List.of(), false, null, Map.of());
        when(fallbackGenerator.generate(eq(NotificationEventType.TRANSACTION_COMPLETED), anyMap()))
                .thenReturn(fallbackContent);

        NotificationContent result = generator.generate(
                NotificationEventType.TRANSACTION_COMPLETED, Map.of("amount", "100.00"), prefs());

        assertThat(result).isEqualTo(fallbackContent);
    }

    @Test
    void fallbackContextIncludesLanguageFromPreferences() {
        when(llmGateway.generate(any(), anyString(), anyMap())).thenThrow(new RuntimeException("down"));
        when(fallbackGenerator.generate(any(), anyMap())).thenReturn(
                new NotificationContent("t", "b", "s", "c", "/p", NotificationTone.INFORMATIONAL,
                        "es", List.of(), false, null, Map.of()));

        generator.generate(NotificationEventType.KYC_REJECTED, Map.of(), prefs());

        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(fallbackGenerator).generate(eq(NotificationEventType.KYC_REJECTED), captor.capture());
        assertThat(captor.getValue()).containsEntry("language", "es");
    }

    @Test
    void handlesNullPreferencesGracefullyDefaultingToSpanish() {
        NotificationContent aiContent = new NotificationContent(
                "t", "b", "s", "c", "/p", NotificationTone.INFORMATIONAL,
                "es", List.of(), false, null, null);
        when(llmGateway.generate(any(), anyString(), anyMap())).thenReturn(aiContent);

        NotificationContent result = generator.generate(
                NotificationEventType.WELCOME, Map.of(), null);

        assertThat(result).isEqualTo(aiContent);
    }

    // ── FallbackContentGenerator (real instance, no mocking needed) ──────

    @Test
    void fallbackGeneratorSubstitutesTemplateVariables() {
        FallbackContentGenerator realFallback = new FallbackContentGenerator();

        NotificationContent content = realFallback.generate(
                NotificationEventType.TRANSACTION_COMPLETED,
                Map.of("currency", "MXN", "amount", "500.00", "recipient", "Juan Perez",
                        "transactionId", "txn-1"));

        assertThat(content.body()).contains("MXN 500.00").contains("Juan Perez");
        assertThat(content.deepLinkPath()).isEqualTo("/transactions/txn-1");
        assertThat(content.tone()).isEqualTo(NotificationTone.POSITIVE);
    }

    @Test
    void fallbackGeneratorUsesGenericTemplateForUnknownEventType() {
        FallbackContentGenerator realFallback = new FallbackContentGenerator();

        NotificationContent content = realFallback.generate(
                NotificationEventType.LOW_BALANCE_ALERT, Map.of());

        assertThat(content.title()).isEqualTo("Notificación de Nexus");
        assertThat(content.tone()).isEqualTo(NotificationTone.INFORMATIONAL);
    }

    @Test
    void fallbackGeneratorDefaultsLanguageToSpanishWhenMissing() {
        FallbackContentGenerator realFallback = new FallbackContentGenerator();

        NotificationContent content = realFallback.generate(
                NotificationEventType.KYC_APPROVED, Map.of());

        assertThat(content.language()).isEqualTo("es");
        assertThat(content.tone()).isEqualTo(NotificationTone.CELEBRATORY);
    }

    @Test
    void fallbackGeneratorLeavesUnfilledPlaceholdersEmptyNotLiteral() {
        FallbackContentGenerator realFallback = new FallbackContentGenerator();

        NotificationContent content = realFallback.generate(
                NotificationEventType.FRAUD_ALERT, Map.of("currency", "MXN", "amount", "1000"));

        assertThat(content.body()).doesNotContain("{currency}").doesNotContain("{amount}");
        assertThat(content.tone()).isEqualTo(NotificationTone.URGENT);
    }
}
