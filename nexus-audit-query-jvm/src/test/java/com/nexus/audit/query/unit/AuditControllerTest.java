package com.nexus.audit.query.unit;

import com.nexus.audit.query.application.AuditSearchService;
import com.nexus.audit.query.domain.exception.ForbiddenException;
import com.nexus.audit.query.domain.exception.UnauthorizedException;
import com.nexus.audit.query.infrastructure.elasticsearch.AuditElasticsearchRepository;
import com.nexus.audit.query.web.controller.AuditController;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditControllerTest {

    @Mock private AuditSearchService searchService;
    @Mock private AuditElasticsearchRepository auditRepository;
    @Mock private HttpServletRequest request;

    private AuditController controller;

    @BeforeEach
    void setUp() {
        controller = new AuditController(searchService, auditRepository);
    }

    @Test
    void getUserEventsThrowsUnauthorizedWhenNoUserIdHeader() {
        when(request.getHeader("X-User-Id")).thenReturn(null);

        assertThatThrownBy(() -> controller.getUserEvents("user-1", 0, 50, null, null, null, request))
                .isInstanceOf(UnauthorizedException.class);

        verifyNoInteractions(searchService);
    }

    @Test
    void getUserEventsThrowsForbiddenWithoutComplianceRole() {
        when(request.getHeader("X-User-Id")).thenReturn("caller-1");
        when(request.getHeader("X-User-Roles")).thenReturn("USER");

        assertThatThrownBy(() -> controller.getUserEvents("user-1", 0, 50, null, null, null, request))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void getUserEventsSucceedsForComplianceOfficer() {
        when(request.getHeader("X-User-Id")).thenReturn("caller-1");
        when(request.getHeader("X-User-Roles")).thenReturn("COMPLIANCE_OFFICER");
        when(searchService.getUserTimeline("user-1", 0, 50, null, null))
                .thenReturn(Map.of("userId", "user-1", "events", java.util.List.of()));

        ResponseEntity<?> response = controller.getUserEvents("user-1", 0, 50, null, null, null, request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void getUserEventsSucceedsForAdminRole() {
        when(request.getHeader("X-User-Id")).thenReturn("caller-1");
        when(request.getHeader("X-User-Roles")).thenReturn("ADMIN,USER");
        when(searchService.getUserTimeline(anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(Map.of());

        assertThat(controller.getUserEvents("user-1", 0, 50, null, null, null, request)
                .getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void getTransactionTraceRequiresComplianceRole() {
        when(request.getHeader("X-User-Id")).thenReturn("caller-1");
        when(request.getHeader("X-User-Roles")).thenReturn("USER");

        assertThatThrownBy(() -> controller.getTransactionTrace("txn-1", request))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void getTransactionTraceDelegatesToSearchService() {
        when(request.getHeader("X-User-Id")).thenReturn("caller-1");
        when(request.getHeader("X-User-Roles")).thenReturn("COMPLIANCE_OFFICER");
        when(searchService.getTransactionTrace("txn-1")).thenReturn(Map.of("transactionId", "txn-1"));

        ResponseEntity<?> response = controller.getTransactionTrace("txn-1", request);

        assertThat(response.getBody()).isEqualTo(Map.of("transactionId", "txn-1"));
    }

    @Test
    void getPlatformStatsReturnsCountFromRepository() {
        when(request.getHeader("X-User-Id")).thenReturn("caller-1");
        when(request.getHeader("X-User-Roles")).thenReturn("ADMIN");
        when(auditRepository.count()).thenReturn(42L);

        ResponseEntity<Map<String, Object>> response = controller.getPlatformStats(request);

        assertThat(response.getBody().get("totalAuditEvents")).isEqualTo(42L);
        assertThat(response.getBody().get("status")).isEqualTo("OPERATIONAL");
    }

    @Test
    void getPlatformStatsRejectsMissingRolesHeader() {
        when(request.getHeader("X-User-Id")).thenReturn("caller-1");
        when(request.getHeader("X-User-Roles")).thenReturn(null);

        assertThatThrownBy(() -> controller.getPlatformStats(request))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(auditRepository);
    }
}
