package com.nexus.account.unit;

import com.nexus.account.application.query.AccountQueryService;
import com.nexus.account.domain.exception.AccessDeniedException;
import com.nexus.account.domain.exception.UnauthorizedException;
import com.nexus.account.infrastructure.redis.BalanceCacheRepository;
import com.nexus.account.web.controller.AccountController;
import com.nexus.account.web.dto.response.AccountSummaryResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

    @Mock private AccountQueryService queryService;
    @Mock private HttpServletRequest request;

    private AccountController controller;
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new AccountController(queryService);
    }

    @Test
    void getMyAccountsThrowsWhenUnauthenticated() {
        when(request.getHeader("X-User-Id")).thenReturn(null);

        assertThatThrownBy(() -> controller.getMyAccounts(request))
                .isInstanceOf(UnauthorizedException.class);

        verifyNoInteractions(queryService);
    }

    @Test
    void getMyAccountsReturnsUserAccounts() {
        when(request.getHeader("X-User-Id")).thenReturn(USER_ID.toString());
        AccountSummaryResponse summary = new AccountSummaryResponse(
                ACCOUNT_ID.toString(), "1111-2222", "CHECKING", "MXN",
                new BigDecimal("500.00"), new BigDecimal("0.00"), new BigDecimal("500.00"),
                "ACTIVE", Instant.now().toString());
        when(queryService.getUserAccounts(USER_ID)).thenReturn(List.of(summary));

        ResponseEntity<List<AccountSummaryResponse>> response = controller.getMyAccounts(request);

        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void getBalanceReturns503WhenCacheMiss() {
        when(request.getHeader("X-User-Id")).thenReturn(USER_ID.toString());
        when(queryService.getBalanceCached(ACCOUNT_ID, USER_ID)).thenReturn(null);

        ResponseEntity<?> response = controller.getBalance(ACCOUNT_ID, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }

    @Test
    void getBalanceReturnsCachedEntryOnHit() {
        when(request.getHeader("X-User-Id")).thenReturn(USER_ID.toString());
        var entry = new BalanceCacheRepository.BalanceCacheEntry(
                new BigDecimal("500.00"), new BigDecimal("50.00"), new BigDecimal("550.00"),
                "MXN", "ACTIVE", Instant.now());
        when(queryService.getBalanceCached(ACCOUNT_ID, USER_ID)).thenReturn(entry);

        ResponseEntity<?> response = controller.getBalance(ACCOUNT_ID, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(entry);
    }

    @Test
    void getBalancePropagatesAccessDeniedForNonOwner() {
        when(request.getHeader("X-User-Id")).thenReturn(USER_ID.toString());
        when(queryService.getBalanceCached(ACCOUNT_ID, USER_ID))
                .thenThrow(new AccessDeniedException("not your account"));

        assertThatThrownBy(() -> controller.getBalance(ACCOUNT_ID, request))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getAccountAnalyticsReturns404WhenNull() {
        when(request.getHeader("X-User-Id")).thenReturn(USER_ID.toString());
        when(queryService.getAnalytics(ACCOUNT_ID, USER_ID)).thenReturn(null);

        ResponseEntity<?> response = controller.getAccountAnalytics(ACCOUNT_ID, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
