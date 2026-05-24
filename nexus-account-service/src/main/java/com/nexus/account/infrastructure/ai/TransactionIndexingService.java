package com.nexus.account.infrastructure.ai;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TransactionIndexingService — Indexes transactions into pgvector for RAG.
 *
 * Every balance operation (reserve, release, finalize, credit) is
 * converted to a natural language document and embedded into pgvector.
 * This powers the Account Advisor's Advanced RAG pipeline (Section 10).
 *
 * Document format:
 * "[date] [type] of MXN [amount] — [description]. Balance after: MXN [balance]"
 *
 * Metadata includes accountId (for security filtering), category,
 * date, and amount — used by the RetrievalAugmentationAdvisor for
 * per-user filtering and citation headers.
 *
 * Spring AI 1.0.0-M6: Document uses builder pattern (no setId method).
 */
@Slf4j
@Service
public class TransactionIndexingService {

    private final VectorStore vectorStore;
    private final ObservationRegistry observationRegistry;
    private final Counter indexedTransactionsCounter;
    private final Counter indexingErrorCounter;

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.of("America/Mexico_City"));

    public TransactionIndexingService(
            VectorStore vectorStore,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) {
        this.vectorStore = vectorStore;
        this.observationRegistry = observationRegistry;

        this.indexedTransactionsCounter = Counter.builder(
                        "account.ai.transactions.indexed.total")
                .description("Total transactions indexed into pgvector")
                .register(meterRegistry);

        this.indexingErrorCounter = Counter.builder(
                        "account.ai.transactions.indexing.errors.total")
                .description("Transaction indexing errors")
                .register(meterRegistry);
    }

    /**
     * Index a balance reservation event (debit side of transfer).
     */
    public void indexReservation(UUID accountId, UUID transactionId,
                                 BigDecimal amount, BigDecimal balanceAfter,
                                 String description, Instant occurredAt) {
        indexTransaction(
                accountId, transactionId, "DEBIT_RESERVATION",
                amount, balanceAfter,
                description != null ? description : "Balance reserved for transfer",
                "transfer", occurredAt);
    }

    /**
     * Index a balance release event (SAGA compensation).
     */
    public void indexRelease(UUID accountId, UUID transactionId,
                             BigDecimal amount, BigDecimal balanceAfter,
                             Instant occurredAt) {
        indexTransaction(
                accountId, transactionId, "RELEASE",
                amount, balanceAfter,
                "Transfer cancelled — funds returned",
                "transfer_reversal", occurredAt);
    }

    /**
     * Index a finalized credit event (receiving transfer).
     */
    public void indexCredit(UUID accountId, UUID transactionId,
                            BigDecimal amount, BigDecimal balanceAfter,
                            String sourceDescription, Instant occurredAt) {
        indexTransaction(
                accountId, transactionId, "CREDIT",
                amount, balanceAfter,
                sourceDescription != null ? sourceDescription : "Incoming transfer received",
                "income", occurredAt);
    }

    /**
     * Index a finalized debit event (outgoing transfer completed).
     */
    public void indexFinalizedDebit(UUID accountId, UUID transactionId,
                                    BigDecimal amount, BigDecimal balanceAfter,
                                    String targetDescription, Instant occurredAt) {
        indexTransaction(
                accountId, transactionId, "DEBIT_FINALIZED",
                amount, balanceAfter,
                targetDescription != null ? targetDescription : "Outgoing transfer completed",
                "transfer", occurredAt);
    }

    /**
     * Index an interest application event (savings accounts).
     */
    public void indexInterest(UUID accountId, BigDecimal amount,
                              BigDecimal balanceAfter, Instant occurredAt) {
        indexTransaction(
                accountId, null, "INTEREST",
                amount, balanceAfter,
                "Monthly interest applied to savings account",
                "interest", occurredAt);
    }

    /**
     * Core indexing method — builds the Document and adds to pgvector.
     *
     * Spring AI 1.0.0-M6: Uses Document.builder() pattern.
     * No setId() method exists — id is set via builder.
     */
    private void indexTransaction(UUID accountId, UUID transactionId,
                                  String type, BigDecimal amount,
                                  BigDecimal balanceAfter,
                                  String description, String category,
                                  Instant occurredAt) {

        Observation obs = Observation.createNotStarted(
                "account.ai.index-transaction", observationRegistry).start();

        try {
            String formattedDate = DATE_FORMATTER.format(occurredAt);
            String amountStr = amount.toPlainString();
            String balanceStr = balanceAfter != null
                    ? balanceAfter.toPlainString() : "unknown";

            // Natural language document for embedding
            String content = String.format(
                    "[%s] %s of MXN %s — %s. Balance after: MXN %s",
                    formattedDate, type, amountStr, description, balanceStr);

            // Deterministic document ID
            String docId = transactionId != null
                    ? transactionId.toString() + "-" + type
                    : accountId.toString() + "-" + type + "-" + occurredAt.toEpochMilli();

            // Spring AI 1.0.0-M6: Document.builder() pattern
            Document doc = Document.builder()
                    .id(docId)
                    .text(content)
                    .metadata("account_id", accountId.toString())
                    .metadata("transaction_id", transactionId != null
                            ? transactionId.toString() : "N/A")
                    .metadata("type", type)
                    .metadata("amount", amountStr)
                    .metadata("category", category)
                    .metadata("date", formattedDate)
                    .metadata("timestamp", occurredAt.toString())
                    .build();

            vectorStore.add(List.of(doc));

            indexedTransactionsCounter.increment();
            obs.event(Observation.Event.of("transaction.indexed"));

            log.debug("Indexed transaction: accountId={} type={} amount={}",
                    accountId, type, amountStr);

        } catch (Exception e) {
            indexingErrorCounter.increment();
            obs.error(e);
            log.warn("Failed to index transaction for accountId={}: {}",
                    accountId, e.getMessage());
            // Non-critical: indexing failure should NOT block the transaction
        } finally {
            obs.stop();
        }
    }

    /**
     * Batch index multiple transactions — used for backfill operations.
     * Processes in chunks of 50 to avoid overwhelming the embedding API.
     */
    public void batchIndex(List<TransactionToIndex> transactions) {
        int chunkSize = 50;
        for (int i = 0; i < transactions.size(); i += chunkSize) {
            List<TransactionToIndex> chunk = transactions.subList(
                    i, Math.min(i + chunkSize, transactions.size()));

            List<Document> docs = chunk.stream()
                    .map(this::toDocument)
                    .toList();

            try {
                vectorStore.add(docs);
                indexedTransactionsCounter.increment(docs.size());
                log.info("Batch indexed {} transactions (chunk {}/{})",
                        docs.size(), (i / chunkSize) + 1,
                        (transactions.size() + chunkSize - 1) / chunkSize);
            } catch (Exception e) {
                indexingErrorCounter.increment(docs.size());
                log.error("Batch indexing failed at chunk {}: {}",
                        (i / chunkSize) + 1, e.getMessage());
            }
        }
    }

    private Document toDocument(TransactionToIndex tx) {
        String formattedDate = DATE_FORMATTER.format(tx.occurredAt());
        String content = String.format(
                "[%s] %s of MXN %s — %s. Balance after: MXN %s",
                formattedDate, tx.type(), tx.amount().toPlainString(),
                tx.description(),
                tx.balanceAfter() != null ? tx.balanceAfter().toPlainString() : "unknown");

        String docId = tx.transactionId() != null
                ? tx.transactionId().toString() + "-" + tx.type()
                : tx.accountId().toString() + "-" + tx.type() + "-" + tx.occurredAt().toEpochMilli();

        return Document.builder()
                .id(docId)
                .text(content)
                .metadata("account_id", tx.accountId().toString())
                .metadata("transaction_id", tx.transactionId() != null
                        ? tx.transactionId().toString() : "N/A")
                .metadata("type", tx.type())
                .metadata("amount", tx.amount().toPlainString())
                .metadata("category", tx.category())
                .metadata("date", formattedDate)
                .metadata("timestamp", tx.occurredAt().toString())
                .build();
    }

    /**
     * Record for batch indexing operations.
     */
    public record TransactionToIndex(
            UUID accountId,
            UUID transactionId,
            String type,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String description,
            String category,
            Instant occurredAt
    ) {}
}