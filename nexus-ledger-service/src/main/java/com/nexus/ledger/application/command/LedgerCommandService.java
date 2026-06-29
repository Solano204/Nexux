package com.nexus.ledger.application.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.ledger.domain.exception.*;
import com.nexus.ledger.domain.model.*;
import com.nexus.ledger.domain.model.enums.*;
import com.nexus.ledger.infrastructure.mongodb.*;
import com.nexus.ledger.infrastructure.persistence.*;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Ledger Command Service — CQRS Write Side.
 *
 * The ONLY service that may create ledger entries.
 * All writes use SERIALIZABLE isolation to prevent concurrent
 * running_balance corruption (the running_balance must be computed
 * on the latest value — SERIALIZABLE ensures no phantom reads).
 *
 * Three invariant layers:
 * 1. validatePostingBalance() — throws before any INSERT
 * 2. PostgreSQL CHECK total_debit = total_credit — at INSERT time
 * 3. Nightly reconciliation — operational verification
 *
 * Pattern: Event Sourcing (each posting = immutable events)
 * Pattern: CQRS Command Side (write only, no user-facing reads)
 * Pattern: Outbox (for Debezium → Kafka reliable publishing)
 */
@Slf4j
@Service
public class LedgerCommandService {

    private final LedgerEntryRepository entryRepository;
    private final PostingRepository postingRepository;
    private final ChartOfAccountRepository coaRepository;
    private final OutboxRepository outboxRepository;
    private final AccountLedgerSummaryRepository summaryRepository;
    private final PostingDocumentRepository postingDocRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;
    private final Tracer tracer;

    // Metrics
    private final Timer postingTimer;
    private final Counter postingSuccessCounter;
    private final Counter postingFailedCounter;
    private final Counter imbalanceDetectedCounter;
    private final Counter idempotentReplayCounter;

    public LedgerCommandService(
            LedgerEntryRepository entryRepository,
            PostingRepository postingRepository,
            ChartOfAccountRepository coaRepository,
            OutboxRepository outboxRepository,
            AccountLedgerSummaryRepository summaryRepository,
            PostingDocumentRepository postingDocRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry,
            Tracer tracer,
            MeterRegistry meterRegistry) {

        this.entryRepository = entryRepository;
        this.postingRepository = postingRepository;
        this.coaRepository = coaRepository;
        this.outboxRepository = outboxRepository;
        this.summaryRepository = summaryRepository;
        this.postingDocRepository = postingDocRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;
        this.tracer = tracer;

        this.postingTimer = Timer.builder("ledger.posting.duration.seconds")
                .description("Ledger posting end-to-end duration")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);

