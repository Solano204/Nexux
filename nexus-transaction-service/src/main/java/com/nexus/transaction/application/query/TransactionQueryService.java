package com.nexus.transaction.application.query;

import com.nexus.transaction.domain.model.Transaction;
import com.nexus.transaction.infrastructure.elasticsearch.TransactionSearchDocument;
import com.nexus.transaction.infrastructure.elasticsearch.TransactionSearchRepository;
import com.nexus.transaction.infrastructure.persistence.TransactionRepository;
import com.nexus.transaction.web.dto.response.TransactionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionQueryService {

    private final TransactionRepository transactionRepository;
    private final TransactionSearchRepository searchRepository;

    public Page<TransactionResponse> getTransactionHistory(
            UUID userId, Pageable pageable) {
        return transactionRepository
                .findByUserIdOrderByInitiatedAtDesc(userId, pageable)
                .map(this::toResponse);
    }

    public TransactionResponse getTransactionDetail(
            UUID transactionId, UUID requestingUserId) {
        Transaction txn = transactionRepository
                .findById(transactionId)
                .orElseThrow(() -> new com.nexus.transaction.domain.exception
                        .TransactionNotFoundException(
                        "Transaction not found: " + transactionId));

        if (!txn.getUserId().equals(requestingUserId)) {
            throw new SecurityException(
                    "Transaction does not belong to requesting user");
        }
        return toResponse(txn);
    }

    public List<TransactionResponse> searchTransactions(
            UUID userId, String query) {
        // Elasticsearch full-text search scoped to this user
        return searchRepository
                .findByUserIdAndDescriptionContainingOrMerchantNameContaining(
                        userId.toString(), query, query)
                .stream()
                .map(this::fromSearchDoc)
                .toList();
    }

    private TransactionResponse toResponse(Transaction t) {
        return new TransactionResponse(
                t.getTransactionId().toString(),
                t.getStatus().name(),
                t.getAmount(),
                t.getCurrency(),
                t.getTransactionType().name(),
                t.getDescription(),
                t.getInitiatedAt().toString(),
                t.getCompletedAt() != null
                        ? t.getCompletedAt().toString() : null,
                t.getFailureReason()
        );
    }

    private TransactionResponse fromSearchDoc(
            TransactionSearchDocument d) {
        return new TransactionResponse(
                d.getTransactionId(), d.getStatus(),
                d.getAmount(), d.getCurrency(),
                d.getTransactionType(), d.getDescription(),
                d.getInitiatedAt() != null
                        ? d.getInitiatedAt().toString() : null,
                d.getCompletedAt() != null
                        ? d.getCompletedAt().toString() : null,
                d.getFailureReason()
        );
    }
}