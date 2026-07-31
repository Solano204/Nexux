package com.nexus.notification.unit;

import com.nexus.notification.application.NotificationProcessingService;
import com.nexus.notification.application.ai.NotificationContentGenerator;
import com.nexus.notification.application.channel.EmailNotificationChannel;
import com.nexus.notification.application.channel.InAppNotificationChannel;
import com.nexus.notification.application.channel.PushNotificationChannel;
import com.nexus.notification.application.channel.SmsNotificationChannel;
import com.nexus.notification.domain.model.NotificationContent;
import com.nexus.notification.domain.model.UserNotificationPreferences;
import com.nexus.notification.domain.model.enums.NotificationChannel;
import com.nexus.notification.domain.model.enums.NotificationEventType;
import com.nexus.notification.domain.model.enums.NotificationTone;
import com.nexus.notification.infrastructure.mongodb.NotificationRepository;
import com.nexus.notification.infrastructure.mongodb.PreferencesRepository;
import com.nexus.notification.infrastructure.redis.NotificationRedisRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests NotificationProcessingService's private channel-selection logic via
 * reflection (no Spring context, no real channel I/O — AbstractNotificationChannel.send()
 * is final and cannot be safely stubbed with the default Mockito mock maker,
 * so this deliberately never calls it), plus the pure-logic pieces
 * (QuietHours, NotificationContent) that back the same decision.
 */
@ExtendWith(MockitoExtension.class)
class ChannelSelectionTest {

    @Mock private NotificationContentGenerator contentGenerator;
    @Mock private NotificationRepository notificationRepository;
    @Mock private PreferencesRepository preferencesRepository;
    @Mock private NotificationRedisRepository redisRepository;
    @Mock private PushNotificationChannel pushChannel;
    @Mock private EmailNotificationChannel emailChannel;
    @Mock private SmsNotificationChannel smsChannel;
    @Mock private InAppNotificationChannel inAppChannel;

    private NotificationProcessingService service;

    @BeforeEach
    void setUp() {
        service = new NotificationProcessingService(contentGenerator, notificationRepository,
                preferencesRepository, redisRepository, pushChannel, emailChannel, smsChannel,
                inAppChannel, ObservationRegistry.NOOP, new SimpleMeterRegistry());
    }

    @SuppressWarnings("unchecked")
    private List<NotificationChannel> selectAdditionalChannels(NotificationEventType eventType,
                                                                 UserNotificationPreferences prefs,
                                                                 NotificationContent content) throws Exception {
        Method m = NotificationProcessingService.class.getDeclaredMethod(
                "selectAdditionalChannels", NotificationEventType.class,
                UserNotificationPreferences.class, NotificationContent.class);
        m.setAccessible(true);
        return (List<NotificationChannel>) m.invoke(service, eventType, prefs, content);
    }

    private UserNotificationPreferences.ChannelConfig enabled() {
        return new UserNotificationPreferences.ChannelConfig(true, null, null, null);
    }

    private UserNotificationPreferences.ChannelConfig disabled() {
        return new UserNotificationPreferences.ChannelConfig(false, null, null, null);
    }

    @Test
    void nonUrgentEventOnlyAddsPushWhenEnabled() throws Exception {
        UserNotificationPreferences prefs = UserNotificationPreferences.builder()
                .userId("user-1").pushConfig(enabled()).emailConfig(enabled()).smsConfig(enabled())
                .build();

        List<NotificationChannel> result = selectAdditionalChannels(
                NotificationEventType.TRANSACTION_COMPLETED, prefs, null);

        assertThat(result).containsExactly(NotificationChannel.PUSH);
    }

    @Test
    void urgentEventAddsPushEmailAndSmsWhenAllEnabled() throws Exception {
        UserNotificationPreferences prefs = UserNotificationPreferences.builder()
                .userId("user-1").pushConfig(enabled()).emailConfig(enabled()).smsConfig(enabled())
                .build();

        List<NotificationChannel> result = selectAdditionalChannels(
                NotificationEventType.FRAUD_ALERT, prefs, null);

        assertThat(result).containsExactlyInAnyOrder(
                NotificationChannel.PUSH, NotificationChannel.EMAIL, NotificationChannel.SMS);
    }

    @Test
    void securityAlertAndAccountFrozenAreAlsoTreatedAsUrgent() throws Exception {
        UserNotificationPreferences prefs = UserNotificationPreferences.builder()
                .userId("user-1").emailConfig(enabled()).smsConfig(enabled())
                .build();

        assertThat(selectAdditionalChannels(NotificationEventType.SECURITY_ALERT, prefs, null))
                .contains(NotificationChannel.EMAIL, NotificationChannel.SMS);
        assertThat(selectAdditionalChannels(NotificationEventType.ACCOUNT_FROZEN, prefs, null))
                .contains(NotificationChannel.EMAIL, NotificationChannel.SMS);
    }

