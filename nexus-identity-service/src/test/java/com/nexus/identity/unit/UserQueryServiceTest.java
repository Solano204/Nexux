package com.nexus.identity.unit;

import com.nexus.identity.application.command.UserNotFoundException;
import com.nexus.identity.application.query.UserQueryService;
import com.nexus.identity.domain.model.KycVerification;
import com.nexus.identity.domain.model.Session;
import com.nexus.identity.domain.model.User;
import com.nexus.identity.domain.model.enums.KycDecision;
import com.nexus.identity.domain.model.enums.UserStatus;
import com.nexus.identity.infrastructure.persistence.KycVerificationRepository;
import com.nexus.identity.infrastructure.persistence.SessionRepository;
import com.nexus.identity.infrastructure.persistence.UserRepository;
import com.nexus.identity.infrastructure.redis.SessionCacheRepository;
import com.nexus.identity.web.dto.response.KycStatusResponse;
import com.nexus.identity.web.dto.response.UserProfileResponse;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserQueryServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private KycVerificationRepository kycRepository;
    @Mock private SessionCacheRepository sessionCacheRepository;

    private UserQueryService service;
    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new UserQueryService(userRepository, sessionRepository,
                kycRepository, sessionCacheRepository, ObservationRegistry.NOOP);
    }

    private User user() {
        return User.builder()
                .userId(USER_ID)
                .email("user@example.com")
                .phoneNumber("+5215512345678")
                .fullName("Jane Doe")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .status(UserStatus.ACTIVE)
                .roles(List.of("USER"))
                .kycVerifiedAt(Instant.now())
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void getUserProfileMapsAllFields() {
        when(userRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.of(user()));

        UserProfileResponse response = service.getUserProfile(USER_ID);

        assertThat(response.userId()).isEqualTo(USER_ID.toString());
        assertThat(response.email()).isEqualTo("user@example.com");
        assertThat(response.kycVerified()).isTrue();
    }

    @Test
    void getUserProfileThrowsWhenNotFound() {
        when(userRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getUserProfile(USER_ID))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void getActiveSessionsUsesCacheWhenPresent() {
        List<Map<String, Object>> cached = List.of(Map.of(
                "sessionId", UUID.randomUUID().toString(),
                "ipAddress", "1.2.3.4",
                "deviceFingerprint", "fp",
                "issuedAt", Instant.now().toString(),
                "lastActivityAt", Instant.now().toString()
        ));
        when(sessionCacheRepository.getCachedSessions(USER_ID)).thenReturn(cached);

        var result = service.getActiveSessions(USER_ID);

        assertThat(result).hasSize(1);
        verify(sessionRepository, never()).findActiveSessionsForUser(any());
    }

    @Test
    void getActiveSessionsFallsBackToDbOnCacheMiss() {
        when(sessionCacheRepository.getCachedSessions(USER_ID)).thenReturn(null);
        Session session = Session.builder()
                .sessionId(UUID.randomUUID()).userId(USER_ID)
                .jti(UUID.randomUUID()).ipAddress("1.2.3.4")
                .issuedAt(Instant.now()).lastActivityAt(Instant.now())
                .build();
        when(sessionRepository.findActiveSessionsForUser(USER_ID)).thenReturn(List.of(session));

        var result = service.getActiveSessions(USER_ID);

        assertThat(result).hasSize(1);
        verify(sessionCacheRepository).cacheActiveSessions(eq(USER_ID), anyList());
    }

    @Test
    void getCurrentKycStatusReturnsNotStartedWhenNoRecord() {
        when(kycRepository.findTopByUserIdOrderByAttemptNumberDesc(USER_ID)).thenReturn(Optional.empty());

        KycStatusResponse response = service.getCurrentKycStatus(USER_ID);

        assertThat(response.decision()).isEqualTo("NOT_STARTED");
        assertThat(response.verificationId()).isNull();
    }

    @Test
    void getCurrentKycStatusMapsLatestAttempt() {
        KycVerification kyc = KycVerification.builder()
                .verificationId(UUID.randomUUID())
                .userId(USER_ID)
                .documentType("PASSPORT")
                .finalDecision(KycDecision.APPROVED)
                .initiatedAt(Instant.now())
                .build();
        when(kycRepository.findTopByUserIdOrderByAttemptNumberDesc(USER_ID)).thenReturn(Optional.of(kyc));

        KycStatusResponse response = service.getCurrentKycStatus(USER_ID);

        assertThat(response.decision()).isEqualTo("APPROVED");
        assertThat(response.documentType()).isEqualTo("PASSPORT");
    }

    @Test
    void getKycHistoryMapsAllAttempts() {
        KycVerification kyc1 = KycVerification.builder().verificationId(UUID.randomUUID())
                .userId(USER_ID).initiatedAt(Instant.now()).finalDecision(KycDecision.REJECTED).build();
        KycVerification kyc2 = KycVerification.builder().verificationId(UUID.randomUUID())
                .userId(USER_ID).initiatedAt(Instant.now()).finalDecision(KycDecision.APPROVED).build();
        when(kycRepository.findByUserIdOrderByInitiatedAtDesc(USER_ID)).thenReturn(List.of(kyc2, kyc1));

        var history = service.getKycHistory(USER_ID);

        assertThat(history).hasSize(2);
    }

    @Test
    void getIdentitySummaryExcludesSensitiveFields() {
        when(userRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.of(user()));

        var summary = service.getIdentitySummary(USER_ID);

        assertThat(summary.userId()).isEqualTo(USER_ID.toString());
        assertThat(summary.status()).isEqualTo("ACTIVE");
        assertThat(summary.kycVerified()).isTrue();
    }

    @Test
    void getIdentitySummaryThrowsWhenUserMissing() {
        when(userRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getIdentitySummary(USER_ID))
                .isInstanceOf(UserNotFoundException.class);
    }
}
