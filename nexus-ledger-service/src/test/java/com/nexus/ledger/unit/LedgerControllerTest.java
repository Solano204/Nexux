package com.nexus.ledger.unit;

import com.nexus.ledger.application.query.LedgerQueryService;
import com.nexus.ledger.domain.exception.AccessDeniedException;
import com.nexus.ledger.domain.exception.UnauthorizedException;
import com.nexus.ledger.infrastructure.ai.LedgerExplainerService;
import com.nexus.ledger.web.controller.LedgerController;
import com.nexus.ledger.web.dto.request.ExplainRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerControllerTest {

    @Mock private LedgerQueryService queryService;
    @Mock private LedgerExplainerService explainerService;
    @Mock private HttpServletRequest request;

    private LedgerController controller;
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new LedgerController(queryService, explainerService);
    }

    @Test
    void getBalanceReturnsBalanceAfterOwnershipCheck() {
        when(request.getHeader("X-User-Id")).thenReturn(USER_ID.toString());
        when(queryService.getCurrentBalance(ACCOUNT_ID)).thenReturn(new BigDecimal("1250.50"));

        ResponseEntity<?> response = controller.getBalance(ACCOUNT_ID, request);

        verify(queryService).verifyAccountOwnership(ACCOUNT_ID, USER_ID);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("balance")).isEqualTo(new BigDecimal("1250.50"));
    }

    @Test
    void getBalancePropagatesAccessDeniedForNonOwner() {
        when(request.getHeader("X-User-Id")).thenReturn(USER_ID.toString());
        doThrow(new AccessDeniedException("Account does not belong to requesting user"))
                .when(queryService).verifyAccountOwnership(ACCOUNT_ID, USER_ID);

        assertThatThrownBy(() -> controller.getBalance(ACCOUNT_ID, request))
                .isInstanceOf(AccessDeniedException.class);

        verify(queryService, never()).getCurrentBalance(any());
    }

    @Test
    void throwsUnauthorizedWhenUserIdHeaderMissing() {
        when(request.getHeader("X-User-Id")).thenReturn(null);

        assertThatThrownBy(() -> controller.getBalance(ACCOUNT_ID, request))
                .isInstanceOf(UnauthorizedException.class);

        verifyNoInteractions(queryService);
    }

    @Test
    void getEntriesDelegatesWithPagingParams() {
        when(request.getHeader("X-User-Id")).thenReturn(USER_ID.toString());
        var entry = new com.nexus.ledger.web.dto.response.LedgerEntryResponse(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), "DEBIT",
                new BigDecimal("10.00"), "MXN", new BigDecimal("100.00"), null, "TRANSFER", null, "now");
        when(queryService.getFullHistory(ACCOUNT_ID, 1, 10)).thenReturn(java.util.List.of(entry));

        ResponseEntity<?> response = controller.getEntries(ACCOUNT_ID, 1, 10, request);

        assertThat(response.getBody()).isEqualTo(java.util.List.of(entry));
    }

    @Test
    void getPostingReturns404WhenDetailNull() {
        when(request.getHeader("X-User-Id")).thenReturn(USER_ID.toString());
        UUID txnId = UUID.randomUUID();
        when(queryService.getPostingDetail(txnId)).thenReturn(null);

        ResponseEntity<?> response = controller.getPosting(txnId, request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void explainTransactionsVerifiesOwnershipAndStreams() {
        when(request.getHeader("X-User-Id")).thenReturn(USER_ID.toString());
        ExplainRequest explainRequest = new ExplainRequest("explain my spending", "session-1");
        when(explainerService.explainStreaming(eq(ACCOUNT_ID), eq("explain my spending"), eq("session-1")))
                .thenReturn(Flux.just("chunk1", "chunk2"));

        Flux<String> result = controller.explainTransactions(ACCOUNT_ID, explainRequest, request);

        StepVerifier.create(result).expectNext("chunk1", "chunk2").verifyComplete();
        verify(queryService).verifyAccountOwnership(ACCOUNT_ID, USER_ID);
    }

    @Test
    void explainTransactionsGeneratesSessionIdWhenMissing() {
        when(request.getHeader("X-User-Id")).thenReturn(USER_ID.toString());
        ExplainRequest explainRequest = new ExplainRequest("explain", null);
        when(explainerService.explainStreaming(any(), anyString(), anyString())).thenReturn(Flux.empty());

        controller.explainTransactions(ACCOUNT_ID, explainRequest, request);

        verify(explainerService).explainStreaming(eq(ACCOUNT_ID), eq("explain"),
                argThat(sessionId -> sessionId.startsWith("explain-" + ACCOUNT_ID)));
    }
}
