package com.nexus.transaction.unit;

import com.nexus.transaction.application.query.TransactionQueryService;
import com.nexus.transaction.domain.exception.TransactionNotFoundException;
import com.nexus.transaction.domain.model.Transaction;
import com.nexus.transaction.domain.model.enums.TransactionStatus;
import com.nexus.transaction.domain.model.enums.TransactionType;
import com.nexus.transaction.infrastructure.elasticsearch.TransactionSearchDocument;
import com.nexus.transaction.infrastructure.elasticsearch.TransactionSearchRepository;
import com.nexus.transaction.infrastructure.persistence.TransactionRepository;
import com.nexus.transaction.web.dto.response.TransactionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionQueryServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private TransactionSearchRepository searchRepository;

    private TransactionQueryService service;
    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TransactionQueryService(transactionRepository, searchRepository);
    }

    private Transaction transaction() {
        return Transaction.builder()
                .transactionId(UUID.randomUUID())
                .userId(USER_ID)
                .amount(new BigDecimal("250.00"))
                .currency("MXN")
                .transactionType(TransactionType.INTERNAL_TRANSFER)
                .status(TransactionStatus.COMPLETED)
                .initiatedAt(Instant.now())
                .completedAt(Instant.now())
                .build();
    }

    @Test
    void getTransactionHistoryMapsPageOfTransactions() {
        Pageable pageable = Pageable.unpaged();
        Page<Transaction> page = new PageImpl<>(List.of(transaction()));
        when(transactionRepository.findByUserIdOrderByInitiatedAtDesc(USER_ID, pageable)).thenReturn(page);

        Page<TransactionResponse> result = service.getTransactionHistory(USER_ID, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).status()).isEqualTo("COMPLETED");
    }

    @Test
    void getTransactionDetailReturnsTransactionForOwner() {
        Transaction txn = transaction();
        when(transactionRepository.findById(txn.getTransactionId())).thenReturn(Optional.of(txn));

        TransactionResponse response = service.getTransactionDetail(txn.getTransactionId(), USER_ID);

        assertThat(response.transactionId()).isEqualTo(txn.getTransactionId().toString());
    }

    @Test
    void getTransactionDetailThrowsWhenNotFound() {
        UUID missingId = UUID.randomUUID();
        when(transactionRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTransactionDetail(missingId, USER_ID))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    void getTransactionDetailRejectsAccessByNonOwner() {
        Transaction txn = transaction();
        UUID otherUser = UUID.randomUUID();
        when(transactionRepository.findById(txn.getTransactionId())).thenReturn(Optional.of(txn));

        assertThatThrownBy(() -> service.getTransactionDetail(txn.getTransactionId(), otherUser))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void searchTransactionsMapsSearchDocuments() {
        TransactionSearchDocument doc = TransactionSearchDocument.builder()
                .transactionId(UUID.randomUUID().toString())
                .userId(USER_ID.toString())
                .amount(new BigDecimal("99.00"))
                .currency("MXN")
                .status("COMPLETED")
                .transactionType("PAYMENT")
                .merchantName("Amazon MX")
                .initiatedAt(Instant.now())
                .build();
        when(searchRepository.findByUserIdAndDescriptionContainingOrMerchantNameContaining(
                USER_ID.toString(), "amazon", "amazon")).thenReturn(List.of(doc));

        List<TransactionResponse> results = service.searchTransactions(USER_ID, "amazon");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).transactionType()).isEqualTo("PAYMENT");
    }

    @Test
    void searchTransactionsReturnsEmptyListWhenNoMatches() {
        when(searchRepository.findByUserIdAndDescriptionContainingOrMerchantNameContaining(
                USER_ID.toString(), "xyz", "xyz")).thenReturn(List.of());

        List<TransactionResponse> results = service.searchTransactions(USER_ID, "xyz");

        assertThat(results).isEmpty();
    }
}
