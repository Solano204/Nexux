package com.nexus.transaction.unit;

import com.nexus.transaction.application.command.TransactionCommandService;
import com.nexus.transaction.application.query.TransactionQueryService;
import com.nexus.transaction.domain.exception.UnauthorizedException;
import com.nexus.transaction.domain.model.enums.TransactionType;
import com.nexus.transaction.web.controller.TransactionController;
import com.nexus.transaction.web.dto.request.InitiateTransactionRequest;
import com.nexus.transaction.web.dto.response.TransactionResponse;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionControllerTest {

    @Mock private TransactionCommandService commandService;
    @Mock private TransactionQueryService queryService;
    @Mock private Tracer tracer;
    @Mock private HttpServletRequest request;

    private TransactionController controller;
    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new TransactionController(commandService, queryService, tracer);
    }

    private InitiateTransactionRequest req() {
        return new InitiateTransactionRequest("idem-1", UUID.randomUUID(), UUID.randomUUID(), null,
                null, new BigDecimal("100.00"), "MXN", TransactionType.INTERNAL_TRANSFER, null,
                null, null, null, null);
    }

    @Test
    void initiateTransferReturns202() {
        when(request.getHeader("X-User-Id")).thenReturn(USER_ID.toString());
        TransactionResponse mockResponse = new TransactionResponse(UUID.randomUUID().toString(),
                "INITIATED", new BigDecimal("100.00"), "MXN", "INTERNAL_TRANSFER", null, "now", null, null);
        when(commandService.initiateTransaction(any(), eq(USER_ID), any(), any(), anyString()))
                .thenReturn(mockResponse);

        ResponseEntity<TransactionResponse> response = controller.initiateTransfer(req(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().status()).isEqualTo("INITIATED");
    }

    @Test
    void initiateTransferThrowsWhenUnauthenticated() {
        when(request.getHeader("X-User-Id")).thenReturn(null);

        assertThatThrownBy(() -> controller.initiateTransfer(req(), request))
                .isInstanceOf(UnauthorizedException.class);

        verifyNoInteractions(commandService);
    }

    @Test
    void initiatePaymentDelegatesToCommandService() {
        when(request.getHeader("X-User-Id")).thenReturn(USER_ID.toString());
        TransactionResponse mockResponse = new TransactionResponse(UUID.randomUUID().toString(),
                "INITIATED", new BigDecimal("50.00"), "MXN", "PAYMENT", null, "now", null, null);
        when(commandService.initiateTransaction(any(), eq(USER_ID), any(), any(), anyString()))
                .thenReturn(mockResponse);

        ResponseEntity<TransactionResponse> response = controller.initiatePayment(req(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void getHistoryScopesToRequestingUser() {
        when(request.getHeader("X-User-Id")).thenReturn(USER_ID.toString());
        Pageable pageable = Pageable.unpaged();
        Page<TransactionResponse> page = new PageImpl<>(List.of());
        when(queryService.getTransactionHistory(USER_ID, pageable)).thenReturn(page);

        ResponseEntity<Page<TransactionResponse>> response = controller.getHistory(request, pageable);

        assertThat(response.getBody()).isEmpty();
        verify(queryService).getTransactionHistory(USER_ID, pageable);
    }

    @Test
    void getTransactionDelegatesWithUserIdForOwnershipCheck() {
        when(request.getHeader("X-User-Id")).thenReturn(USER_ID.toString());
        UUID txnId = UUID.randomUUID();
        TransactionResponse mockResponse = new TransactionResponse(txnId.toString(),
                "COMPLETED", new BigDecimal("100.00"), "MXN", "INTERNAL_TRANSFER", null, "now", "now", null);
        when(queryService.getTransactionDetail(txnId, USER_ID)).thenReturn(mockResponse);

        ResponseEntity<TransactionResponse> response = controller.getTransaction(txnId, request);

        assertThat(response.getBody().transactionId()).isEqualTo(txnId.toString());
    }

    @Test
    void searchTransactionsPassesQueryThrough() {
        when(request.getHeader("X-User-Id")).thenReturn(USER_ID.toString());
        when(queryService.searchTransactions(USER_ID, "amazon")).thenReturn(List.of());

        ResponseEntity<List<TransactionResponse>> response = controller.searchTransactions("amazon", request);

        assertThat(response.getBody()).isEmpty();
        verify(queryService).searchTransactions(USER_ID, "amazon");
    }
}