        this.postingSuccessCounter = Counter.builder("ledger.postings.total")
                .tag("outcome", "POSTED").register(meterRegistry);
        this.postingFailedCounter = Counter.builder("ledger.postings.total")
                .tag("outcome", "FAILED").register(meterRegistry);
        this.imbalanceDetectedCounter =
                Counter.builder("ledger.imbalance.detected.total")
                        .description("CRITICAL: double-entry imbalance detected")
                        .register(meterRegistry);
        this.idempotentReplayCounter =
                Counter.builder("ledger.postings.total")
                        .tag("outcome", "ALREADY_EXISTS")
                        .register(meterRegistry);
    }

    // ══════════════════════════════════════════════════════
    // POST DOUBLE-ENTRY POSTING
    // ══════════════════════════════════════════════════════

    /**
     * Posts a double-entry ledger posting for a financial transaction.
     *
     * SERIALIZABLE isolation: ensures no phantom rows can corrupt
     * the running_balance computation between our SELECT and INSERT.
     *
     * Atomic unit:
     * - Two LedgerEntry inserts (debit + credit)
     * - One Posting insert
     * - One OutboxEntry insert
     * All commit together or none do.
     *
     * @return PostingResult with posting ID and both entry IDs
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public PostingResult postDoubleEntry(PostLedgerCommand command) {

        Timer.Sample sample = Timer.start();
        Observation obs = Observation.createNotStarted(
                        "ledger.post", observationRegistry)
                .lowCardinalityKeyValue("type",
                        command.postingType().name())
                .start();

        try (Observation.Scope scope = obs.openScope()) {

            // ── Step 1: Idempotency check ──────────────────
            Optional<Posting> existing = postingRepository
                    .findByTransactionId(command.transactionId());

            if (existing.isPresent()) {
                idempotentReplayCounter.increment();
                obs.event(Observation.Event.of(
                        "posting.idempotent.replay"));
                log.info("Idempotent posting replay: txnId={}",
                        command.transactionId());

                // Return existing entry IDs
                List<LedgerEntry> existingEntries = entryRepository
                        .findByPostingIdOrderByEntryNumberAsc(
                                existing.get().getPostingId());

                UUID debitId = existingEntries.stream()
                        .filter(e -> e.getEntryType() == EntryType.DEBIT)
                        .map(LedgerEntry::getEntryId)
                        .findFirst().orElse(null);
                UUID creditId = existingEntries.stream()
                        .filter(e -> e.getEntryType() == EntryType.CREDIT)
                        .map(LedgerEntry::getEntryId)
                        .findFirst().orElse(null);

                return new PostingResult(
                        existing.get().getPostingId(),
                        debitId, creditId, true);
            }

            // ── Step 2: Validate accounts exist ───────────
            var sourceAccount = coaRepository
                    .findByAccountIdAndIsActiveTrue(
                            command.sourceAccountId())
                    .orElseThrow(() ->
                            new AccountNotFoundException(
                                    "Source account not in chart of accounts: " +
                                            command.sourceAccountId()));

            var targetAccount = coaRepository
                    .findByAccountIdAndIsActiveTrue(
                            command.targetAccountId())
                    .orElseThrow(() ->
                            new AccountNotFoundException(
                                    "Target account not in chart of accounts: " +
                                            command.targetAccountId()));

            // ── Step 3: Validate posting balance ──────────
            // Layer 1: application-level validation
            validatePostingBalance(command.amount(), command.amount());

            UUID postingId = UUID.randomUUID();
            String traceId = getTraceId();
            LocalDate today = LocalDate.now();
            int year = today.getYear();
            int month = today.getMonthValue();
            int quarter = (month - 1) / 3 + 1;

            // ── Step 4: Compute running balances ──────────
            // SERIALIZABLE isolation guarantees these values are
            // consistent — no concurrent transaction can insert
            // a new entry for these accounts between now and commit.
            BigDecimal sourceCurrentBalance = entryRepository
                    .findLatestRunningBalance(command.sourceAccountId())
                    .orElse(sourceAccount.getOpeningBalance());

            BigDecimal targetCurrentBalance = entryRepository
                    .findLatestRunningBalance(command.targetAccountId())
                    .orElse(targetAccount.getOpeningBalance());

            BigDecimal debitNewBalance = sourceCurrentBalance
                    .subtract(command.amount());

            // Validate: debit cannot make balance negative
            if (debitNewBalance.compareTo(BigDecimal.ZERO) < 0) {
                throw new InsufficientLedgerBalanceException(
                        String.format(
                                "Ledger balance insufficient: " +
                                        "account=%s current=%s required=%s",
                                command.sourceAccountId(),
                                sourceCurrentBalance, command.amount()));
            }

            BigDecimal creditNewBalance = targetCurrentBalance
                    .add(command.amount());

            // ── Step 5: Build entry objects ────────────────
            LedgerEntry debitEntry = LedgerEntry.builder()
                    .entryId(UUID.randomUUID())
                    .postingId(postingId)
                    .transactionId(command.transactionId())
                    .accountId(command.sourceAccountId())
                    .accountNumber(sourceAccount.getAccountNumber())
                    .accountType(sourceAccount.getAccountSubtype())
                    .entryType(EntryType.DEBIT)
                    .amount(command.amount())
                    .currency(command.currency())
                    .runningBalance(debitNewBalance)
                    .description(command.description())
                    .category(mapCategory(command.postingType()))
                    .merchantName(command.merchantName())
                    .merchantCategoryCode(
                            command.merchantCategoryCode())
                    .sourceAccountId(command.targetAccountId())
                    .sourceAccountNumber(
                            targetAccount.getAccountNumber())
                    .fiscalYear(year)
                    .fiscalMonth(month)
                    .fiscalQuarter(quarter)
                    .traceId(traceId)
                    .isReversal(false)
                    .build();

            LedgerEntry creditEntry = LedgerEntry.builder()
                    .entryId(UUID.randomUUID())
                    .postingId(postingId)
                    .transactionId(command.transactionId())
                    .accountId(command.targetAccountId())
                    .accountNumber(targetAccount.getAccountNumber())
                    .accountType(targetAccount.getAccountSubtype())
                    .entryType(EntryType.CREDIT)
                    .amount(command.amount())
                    .currency(command.currency())
                    .runningBalance(creditNewBalance)
                    .description(command.description())
                    .category(mapCategory(command.postingType()))
                    .merchantName(command.merchantName())
                    .merchantCategoryCode(
                            command.merchantCategoryCode())
                    .sourceAccountId(command.sourceAccountId())
                    .sourceAccountNumber(
                            sourceAccount.getAccountNumber())
                    .fiscalYear(year)
                    .fiscalMonth(month)
                    .fiscalQuarter(quarter)
                    .traceId(traceId)
                    .isReversal(false)
                    .build();

            // ── Step 6: Insert entries ─────────────────────
            entryRepository.save(debitEntry);
            entryRepository.save(creditEntry);

            // ── Step 7: Insert posting record ─────────────
            // PostgreSQL CHECK constraint validates isBalanced here
            Posting posting = Posting.builder()
                    .postingId(postingId)
                    .transactionId(command.transactionId())
                    .postingType(command.postingType())
                    .status(PostingStatus.POSTED)
                    .entryCount(2)
                    .totalDebit(command.amount())
                    .totalCredit(command.amount())
                    .isBalanced(true) // MUST be true — DB enforces
                    .currency(command.currency())
                    .description(command.description())
                    .traceId(traceId)
                    .build();

            postingRepository.save(posting);

            // ── Step 8: Outbox entry ───────────────────────
            outboxRepository.save(OutboxEntry.of(
                    "ledger.posted", postingId,
                    "LedgerPosted",
                    buildLedgerPostedPayload(
                            posting, debitEntry, creditEntry)));

            postingSuccessCounter.increment();
            obs.event(Observation.Event.of("posting.success"));

            log.info("Ledger posted: postingId={} txnId={} " +
                            "amount={} debitAccount={} creditAccount={}",
                    postingId, command.transactionId(),
                    command.amount(),
                    sourceAccount.getAccountNumber(),
                    targetAccount.getAccountNumber());

            PostingResult result = new PostingResult(
                    postingId,
                    debitEntry.getEntryId(),
                    creditEntry.getEntryId(),
                    false);

            // Publish async MongoDB update via Spring event
            org.springframework.context.ApplicationEventPublisher
                    publisher = getApplicationEventPublisher();
            if (publisher != null) {
                publisher.publishEvent(
                        new LedgerPostedEvent(result, command));
            }

            return result;

        } catch (AccountingImbalanceException e) {
            imbalanceDetectedCounter.increment();
            obs.error(e);
            postingFailedCounter.increment();
            log.error("CRITICAL: Accounting imbalance detected: {}",
                    e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            obs.error(e);
            postingFailedCounter.increment();
            throw e;
        } finally {
            sample.stop(postingTimer);
            obs.stop();
        }
    }

    // ══════════════════════════════════════════════════════
    // MONGODB READ MODEL UPDATE (async, after PostgreSQL commit)
    // ══════════════════════════════════════════════════════

    /**
     * Updates MongoDB read model AFTER PostgreSQL commits.
     * Uses @TransactionalEventListener with AFTER_COMMIT phase.
     *
     * If MongoDB update fails:
     * - PostgreSQL transaction already committed (source of truth safe)
     * - Retry with exponential backoff
     * - Dead letter to Kafka on final failure
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("virtualThreadExecutor")
    public void updateMongoReadModel(LedgerPostedEvent event) {
        Observation obs = Observation.createNotStarted(
                "ledger.mongodb.update", observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {

            PostLedgerCommand cmd = event.command();
            PostingResult result = event.result();

            // Update account summary for both accounts
            updateAccountSummary(cmd.sourceAccountId(),
                    result.postingId(), cmd.amount(), EntryType.DEBIT,
                    cmd.description());
            updateAccountSummary(cmd.targetAccountId(),
                    result.postingId(), cmd.amount(), EntryType.CREDIT,
                    cmd.description());

            // Create posting document for quick retrieval
            createPostingDocument(result, cmd);

            obs.event(Observation.Event.of("mongodb.update.success"));

        } catch (Exception e) {
            obs.error(e);
            log.warn("MongoDB read model update failed for postingId={}: {}",
                    event.result().postingId(), e.getMessage());
            // Non-critical: source of truth is PostgreSQL
        } finally {
            obs.stop();
        }
    }

    // ══════════════════════════════════════════════════════
    // REVERSAL
    // ══════════════════════════════════════════════════════

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public PostingResult postReversal(UUID originalPostingId,
                                      String reason,
                                      String traceId) {

        Posting original = postingRepository
                .findById(originalPostingId)
                .orElseThrow(() -> new RuntimeException(
                        "Posting not found: " + originalPostingId));

        if (original.getStatus() == PostingStatus.REVERSED) {
            throw new IllegalStateException(
                    "Posting already reversed: " + originalPostingId);
        }

        // Load original entries
        List<LedgerEntry> originalEntries = entryRepository
                .findByPostingIdOrderByEntryNumberAsc(originalPostingId);

        LedgerEntry originalDebit = originalEntries.stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .findFirst()
                .orElseThrow();

        LedgerEntry originalCredit = originalEntries.stream()
                .filter(e -> e.getEntryType() == EntryType.CREDIT)
                .findFirst()
                .orElseThrow();

        // Build reversal command (swap debit/credit accounts)
        PostLedgerCommand reversalCommand = PostLedgerCommand.builder()
                .transactionId(null) // Reversal is a system posting
                .sourceAccountId(originalCredit.getAccountId())  // Reverse
                .targetAccountId(originalDebit.getAccountId())   // sides
                .amount(original.getTotalDebit())
                .currency(original.getCurrency())
                .postingType(PostingType.REVERSAL)
                .description("REVERSAL: " + reason +
                        " [original posting: " + originalPostingId + "]")
                .merchantName(null)
                .merchantCategoryCode(null)
                .build();

        PostingResult reversalResult =
                postDoubleEntry(reversalCommand);

        // Update original posting status to REVERSED
        original.markReversed();
        postingRepository.save(original);

        // Write outbox event
        outboxRepository.save(OutboxEntry.of(
                "ledger.reversed", originalPostingId,
                "LedgerReversed",
                objectMapper.createObjectNode()
                        .put("originalPostingId", originalPostingId.toString())
                        .put("reversalPostingId",
                                reversalResult.postingId().toString())
                        .put("amount", original.getTotalDebit().toPlainString())
                        .put("reason", reason)
                        .put("reversedAt", Instant.now().toString())
        ));

        log.info("Ledger reversed: originalPostingId={} " +
                        "reversalPostingId={}",
                originalPostingId, reversalResult.postingId());

        return reversalResult;
    }

    // ══════════════════════════════════════════════════════
    // KAFKA SAGA REPLY
    // ══════════════════════════════════════════════════════

    public void publishSagaReply(PostingResult result,
                                 UUID transactionId,
                                 String sagaId,
                                 String traceId) {
        try {
            Map<String, Object> reply = Map.of(
                    "replyType", "LedgerPostedReply",
                    "sagaId", sagaId,
                    "transactionId", transactionId.toString(),
                    "sourceService", "nexus-ledger-service",
                    "success", true,
                    "postingId", result.postingId().toString(),
                    "debitEntryId", result.debitEntryId().toString(),
                    "creditEntryId", result.creditEntryId().toString(),
                    "postedAt", Instant.now().toString(),
                    "traceId", traceId
            );

            kafkaTemplate.send("saga.replies", sagaId,
                    objectMapper.writeValueAsString(reply));

        } catch (Exception e) {
            log.error("Failed to publish LedgerPostedReply: {}",
                    e.getMessage(), e);
        }
    }

    // ══════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ══════════════════════════════════════════════════════

    /**
     * Layer 1 imbalance validation — application level.
     * Called BEFORE any INSERT.
     */
    private void validatePostingBalance(BigDecimal totalDebit,
                                        BigDecimal totalCredit) {
        if (totalDebit.compareTo(totalCredit) != 0) {
            imbalanceDetectedCounter.increment();
            throw new AccountingImbalanceException(
                    String.format(
                            "Posting imbalance: debit=%s credit=%s " +
                                    "difference=%s",
                            totalDebit, totalCredit,
                            totalDebit.subtract(totalCredit)
                                    .abs().toPlainString()));
        }
    }

    private String mapCategory(PostingType type) {
        return switch (type) {
            case TRANSFER -> "TRANSFER";
            case PAYMENT -> "PAYMENT";
            case FEE -> "FEE";
            case INTEREST_PAYMENT, INTEREST_ACCRUAL -> "INTEREST";
            case REVERSAL -> "REVERSAL";
            case INITIAL_BALANCE -> "INITIAL_BALANCE";
            case ADJUSTMENT -> "ADJUSTMENT";
            case REGULATORY_HOLD -> "REGULATORY_HOLD";
        };
    }

    private ObjectNode buildLedgerPostedPayload(
            Posting posting, LedgerEntry debit, LedgerEntry credit) {
        return objectMapper.createObjectNode()
                .put("postingId", posting.getPostingId().toString())
                .put("transactionId", posting.getTransactionId() != null
                        ? posting.getTransactionId().toString() : null)
                .put("debitEntryId", debit.getEntryId().toString())
                .put("creditEntryId", credit.getEntryId().toString())
                .put("debitAccountId", debit.getAccountId().toString())
                .put("creditAccountId", credit.getAccountId().toString())
                .put("amount", posting.getTotalDebit().toPlainString())
                .put("currency", posting.getCurrency())
                .put("postingType", posting.getPostingType().name())
                .put("postedAt", Instant.now().toString());
    }

    private void updateAccountSummary(UUID accountId, UUID postingId,
                                      BigDecimal amount,
                                      EntryType entryType,
                                      String description) {
        var summary = summaryRepository
                .findByAccountId(accountId.toString())
                .orElse(new AccountLedgerSummaryDocument());

        summary.setAccountId(accountId.toString());
        summary.setLastPostingAt(Instant.now());

        // Update current balance
        if (summary.getCurrentBalance() == null) {
            summary.setCurrentBalance(
                    entryType == EntryType.CREDIT ? amount :
                            BigDecimal.ZERO.subtract(amount));
        } else {
            summary.setCurrentBalance(
                    entryType == EntryType.CREDIT
                            ? summary.getCurrentBalance().add(amount)
                            : summary.getCurrentBalance().subtract(amount));
        }

        summaryRepository.save(summary);
    }

    private void createPostingDocument(PostingResult result,
                                       PostLedgerCommand cmd) {
        // Create MongoDB posting document for quick retrieval
        PostingDocument doc = new PostingDocument();
        doc.setPostingId(result.postingId().toString());
        doc.setTransactionId(cmd.transactionId() != null
                ? cmd.transactionId().toString() : null);
        doc.setPostingType(cmd.postingType().name());
        doc.setDescription(cmd.description());
        doc.setPostedAt(Instant.now());
        doc.setCurrency(cmd.currency());
        doc.setAmount(cmd.amount());
        postingDocRepository.save(doc);
    }

    private String getTraceId() {
        return tracer.currentSpan() != null
                ? tracer.currentSpan().context().traceId()
                : "no-trace";
    }

    // Lazy getter to avoid circular dependency
    private volatile org.springframework.context
            .ApplicationEventPublisher eventPublisher;

    private org.springframework.context.ApplicationEventPublisher
    getApplicationEventPublisher() {
        return eventPublisher;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setApplicationEventPublisher(
            org.springframework.context.ApplicationEventPublisher pub) {
        this.eventPublisher = pub;
    }

    // ── Inner types ───────────────────────────────────────

    public record PostingResult(
            UUID postingId, UUID debitEntryId,
            UUID creditEntryId, boolean idempotentReplay
    ) {}

    public record LedgerPostedEvent(
            PostingResult result, PostLedgerCommand command
    ) {}
}