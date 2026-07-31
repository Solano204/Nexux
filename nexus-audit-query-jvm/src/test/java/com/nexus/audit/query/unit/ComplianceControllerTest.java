package com.nexus.audit.query.unit;

import com.nexus.audit.query.application.AuditSearchService;
import com.nexus.audit.query.application.ComplianceQueryService;
import com.nexus.audit.query.application.model.ComplianceQuery;
import com.nexus.audit.query.application.model.ComplianceQueryRequest;
import com.nexus.audit.query.application.model.ComplianceQueryResult;
import com.nexus.audit.query.application.model.QueryType;
import com.nexus.audit.query.domain.exception.ForbiddenException;
import com.nexus.audit.query.domain.exception.UnauthorizedException;
import com.nexus.audit.query.infrastructure.mongodb.ComplianceReportRepository;
import com.nexus.audit.query.web.controller.ComplianceController;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ComplianceControllerTest {

    @Mock private ComplianceQueryService queryService;
    @Mock private AuditSearchService searchService;
    @Mock private ComplianceReportRepository reportRepository;
    @Mock private HttpServletRequest request;

    private ComplianceController controller;

    @BeforeEach
    void setUp() {
        controller = new ComplianceController(queryService, searchService, reportRepository);
    }

    @Test
    void queryThrowsUnauthorizedWhenNoUserId() {
        when(request.getHeader("X-User-Id")).thenReturn(null);
        var req = new ComplianceQueryRequest("show me suspicious activity", null, null, null, null);

        assertThatThrownBy(() -> controller.query(req, request))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void queryThrowsForbiddenWithoutComplianceRole() {
        when(request.getHeader("X-User-Id")).thenReturn("caller-1");
        when(request.getHeader("X-User-Roles")).thenReturn("USER");
        var req = new ComplianceQueryRequest("show me suspicious activity", null, null, null, null);

        assertThatThrownBy(() -> controller.query(req, request))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void queryDefaultsDateRangeAndQueryTypeWhenOmitted() {
        when(request.getHeader("X-User-Id")).thenReturn("auditor-1");
        when(request.getHeader("X-User-Roles")).thenReturn("COMPLIANCE_OFFICER");
        ComplianceQueryResult mockResult = ComplianceQueryResult.noResults("q1", "auditor-1");
        when(queryService.executeQuery(any(ComplianceQuery.class), eq("auditor-1"))).thenReturn(mockResult);

        var req = new ComplianceQueryRequest("suspicious wires", "target-user", null, null, null);
        controller.query(req, request);

        ArgumentCaptor<ComplianceQuery> captor = ArgumentCaptor.forClass(ComplianceQuery.class);
        verify(queryService).executeQuery(captor.capture(), eq("auditor-1"));
        ComplianceQuery built = captor.getValue();
        assertThat(built.getQueryType()).isEqualTo(QueryType.SUSPICIOUS_ACTIVITY);
        assertThat(built.getStartDate()).isEqualTo(LocalDate.now().minusDays(30));
        assertThat(built.getEndDate()).isEqualTo(LocalDate.now());
        assertThat(built.getTargetUserId()).isEqualTo("target-user");
    }

    @Test
    void queryUsesAuditorIdFromHeaderNotRequestBody() {
        when(request.getHeader("X-User-Id")).thenReturn("real-auditor");
        when(request.getHeader("X-User-Roles")).thenReturn("ADMIN");
        when(queryService.executeQuery(any(), anyString()))
                .thenReturn(ComplianceQueryResult.noResults("q1", "real-auditor"));

        var req = new ComplianceQueryRequest("query", null, null, null, QueryType.TRANSACTION_TRACE);
        controller.query(req, request);

        verify(queryService).executeQuery(any(), eq("real-auditor"));
    }

    @Test
    void getUserTimelineRequiresComplianceRole() {
        when(request.getHeader("X-User-Id")).thenReturn("caller-1");
        when(request.getHeader("X-User-Roles")).thenReturn("USER");

        assertThatThrownBy(() -> controller.getUserTimeline("user-1", 0, 50, null, null, request))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void getAlertsDelegatesToSearchService() {
        when(request.getHeader("X-User-Id")).thenReturn("caller-1");
        when(request.getHeader("X-User-Roles")).thenReturn("COMPLIANCE_OFFICER");
        when(searchService.getActiveAlerts("CRITICAL", 0, 20)).thenReturn(Map.of("alerts", List.of()));

        ResponseEntity<?> response = controller.getAlerts("CRITICAL", 0, 20, request);

        assertThat(response.getBody()).isEqualTo(Map.of("alerts", List.of()));
    }

    @Test
    void getReportsDelegatesToRepositoryWithPaging() {
        when(request.getHeader("X-User-Id")).thenReturn("caller-1");
        when(request.getHeader("X-User-Roles")).thenReturn("ADMIN");
        Page<com.nexus.audit.query.application.model.ComplianceReport> page = new PageImpl<>(List.of());
        when(reportRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(page);

        ResponseEntity<?> response = controller.getReports(0, 20, request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }
}
