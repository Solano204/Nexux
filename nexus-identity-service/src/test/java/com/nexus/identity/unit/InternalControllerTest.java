package com.nexus.identity.unit;

import com.nexus.identity.application.command.UnauthorizedException;
import com.nexus.identity.application.query.UserQueryService;
import com.nexus.identity.web.controller.InternalController;
import com.nexus.identity.web.dto.response.IdentitySummaryResponse;
import com.nexus.identity.web.dto.response.KycStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalControllerTest {

    @Mock private UserQueryService queryService;

    private InternalController controller;
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String SECRET = "test-bridge-secret";

    @BeforeEach
    void setUp() {
        controller = new InternalController(queryService);
        ReflectionTestUtils.setField(controller, "planeBridgeSecret", SECRET);
    }

    @Test
    void getIdentitySummaryReturnsNoPiiSummary() {
        IdentitySummaryResponse summary = new IdentitySummaryResponse(
                USER_ID.toString(), "user@example.com", "Jane Doe", "ACTIVE", true, List.of("USER"));
        when(queryService.getIdentitySummary(USER_ID)).thenReturn(summary);

        ResponseEntity<IdentitySummaryResponse> response = controller.getIdentitySummary(USER_ID, "fraud-service");

        assertThat(response.getBody().email()).isEqualTo("user@example.com");
    }

    @Test
    void getKycStatusRequiresValidBridgeSecret() {
        assertThatThrownBy(() -> controller.getKycStatus(USER_ID, "wrong-secret"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void getKycStatusRejectsMissingSecret() {
        assertThatThrownBy(() -> controller.getKycStatus(USER_ID, null))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void getKycStatusFailsClosedWhenSecretNotConfigured() {
        ReflectionTestUtils.setField(controller, "planeBridgeSecret", "");

        assertThatThrownBy(() -> controller.getKycStatus(USER_ID, "anything"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void getKycStatusReturnsCombinedStatusWithValidSecret() {
        KycStatusResponse kyc = new KycStatusResponse(UUID.randomUUID().toString(), "APPROVED", "PASSPORT", null, "now");
        IdentitySummaryResponse identity = new IdentitySummaryResponse(
                USER_ID.toString(), "user@example.com", "Jane Doe", "ACTIVE", true, List.of("USER"));
        when(queryService.getCurrentKycStatus(USER_ID)).thenReturn(kyc);
        when(queryService.getIdentitySummary(USER_ID)).thenReturn(identity);

        ResponseEntity<Map<String, Object>> response = controller.getKycStatus(USER_ID, SECRET);

        assertThat(response.getBody().get("kycDecision")).isEqualTo("APPROVED");
        assertThat(response.getBody().get("kycVerified")).isEqualTo(true);
    }

    @Test
    void detailedHealthReturnsUp() {
        ResponseEntity<Map<String, Object>> response = controller.detailedHealth();

        assertThat(response.getBody().get("status")).isEqualTo("UP");
        assertThat(response.getBody().get("service")).isEqualTo("nexus-identity-service");
    }
}