    @Test
    void doesNotAddEmailOrSmsForUrgentEventWhenDisabledInPreferences() throws Exception {
        UserNotificationPreferences prefs = UserNotificationPreferences.builder()
                .userId("user-1").emailConfig(disabled()).smsConfig(disabled())
                .build();

        List<NotificationChannel> result = selectAdditionalChannels(
                NotificationEventType.FRAUD_ALERT, prefs, null);

        assertThat(result).doesNotContain(NotificationChannel.EMAIL, NotificationChannel.SMS);
    }

    @Test
    void doesNotAddPushWhenPushConfigMissing() throws Exception {
        UserNotificationPreferences prefs = UserNotificationPreferences.builder().userId("user-1").build();

        List<NotificationChannel> result = selectAdditionalChannels(
                NotificationEventType.TRANSACTION_COMPLETED, prefs, null);

        assertThat(result).isEmpty();
    }

    @Test
    void doesNotAddEmailOrSmsForNonUrgentEventRegardlessOfPreferences() throws Exception {
        UserNotificationPreferences prefs = UserNotificationPreferences.builder()
                .userId("user-1").emailConfig(enabled()).smsConfig(enabled())
                .build();

        List<NotificationChannel> result = selectAdditionalChannels(
                NotificationEventType.WELCOME, prefs, null);

        assertThat(result).doesNotContain(NotificationChannel.EMAIL, NotificationChannel.SMS);
    }

    // ── QuietHours pure logic ──────────────────────────────────

    @Test
    void quietHoursDetectsWithinSameDayWindow() {
        var quietHours = new UserNotificationPreferences.QuietHours(true, 22, 23, "America/Mexico_City", false);
        ZonedDateTime at2230 = ZonedDateTime.now(ZoneId.of("America/Mexico_City")).withHour(22).withMinute(30);

        assertThat(quietHours.isQuietNow(at2230)).isTrue();
    }

    @Test
    void quietHoursDetectsOvernightWindow() {
        var quietHours = new UserNotificationPreferences.QuietHours(true, 22, 8, "America/Mexico_City", false);
        ZonedDateTime at0300 = ZonedDateTime.now(ZoneId.of("America/Mexico_City")).withHour(3).withMinute(0);
        ZonedDateTime atNoon = ZonedDateTime.now(ZoneId.of("America/Mexico_City")).withHour(12).withMinute(0);

        assertThat(quietHours.isQuietNow(at0300)).isTrue();
        assertThat(quietHours.isQuietNow(atNoon)).isFalse();
    }

    @Test
    void quietHoursDisabledNeverBlocks() {
        var quietHours = new UserNotificationPreferences.QuietHours(false, 22, 8, "America/Mexico_City", false);
        ZonedDateTime at0300 = ZonedDateTime.now(ZoneId.of("America/Mexico_City")).withHour(3).withMinute(0);

        assertThat(quietHours.isQuietNow(at0300)).isFalse();
    }

    // ── NotificationContent pure logic ──────────────────────────

    @Test
    void isUrgentReflectsUrgentTone() {
        var urgent = new NotificationContent("t", "b", "s", "cta", "/path",
                NotificationTone.URGENT, "es", List.of(), false, null, null);
        var informational = new NotificationContent("t", "b", "s", "cta", "/path",
                NotificationTone.INFORMATIONAL, "es", List.of(), false, null, null);

        assertThat(urgent.isUrgent()).isTrue();
        assertThat(informational.isUrgent()).isFalse();
    }

    @Test
    void smsBodyTruncatesLongContentAtWordBoundary() {
        String longBody = "A".repeat(200);
        var content = new NotificationContent("t", "b", longBody, "cta", "/path",
                NotificationTone.INFORMATIONAL, "es", List.of(), false, null, null);

        String sms = content.smsBody();

        assertThat(sms.length()).isLessThanOrEqualTo(161);
        assertThat(sms).startsWith("NEXUS: ");
    }

    @Test
    void smsBodyFallsBackToTitleWhenShortBodyMissing() {
        var content = new NotificationContent("Title Only", "b", null, "cta", "/path",
                NotificationTone.INFORMATIONAL, "es", List.of(), false, null, null);

        assertThat(content.smsBody()).isEqualTo("NEXUS: Title Only");
    }
}
