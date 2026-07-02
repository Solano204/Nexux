# nexus-transaction-service
## POST /api/v1/transactions/transfer

```
Trigger legend
──────────────────────────────────────────────────────────────
[HTTP]      client → API Gateway → service (synchronous REST)
[CDC]       Debezium reads outbox table WAL → publishes to Kafka
[KAFKA-L]   service has @KafkaListener always running on that topic
            message arrives → listener method fires
[PGVEC-R]   pgvector READ  — semantic similarity search (SELECT)
[PGVEC-W]   pgvector WRITE — vector embedding indexed (INSERT)
──────────────────────────────────────────────────────────────
Two participant topics:
  saga.commands — ALL commands land here, each service
                  filters by commandType + targetService field
  saga.replies  — ALL replies land here, saga orchestrator
                  filters by replyType field
──────────────────────────────────────────────────────────────


═══════════════════════════════════════════════════════════════
Step 1
Service:  nexus-api-gateway:8080 → nexus-transaction-service:8086
Trigger:  [HTTP] client POSTs /api/v1/transactions/transfer
DB:       nexus_transactions (PostgreSQL)
═══════════════════════════════════════════════════════════════
transactions — INSERT
  transaction_id    = <new UUID>
  status            = INITIATED
  saga_id           = <new UUID>
  idempotency_key   = <from request>
  source_account_id = <UUID>
  target_account_id = <UUID>
  amount            = 1500.0000
  currency          = MXN
  transaction_type  = INTERNAL_TRANSFER
  version           = 0
  initiated_at      = NOW()

outbox — INSERT
  event_type   = TransactionInitiated
  topic        = transactions.initiated
  processed_at = NULL

  ↓ Debezium reads outbox WAL row → publishes to Kafka
    topic: transactions.initiated
    key:   transactionId

HTTP 202 returned to client immediately.

[PGVEC-W] nexus-audit-query-jvm — ASYNC (fires when Kafka event arrives)
  table: audit_event_embeddings  (nexus_audit DB)
  AuditIndexingConsumer listens to transactions.initiated
  → embeds event + metadata → INSERT into audit_event_embeddings
  This write is independent of the saga — it is best-effort.


═══════════════════════════════════════════════════════════════
Step 2
Service:  nexus-saga-orchestrator:8095
Trigger:  [KAFKA-L] @KafkaListener(topics="transactions.initiated")
          SagaEventConsumer.consumeTransactionEvent() fires
DB:       nexus_saga (PostgreSQL)
═══════════════════════════════════════════════════════════════
transfer_saga_states — INSERT
  saga_id               = <UUID>
  transaction_id        = <UUID>
  current_step          = BALANCE_RESERVING
  funds_reserved        = false
  compensation_attempts = 0
  version               = 0
  started_at            = NOW()

saga_step_history — INSERT
  STARTED → BALANCE_RESERVING

saga_timeouts — INSERT
  timeout_type = BALANCE_RESERVATION
  fires_at     = NOW() + 30s
  is_cancelled = false

outbox — INSERT
  event_type = ReserveBalanceCommand
  topic      = saga.commands
  payload    = { commandType: "ReserveBalanceCommand",
                 targetService: "nexus-account-service",
                 sagaId, accountId, amount, currency }

  ↓ Debezium reads saga outbox WAL row → publishes to Kafka
    topic: saga.commands
    key:   sagaId


═══════════════════════════════════════════════════════════════
Step 3
Service:  nexus-account-service:8085
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.commands")
          SagaCommandConsumer.consumeSagaCommand() fires
          → checks commandType == "ReserveBalanceCommand"
          → checks targetService == "nexus-account-service"
          → ignores all other commands on this topic
DB:       MongoDB (accounts collection)
═══════════════════════════════════════════════════════════════
accounts — UPDATE
  available_balance -= 1500.00   (10000.00 → 8500.00)
  reserved_amount   += 1500.00

[PGVEC-W] nexus-account-service — SYNC (same thread, after reserve)
  table: transaction_embeddings  (nexus_accounts DB)
  TransactionIndexingService.indexOnReserve() fires
  → builds Document: { accountId, transactionId, amount,
                        currency, type: INTERNAL_TRANSFER,
                        event: BALANCE_RESERVED, timestamp }
  → embeds via OpenAI → INSERT into transaction_embeddings
  This records the reserve event for future RAG queries
  (e.g. AccountAdvisorService answering "show my recent transfers")

  ↓ account-service produces DIRECTLY to Kafka (no outbox)
    topic: saga.replies
    key:   sagaId
    body:  { replyType: "BalanceReservedReply",
             sagaId, newAvailableBalance: 8500.00 }


═══════════════════════════════════════════════════════════════
Step 4a
Service:  nexus-transaction-service:8086
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.replies")
          SagaReplyConsumer.consumeSagaReply() fires
          → checks replyType == "BalanceReservedReply"
          consumer group: transaction-service-saga-replies
DB:       nexus_transactions (PostgreSQL)
═══════════════════════════════════════════════════════════════
transactions — UPDATE  (saveAndFlush — forces separate DB write)
  status  = BALANCE_RESERVING   (was INITIATED)
  version = 1

transactions — UPDATE
  status              = BALANCE_RESERVED
  balance_reserved_at = NOW()
  version             = 2


═══════════════════════════════════════════════════════════════
Step 4b
Service:  nexus-saga-orchestrator:8095
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.replies")
          SagaReplyConsumer.consumeReply() fires
          → checks replyType == "BalanceReservedReply"
          consumer group: saga-orchestrator-replies
          ← SAME Kafka message as Step 4a, different consumer group
             both fire independently and in parallel
DB:       nexus_saga (PostgreSQL)
═══════════════════════════════════════════════════════════════
transfer_saga_states — UPDATE
  current_step          = BALANCE_RESERVED
  funds_reserved        = true
  new_available_balance = 8500.00
  version               = 1

saga_step_history — INSERT  BALANCE_RESERVING → BALANCE_RESERVED

saga_timeouts — UPDATE  BALANCE_RESERVATION → is_cancelled = true
saga_timeouts — INSERT  FRAUD_CHECK  fires_at = NOW() + 60s

outbox — INSERT
  event_type = CheckFraudCommand
  topic      = saga.commands
  payload    = { commandType: "CheckFraudCommand",
                 targetService: "nexus-fraud-service",
                 sagaId, transactionId, sourceUserId, amount }

  ↓ Debezium reads saga outbox WAL row → publishes to Kafka
    topic: saga.commands
    key:   sagaId


═══════════════════════════════════════════════════════════════
Step 5
Service:  nexus-fraud-service:8087
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.commands")
          FraudCommandConsumer.consumeFraudCommand() fires
          → checks commandType == "CheckFraudCommand"
          → checks targetService == "nexus-fraud-service"
          → ignores ReserveBalanceCommand, PostLedgerCommand, etc.
DB:       nexus_fraud (PostgreSQL)  +  OpenAI API (gpt-4o-mini)
═══════════════════════════════════════════════════════════════
AI ReAct pipeline runs (~15–20s):

  Phase 1 — Planning:
    fraudPlanningClient → FraudAnalysisPlan
      (decides which tools to call and in what order)

  Phase 2 — Tool execution (sequential):
    velocity_check_tool       → null (no history on new user)
    account_relationship_tool → null (first transfer)

    [PGVEC-R] rag_policy_tool fires:
      table: fraud_policy_embeddings  (nexus_fraud DB)
      RAG pipeline:
        1. MultiQueryExpander generates 4 query variants
           from the transaction signals context
        2. VectorStoreDocumentRetriever runs similarity search
           → retrieves top-15 policy documents ranked by cosine distance
           Example results (confirmed from live logs):
             score=0.809  New Device + New Country Risk Policy §7.2
             score=0.797  Geographic Anomaly — Impossible Travel §5.3
             score=0.787  Z-Score Amount Anomaly Detection §14.1
             score=0.762  New Counterparty Risk Assessment §9.1
             score=0.763  Established Relationship Risk Reduction §15.1
             ... (10 more policies)
        3. ContextualQueryAugmenter injects the 15 documents
           into the synthesis prompt as grounding context
      This is a pure READ — no writes to fraud_policy_embeddings
      during a transaction. Policies are pre-seeded at startup.

  Phase 3 — Synthesis:
    fraudSynthesisClient → FraudDecision
      { decision: APPROVE, riskScore: 10, confidence: 0.0 }
      (base 0 + new counterparty +10 = 10 → APPROVE)

fraud_decisions — INSERT
  decision_outcome = APPROVE
  risk_score       = 10.00
  confidence_level = 0.0

outbox — INSERT  (saga reply)

  ↓ fraud-service produces DIRECTLY to Kafka (no outbox)
    topic: saga.replies
    key:   sagaId
    body:  { replyType: "FraudClearedReply",
             sagaId, transactionId, riskScore: 10 }

[PGVEC-W] nexus-audit-query-jvm — ASYNC
  table: audit_event_embeddings  (nexus_audit DB)
  AuditIndexingConsumer listens to fraud.result topic
  → embeds fraud result event → INSERT into audit_event_embeddings


═══════════════════════════════════════════════════════════════
Step 6
Service:  nexus-saga-orchestrator:8095
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.replies")
          SagaReplyConsumer.consumeReply() fires
          → checks replyType == "FraudClearedReply"
DB:       nexus_saga (PostgreSQL)
═══════════════════════════════════════════════════════════════
transfer_saga_states — UPDATE
  current_step   = LEDGER_POSTING
  fraud_score    = 10.00
  fraud_decision = CLEARED
  version        = 2

saga_step_history — INSERT  BALANCE_RESERVED → LEDGER_POSTING

saga_timeouts — UPDATE  FRAUD_CHECK → is_cancelled = true
saga_timeouts — INSERT  LEDGER_POST  fires_at = NOW() + 30s

outbox — INSERT
  event_type = PostLedgerCommand
  topic      = saga.commands
  payload    = { commandType: "PostLedgerCommand",
                 targetService: "nexus-ledger-service",
                 sagaId, sourceAccountId, targetAccountId, amount }

  ↓ Debezium reads saga outbox WAL row → publishes to Kafka
    topic: saga.commands


═══════════════════════════════════════════════════════════════
Step 7
Service:  nexus-ledger-service:8088
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.commands")
          → checks commandType == "PostLedgerCommand"
          → checks targetService == "nexus-ledger-service"
DB:       nexus_ledger (PostgreSQL)
═══════════════════════════════════════════════════════════════
ledger_entries — INSERT × 2  (double-entry, one transaction)
  DEBIT:  sourceAccountId  -1500.00
  CREDIT: targetAccountId  +1500.00

  ↓ ledger-service produces DIRECTLY to Kafka (no outbox)
    topic: saga.replies
    body:  { replyType: "LedgerPostedReply",
             sagaId, postingId, debitEntryId, creditEntryId }

[PGVEC-W] nexus-audit-query-jvm — ASYNC
  table: audit_event_embeddings  (nexus_audit DB)
  AuditIndexingConsumer listens to ledger.posted topic
  → embeds ledger event → INSERT into audit_event_embeddings


═══════════════════════════════════════════════════════════════
Step 8a
Service:  nexus-transaction-service:8086
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.replies")
          → checks replyType == "LedgerPostedReply"
          consumer group: transaction-service-saga-replies
DB:       nexus_transactions (PostgreSQL)  +  Elasticsearch:9202
═══════════════════════════════════════════════════════════════
transactions — UPDATE  (saveAndFlush)
  status  = LEDGER_POSTING   (was BALANCE_RESERVED)
  version = 3

transactions — UPDATE  (net DB commit — COMPLETING skipped in DB)
  status           = COMPLETED
  ledger_entry_id  = <UUID>
  completed_at     = NOW()
  version          = 4

outbox — INSERT  topic = transactions.completed

Elasticsearch — async index
  index = transactions
  doc   = { transactionId, status: COMPLETED, amount, ... }

[PGVEC-W] nexus-audit-query-jvm — ASYNC
  table: audit_event_embeddings  (nexus_audit DB)
  AuditIndexingConsumer listens to transactions.completed
  → embeds completed event → INSERT into audit_event_embeddings


═══════════════════════════════════════════════════════════════
Step 8b
Service:  nexus-saga-orchestrator:8095
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.replies")
          → checks replyType == "LedgerPostedReply"
          consumer group: saga-orchestrator-replies
          ← SAME message as Step 8a, different consumer group
DB:       nexus_saga (PostgreSQL)
═══════════════════════════════════════════════════════════════
transfer_saga_states — UPDATE
  current_step      = BALANCE_FINALIZING
  ledger_posting_id = <UUID>
  debit_entry_id    = <UUID>
  credit_entry_id   = <UUID>
  version           = 3

saga_step_history — INSERT  LEDGER_POSTING → BALANCE_FINALIZING

saga_timeouts — UPDATE  LEDGER_POST → is_cancelled = true
saga_timeouts — INSERT  BALANCE_FINALIZE  fires_at = NOW() + 30s

outbox — INSERT
  event_type = FinalizeTransferCommand
  topic      = saga.commands
  payload    = { commandType: "FinalizeTransferCommand",
                 targetService: "nexus-account-service",
                 sagaId, sourceAccountId, targetAccountId,
                 amount, reservationId }

  ↓ Debezium → saga.commands


═══════════════════════════════════════════════════════════════
Step 9
Service:  nexus-account-service:8085
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.commands")
          → checks commandType == "FinalizeTransferCommand"
          → checks targetService == "nexus-account-service"
DB:       MongoDB (accounts collection)
═══════════════════════════════════════════════════════════════
accounts — UPDATE  source
  reserved_amount   -= 1500.00   (1500.00 → 0.00)

accounts — UPDATE  target
  available_balance += 1500.00

[PGVEC-W] nexus-account-service — SYNC (same thread, after finalize)
  table: transaction_embeddings  (nexus_accounts DB)
  TransactionIndexingService.indexOnFinalize() fires
  → builds Document: { accountId, transactionId, amount,
                        event: BALANCE_FINALIZED, timestamp }
  → embeds via OpenAI → INSERT into transaction_embeddings

  ↓ produces DIRECTLY to Kafka
    topic: saga.replies
    body:  { replyType: "BalanceFinalizedReply", sagaId }


═══════════════════════════════════════════════════════════════
Step 10
Service:  nexus-saga-orchestrator:8095
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.replies")
          → checks replyType == "BalanceFinalizedReply"
DB:       nexus_saga (PostgreSQL)
═══════════════════════════════════════════════════════════════
transfer_saga_states — UPDATE
  current_step = NOTIFICATION_SENDING
  version      = 4

outbox — INSERT
  event_type = SendNotificationCommand
  topic      = saga.commands
  payload    = { commandType: "SendTransactionNotificationCommand",
                 targetService: "nexus-notification-service",
                 sagaId, sourceUserId, targetUserId, amount }

  ↓ Debezium → saga.commands


═══════════════════════════════════════════════════════════════
Step 11
Service:  nexus-notification-service:8089
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.commands")
          → checks commandType == "SendTransactionNotificationCommand"
          → checks targetService == "nexus-notification-service"
DB:       nexus_notification (PostgreSQL)
═══════════════════════════════════════════════════════════════
notifications — INSERT
  status  = SENT
  sent_at = NOW()

  ↓ produces DIRECTLY to Kafka
    topic: saga.replies
    body:  { replyType: "NotificationSentReply", sagaId }


═══════════════════════════════════════════════════════════════
Step 12
Service:  nexus-saga-orchestrator:8095
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.replies")
          → checks replyType == "NotificationSentReply"
DB:       nexus_saga (PostgreSQL)
═══════════════════════════════════════════════════════════════
transfer_saga_states — UPDATE  (FINAL STATE)
  current_step = COMPLETED
  completed_at = NOW()
  version      = 5

saga_step_history — INSERT  NOTIFICATION_SENDING → COMPLETED

outbox — INSERT
  event_type = SagaCompleted
  topic      = transactions.saga.completed


══════════════════════════════════════════════════════════════
PGVECTOR SUMMARY — INTERNAL_TRANSFER
══════════════════════════════════════════════════════════════

  Step  │ Table                   │ DB              │ Op    │ Trigger
  ──────┼─────────────────────────┼─────────────────┼───────┼────────────────
  1     │ audit_event_embeddings  │ nexus_audit     │ WRITE │ transactions.initiated Kafka event
  3     │ transaction_embeddings  │ nexus_accounts  │ WRITE │ after balance reserve (sync)
  5     │ fraud_policy_embeddings │ nexus_fraud     │ READ  │ rag_policy_tool — 15 docs retrieved
  5     │ audit_event_embeddings  │ nexus_audit     │ WRITE │ fraud.result Kafka event
  7     │ audit_event_embeddings  │ nexus_audit     │ WRITE │ ledger.posted Kafka event
  8a    │ audit_event_embeddings  │ nexus_audit     │ WRITE │ transactions.completed Kafka event
  9     │ transaction_embeddings  │ nexus_accounts  │ WRITE │ after balance finalize (sync)

  Total distinct tables touched: 3
    fraud_policy_embeddings → 1 READ
    transaction_embeddings  → 2 WRITEs
    audit_event_embeddings  → 4 WRITEs (async, best-effort)


══════════════════════════════════════════════════════════════
COMPENSATION PATH — fraud returns REJECT (after Step 5)
══════════════════════════════════════════════════════════════

═══════════════════════════════════════════════════════════════
Step C1
Service:  nexus-transaction-service:8086
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.replies")
          → checks replyType == "FraudRejectedReply"
          consumer group: transaction-service-saga-replies
DB:       nexus_transactions (PostgreSQL)
═══════════════════════════════════════════════════════════════
transactions — UPDATE
  status           = FRAUD_REJECTED → FAILED
  fraud_score      = 70.00
  fraud_decision   = REJECTED
  fraud_reasons    = ["new_counterparty"]
  fraud_checked_at = NOW()
  failed_at        = NOW()
  version          = 3


═══════════════════════════════════════════════════════════════
Step C2
Service:  nexus-saga-orchestrator:8095
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.replies")
          → checks replyType == "FraudRejectedReply"
          consumer group: saga-orchestrator-replies
DB:       nexus_saga (PostgreSQL)  +  OpenAI API (gpt-4o-mini)
═══════════════════════════════════════════════════════════════
transfer_saga_states — UPDATE
  current_step          = RELEASING_BALANCE
  failure_type          = FRAUD_REJECTED
  failure_explanation   = { AI-generated user-facing message in Spanish }
  compensation_attempts = 1
  version               = 2

outbox — INSERT
  event_type = ReleaseBalanceCommand
  topic      = saga.commands

  ↓ Debezium → saga.commands


═══════════════════════════════════════════════════════════════
Step C3
Service:  nexus-account-service:8085
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.commands")
          → checks commandType == "ReleaseBalanceCommand"
DB:       MongoDB (accounts collection)
═══════════════════════════════════════════════════════════════
accounts — UPDATE  source
  available_balance += 1500.00   (8500.00 → 10000.00)
  reserved_amount   -= 1500.00   (1500.00 → 0.00)

[PGVEC-W] nexus-account-service — SYNC (same thread, after release)
  table: transaction_embeddings  (nexus_accounts DB)
  TransactionIndexingService.indexOnRelease() fires
  → builds Document: { accountId, transactionId, amount,
                        event: BALANCE_RELEASED, timestamp }
  → embeds via OpenAI → INSERT into transaction_embeddings

  ↓ produces DIRECTLY to Kafka
    topic: saga.replies
    body:  { replyType: "BalanceReleasedReply", sagaId }


═══════════════════════════════════════════════════════════════
Step C4
Service:  nexus-saga-orchestrator:8095
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.replies")
          → checks replyType == "BalanceReleasedReply"
DB:       nexus_saga (PostgreSQL)
═══════════════════════════════════════════════════════════════
transfer_saga_states — UPDATE  (FINAL STATE)
  current_step   = COMPENSATION_COMPLETED
  funds_released = true
  completed_at   = NOW()
  version        = 3

outbox — INSERT
  event_type = SendTransactionFailureNotificationCommand
  topic      = saga.commands

  ↓ nexus-notification-service sends failure SMS/email
    → NotificationSentReply → saga remains COMPENSATION_COMPLETED


══════════════════════════════════════════════════════════════
HOW THE ORCHESTRATOR "COMES BACK" — the short answer
══════════════════════════════════════════════════════════════
The orchestrator never sleeps and never polls.
It has ONE listener always running:

  @KafkaListener(topics = "saga.replies",
                 groupId = "saga-orchestrator-replies")
  void consumeReply(String message) {
      switch (replyType) {
          case "BalanceReservedReply"   → handleBalanceReserved()
          case "FraudClearedReply"      → handleFraudCleared()
          case "FraudRejectedReply"     → handleFraudRejected()
          case "LedgerPostedReply"      → handleLedgerPosted()
          case "BalanceFinalizedReply"  → handleBalanceFinalized()
          case "BalanceReleasedReply"   → handleBalanceReleased()
          case "NotificationSentReply"  → handleNotificationSent()
      }
  }

Every participant service (account, fraud, ledger, notification)
drops its reply onto saga.replies when done.
That Kafka push wakes the orchestrator instantly.
No API call. No webhook. No polling. Pure event-driven.
```


---


# nexus-transaction-service
## POST /api/v1/transactions/payment

```
Trigger legend
──────────────────────────────────────────────────────────────
[HTTP]      client → API Gateway → service (synchronous REST)
[CDC]       Debezium reads outbox table WAL → publishes to Kafka
[KAFKA-L]   service has @KafkaListener always running on that topic
            message arrives → listener method fires
[KS]        Kafka Streams topology — stateful stream processor
            always running, reads from input topic, writes to
            output topic. Not a listener — it's a DSL pipeline.
[PGVEC-R]   pgvector READ  — semantic similarity search (SELECT)
[PGVEC-W]   pgvector WRITE — vector embedding indexed (INSERT)
──────────────────────────────────────────────────────────────

DIFFERENCE vs /transfer
──────────────────────────────────────────────────────────────
1. transactionType stored as PAYMENT (not INTERNAL_TRANSFER)
2. merchantName + merchantCategoryCode are populated
3. targetAccountId is OPTIONAL (can be null for merchant payments
   where no internal account is the target)
4. Fee = $0.00 — calculateFee() only charges 1.5% on
   EXTERNAL_TRANSFER; PAYMENT is exempt
5. Step 8a triggers a Kafka Streams topology (MerchantAggregation)
   that is NOT triggered by INTERNAL_TRANSFER
6. pgvector usage is IDENTICAL to INTERNAL_TRANSFER —
   same 3 tables, same operations, same steps
──────────────────────────────────────────────────────────────
Two participant topics:
  saga.commands — ALL commands land here, each service
                  filters by commandType + targetService field
  saga.replies  — ALL replies land here, saga orchestrator
                  filters by replyType field
─────────────────────────────────────────────────────────────

═══════════════════════════════════════════════════════════════
Step 1
Service:  nexus-api-gateway:8080 → nexus-transaction-service:8086
Trigger:  [HTTP] client POSTs /api/v1/transactions/payment
DB:       nexus_transactions (PostgreSQL)
═══════════════════════════════════════════════════════════════
transactions — INSERT
  transaction_id         = <new UUID>
  status                 = INITIATED
  saga_id                = <new UUID>
  idempotency_key        = <from request>
  source_account_id      = <UUID>
  target_account_id      = <UUID or NULL>          ← optional for payments
  amount                 = 500.00
  currency               = MXN
  transaction_type       = PAYMENT                 ← key difference
  fee_amount             = 0.0000                  ← no fee on PAYMENT
  merchant_name          = "Netflix MX"            ← populated
  merchant_category_code = "7995"                  ← populated
  channel                = API
  description            = "Monthly subscription"
  version                = 0
  initiated_at           = NOW()

outbox — INSERT
  event_type   = TransactionInitiated
  topic        = transactions.initiated
  processed_at = NULL
  payload includes transactionType = "PAYMENT"

  ↓ Debezium reads outbox WAL row → publishes to Kafka
    topic: transactions.initiated
    key:   transactionId

HTTP 202 returned to client immediately.

[PGVEC-W] nexus-audit-query-jvm — ASYNC
  table: audit_event_embeddings  (nexus_audit DB)
  AuditIndexingConsumer listens to transactions.initiated
  → embeds event → INSERT into audit_event_embeddings


═══════════════════════════════════════════════════════════════
Step 2
Service:  nexus-saga-orchestrator:8095
Trigger:  [KAFKA-L] @KafkaListener(topics="transactions.initiated")
          TransactionEventConsumer.consumeTransactionInitiated() fires
          → delegates to TransferSagaProcessor.handleTransactionInitiated()
          NOTE: same processor handles ALL transactionTypes
               (PAYMENT, INTERNAL_TRANSFER, EXTERNAL_TRANSFER…)
               there is no payment-specific saga class
DB:       nexus_saga (PostgreSQL)
═══════════════════════════════════════════════════════════════
transfer_saga_states — INSERT
  saga_id               = <UUID>
  transaction_id        = <UUID>
  transaction_type      = "PAYMENT"              ← stored on saga state
  merchant_name         = "Netflix MX"           ← stored on saga state
  current_step          = BALANCE_RESERVING
  funds_reserved        = false
  compensation_attempts = 0
  version               = 0
  started_at            = NOW()

saga_step_history — INSERT
  STARTED → BALANCE_RESERVING

saga_timeouts — INSERT
  timeout_type = BALANCE_RESERVATION
  fires_at     = NOW() + 30s
  is_cancelled = false

outbox — INSERT
  commandType   = "ReserveBalanceCommand"
  targetService = "nexus-account-service"
  topic         = saga.commands
  payload       = { sagaId, accountId: sourceAccountId, amount, currency }

  ↓ Debezium reads saga outbox WAL row → publishes to Kafka
    topic: saga.commands
    key:   sagaId


═══════════════════════════════════════════════════════════════
Step 3
Service:  nexus-account-service:8085
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.commands")
          → commandType == "ReserveBalanceCommand"
          → targetService == "nexus-account-service"
          → ignores all other commands on this topic
DB:       MongoDB (accounts collection)
═══════════════════════════════════════════════════════════════
accounts — UPDATE  (source account)
  available_balance -= 500.00
  reserved_amount   += 500.00

[PGVEC-W] nexus-account-service — SYNC (same thread, after reserve)
  table: transaction_embeddings  (nexus_accounts DB)
  TransactionIndexingService.indexOnReserve() fires
  → builds Document: { accountId, transactionId, amount,
                        currency, type: PAYMENT,
                        merchantName: "Netflix MX",
                        event: BALANCE_RESERVED, timestamp }
  → embeds via OpenAI → INSERT into transaction_embeddings

  ↓ produces DIRECTLY to Kafka (no outbox)
    topic: saga.replies
    key:   sagaId
    body:  { replyType: "BalanceReservedReply",
             sagaId, newAvailableBalance: <new balance> }


═══════════════════════════════════════════════════════════════
Step 4a
Service:  nexus-transaction-service:8086
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.replies")
          SagaReplyConsumer fires
          → replyType == "BalanceReservedReply"
          consumer group: transaction-service-saga-replies
DB:       nexus_transactions (PostgreSQL)
═══════════════════════════════════════════════════════════════
transactions — UPDATE  (saveAndFlush)
  status  = BALANCE_RESERVING   (was INITIATED)
  version = 1

transactions — UPDATE
  status              = BALANCE_RESERVED
  balance_reserved_at = NOW()
  version             = 2


═══════════════════════════════════════════════════════════════
Step 4b
Service:  nexus-saga-orchestrator:8095
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.replies")
          SagaReplyConsumer.consumeReply() fires
          → replyType == "BalanceReservedReply"
          consumer group: saga-orchestrator-replies
          ← SAME Kafka message as Step 4a, different consumer group
             both fire independently and in parallel
DB:       nexus_saga (PostgreSQL)
═══════════════════════════════════════════════════════════════
transfer_saga_states — UPDATE
  current_step          = BALANCE_RESERVED
  funds_reserved        = true
  new_available_balance = <new balance>
  version               = 1

saga_step_history — INSERT  BALANCE_RESERVING → BALANCE_RESERVED

saga_timeouts — UPDATE  BALANCE_RESERVATION → is_cancelled = true
saga_timeouts — INSERT  FRAUD_CHECK  fires_at = NOW() + 60s

outbox — INSERT
  commandType   = "CheckFraudCommand"
  targetService = "nexus-fraud-service"
  topic         = saga.commands
  payload       = { sagaId, transactionId, sourceAccountId,
                    targetAccountId, sourceUserId,
                    amount, currency,
                    transactionType: "PAYMENT",            ← passed to fraud AI
                    merchantName: "Netflix MX",            ← passed to fraud AI
                    merchantCategoryCode: "7995" }         ← passed to fraud AI

  ↓ Debezium reads saga outbox WAL row → publishes to Kafka
    topic: saga.commands
    key:   sagaId


═══════════════════════════════════════════════════════════════
Step 5
Service:  nexus-fraud-service:8087
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.commands")
          FraudCommandConsumer.consumeFraudCommand() fires
          → checks commandType == "CheckFraudCommand"
          → checks targetService == "nexus-fraud-service"
          → ignores ReserveBalanceCommand, PostLedgerCommand, etc.
DB:       nexus_fraud (PostgreSQL)  +  OpenAI API (gpt-4o-mini)
═══════════════════════════════════════════════════════════════
AI ReAct pipeline runs (~15–20s):

  Phase 1 — Planning:
    fraudPlanningClient → FraudAnalysisPlan
      MCC 7995 (gambling) is HIGH RISK → signals added to plan

  Phase 2 — Tool execution (sequential):
    velocity_check_tool       → transaction velocity for this user
    account_relationship_tool → known merchant relationship check

    [PGVEC-R] rag_policy_tool fires:
      table: fraud_policy_embeddings  (nexus_fraud DB)
      RAG pipeline:
        1. MultiQueryExpander generates 4 query variants
           — context includes merchantName + MCC 7995
        2. VectorStoreDocumentRetriever runs similarity search
           → retrieves top-15 policy documents ranked by cosine distance
           For PAYMENT with MCC 7995 additional policies surface:
             High-Risk Merchant Category Codes §6.1  ← MCC 7995 is flagged
             Sanctioned Entity Blacklist Policy §2.1
             + same 13 base policies as INTERNAL_TRANSFER
        3. ContextualQueryAugmenter injects documents into synthesis prompt
      Pure READ — no writes to fraud_policy_embeddings.

  Phase 3 — Synthesis:
    fraudSynthesisClient → FraudDecision { decision, score }
      (MCC 7995 raises score; result depends on other signals)

fraud_decisions — INSERT
  decision_outcome = APPROVE (or REJECT)
  risk_score       = <0–100>
  confidence_level = <float>

  ↓ fraud-service produces DIRECTLY to Kafka (no outbox)
    topic: saga.replies
    key:   sagaId
    body:  { replyType: "FraudClearedReply" OR "FraudRejectedReply",
             sagaId, transactionId, riskScore }

[PGVEC-W] nexus-audit-query-jvm — ASYNC
  table: audit_event_embeddings  (nexus_audit DB)
  AuditIndexingConsumer listens to fraud.result topic
  → embeds fraud result event → INSERT into audit_event_embeddings


═══════════════════════════════════════════════════════════════
Step 6
Service:  nexus-saga-orchestrator:8095
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.replies")
          SagaReplyConsumer.consumeReply() fires
          → checks replyType == "FraudClearedReply"
          TransferSagaProcessor.handleFraudCleared()
DB:       nexus_saga (PostgreSQL)
═══════════════════════════════════════════════════════════════
transfer_saga_states — UPDATE
  current_step   = LEDGER_POSTING
  fraud_score    = <score>
  fraud_decision = CLEARED
  version        = 2

saga_step_history — INSERT  FRAUD_CHECKING → FRAUD_CLEARED
saga_step_history — INSERT  FRAUD_CLEARED  → LEDGER_POSTING

saga_timeouts — UPDATE  FRAUD_CHECK → is_cancelled = true
saga_timeouts — INSERT  LEDGER_POST  fires_at = NOW() + 30s

outbox — INSERT
  commandType   = "PostLedgerCommand"
  targetService = "nexus-ledger-service"
  topic         = saga.commands
  payload       = { sagaId, transactionId, sourceAccountId,
                    targetAccountId, amount, currency,
                    postingType: "TRANSFER",             ← hardcoded even for PAYMENT
                    description: "Monthly subscription" }

  ↓ Debezium reads saga outbox WAL row → publishes to Kafka
    topic: saga.commands


═══════════════════════════════════════════════════════════════
Step 7
Service:  nexus-ledger-service:8088
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.commands")
          → checks commandType == "PostLedgerCommand"
          → checks targetService == "nexus-ledger-service"
DB:       nexus_ledger (PostgreSQL)
═══════════════════════════════════════════════════════════════
ledger_entries — INSERT × 2  (double-entry)
  DEBIT:  sourceAccountId  -500.00
  CREDIT: targetAccountId  +500.00
           (or merchant ledger account if targetAccountId is NULL)

  ↓ ledger-service produces DIRECTLY to Kafka (no outbox)
    topic: saga.replies
    body:  { replyType: "LedgerPostedReply",
             sagaId, postingId, debitEntryId, creditEntryId }

[PGVEC-W] nexus-audit-query-jvm — ASYNC
  table: audit_event_embeddings  (nexus_audit DB)
  AuditIndexingConsumer listens to ledger.posted topic
  → embeds ledger event → INSERT into audit_event_embeddings


═══════════════════════════════════════════════════════════════
Step 8a
Service:  nexus-transaction-service:8086
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.replies")
          → checks replyType == "LedgerPostedReply"
          consumer group: transaction-service-saga-replies
DB:       nexus_transactions (PostgreSQL)  +  Elasticsearch:9202
═══════════════════════════════════════════════════════════════
transactions — UPDATE  (saveAndFlush)
  status  = LEDGER_POSTING   (was BALANCE_RESERVED)
  version = 3

transactions — UPDATE  (net DB commit — COMPLETING skipped in DB)
  status           = COMPLETED
  ledger_entry_id  = <UUID>
  completed_at     = NOW()
  version          = 4

outbox — INSERT  topic = transactions.completed

  ↓ Debezium → transactions.completed
    MerchantAggregationTopology [KS] reads from this topic ──→ see Step 8c

Elasticsearch — async index
  index = transactions
  doc   = { transactionId, status: COMPLETED, transactionType: PAYMENT,
             merchantName: "Netflix MX", amount: 500.00, ... }

[PGVEC-W] nexus-audit-query-jvm — ASYNC
  table: audit_event_embeddings  (nexus_audit DB)
  AuditIndexingConsumer listens to transactions.completed
  → embeds completed event → INSERT into audit_event_embeddings


═══════════════════════════════════════════════════════════════
Step 8b
Service:  nexus-saga-orchestrator:8095
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.replies")
          → checks replyType == "LedgerPostedReply"
          consumer group: saga-orchestrator-replies
          ← SAME message as Step 8a, different consumer group
DB:       nexus_saga (PostgreSQL)
═══════════════════════════════════════════════════════════════
transfer_saga_states — UPDATE
  current_step      = BALANCE_FINALIZING
  ledger_posting_id = <UUID>
  debit_entry_id    = <UUID>
  credit_entry_id   = <UUID>
  version           = 3

saga_step_history — INSERT  LEDGER_POSTING → BALANCE_FINALIZING

saga_timeouts — UPDATE  LEDGER_POST → is_cancelled = true
saga_timeouts — INSERT  BALANCE_FINALIZE  fires_at = NOW() + 30s

outbox — INSERT
  commandType   = "FinalizeTransferCommand"
  targetService = "nexus-account-service"
  topic         = saga.commands
  payload       = { sagaId, transactionId, sourceAccountId,
                    targetAccountId, amount, currency, reservationId }

  ↓ Debezium → saga.commands


═══════════════════════════════════════════════════════════════
Step 8c  ← PAYMENT-EXCLUSIVE
Service:  nexus-transaction-service:8086 (Kafka Streams)
Trigger:  [KS] MerchantAggregationTopology
          reads from: transactions.completed
          filter: merchantName field must be present and non-blank
DB:       Kafka Streams state store (RocksDB, 24h retention)
═══════════════════════════════════════════════════════════════
  ⚠ KNOWN GAP: buildCompletedPayload() does NOT include merchantName
    in the transactions.completed outbox payload. The topology filter
    (v.path("merchantName").isMissingNode()) therefore drops this event.
    The merchant stats aggregation will NOT fire until merchantName
    is added to the completed payload.

  IF merchantName were in the payload, the topology would:
    → re-key stream by merchantName
    → upsert 1-hour tumbling window aggregate in "merchant-stats" store
      { merchantName, transactionCount++, totalVolume+=500.00,
        avgTransaction, computedAt }
    → publish to: transactions.merchant-stats

  No pgvector interaction in this step.


═══════════════════════════════════════════════════════════════
Step 9
Service:  nexus-account-service:8085
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.commands")
          → checks commandType == "FinalizeTransferCommand"
          → checks targetService == "nexus-account-service"
DB:       MongoDB (accounts collection)
═══════════════════════════════════════════════════════════════
accounts — UPDATE  source
  reserved_amount   -= 500.00   (reservation cleared)

accounts — UPDATE  target (if targetAccountId present)
  available_balance += 500.00

[PGVEC-W] nexus-account-service — SYNC (same thread, after finalize)
  table: transaction_embeddings  (nexus_accounts DB)
  TransactionIndexingService.indexOnFinalize() fires
  → builds Document: { accountId, transactionId, amount,
                        currency, type: PAYMENT,
                        merchantName: "Netflix MX",
                        event: BALANCE_FINALIZED, timestamp }
  → embeds via OpenAI → INSERT into transaction_embeddings

  ↓ produces DIRECTLY to Kafka
    topic: saga.replies
    body:  { replyType: "BalanceFinalizedReply", sagaId }


═══════════════════════════════════════════════════════════════
Step 10
Service:  nexus-saga-orchestrator:8095
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.replies")
          → checks replyType == "BalanceFinalizedReply"
DB:       nexus_saga (PostgreSQL)
═══════════════════════════════════════════════════════════════
transfer_saga_states — UPDATE
  current_step = NOTIFICATION_SENDING
  version      = 4

saga_timeouts — UPDATE  BALANCE_FINALIZE → is_cancelled = true
saga_timeouts — INSERT  NOTIFICATION  fires_at = NOW() + 5m

outbox — INSERT
  commandType   = "SendTransactionNotificationCommand"
  targetService = "nexus-notification-service"
  topic         = saga.commands
  payload       = { sagaId, transactionId, sourceUserId,
                    targetUserId, amount, currency }

  ↓ Debezium → saga.commands


═══════════════════════════════════════════════════════════════
Step 11
Service:  nexus-notification-service:8089
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.commands")
          → checks commandType == "SendTransactionNotificationCommand"
          → checks targetService == "nexus-notification-service"
DB:       nexus_notification (PostgreSQL)
═══════════════════════════════════════════════════════════════
notifications — INSERT
  status  = SENT
  sent_at = NOW()

  ↓ produces DIRECTLY to Kafka
    topic: saga.replies
    body:  { replyType: "NotificationSentReply", sagaId }


═══════════════════════════════════════════════════════════════
Step 12
Service:  nexus-saga-orchestrator:8095
Trigger:  [KAFKA-L] @KafkaListener(topics="saga.replies")
          → checks replyType == "NotificationSentReply"
DB:       nexus_saga (PostgreSQL)
═══════════════════════════════════════════════════════════════
transfer_saga_states — UPDATE  (FINAL STATE)
  current_step = COMPLETED
  completed_at = NOW()
  version      = 5

saga_step_history — INSERT  NOTIFICATION_SENDING → COMPLETED

outbox — INSERT
  event_type = SagaCompleted
  topic      = transactions.saga.completed


══════════════════════════════════════════════════════════════
PGVECTOR SUMMARY — PAYMENT
══════════════════════════════════════════════════════════════

  Step  │ Table                   │ DB              │ Op    │ Trigger
  ──────┼─────────────────────────┼─────────────────┼───────┼────────────────
  1     │ audit_event_embeddings  │ nexus_audit     │ WRITE │ transactions.initiated Kafka event
  3     │ transaction_embeddings  │ nexus_accounts  │ WRITE │ after balance reserve (sync) — includes merchantName
  5     │ fraud_policy_embeddings │ nexus_fraud     │ READ  │ rag_policy_tool — MCC 7995 surfaces §6.1 additionally
  5     │ audit_event_embeddings  │ nexus_audit     │ WRITE │ fraud.result Kafka event
  7     │ audit_event_embeddings  │ nexus_audit     │ WRITE │ ledger.posted Kafka event
  8a    │ audit_event_embeddings  │ nexus_audit     │ WRITE │ transactions.completed Kafka event
  9     │ transaction_embeddings  │ nexus_accounts  │ WRITE │ after balance finalize (sync) — includes merchantName

  Total distinct tables touched: 3  (identical to INTERNAL_TRANSFER)
    fraud_policy_embeddings → 1 READ  (MCC context shifts which policies surface)
    transaction_embeddings  → 2 WRITEs (merchant metadata included in document)
    audit_event_embeddings  → 4 WRITEs (async, best-effort)


══════════════════════════════════════════════════════════════
COMPENSATION PATH — fraud returns REJECT (after Step 5)
══════════════════════════════════════════════════════════════
Same compensation path as INTERNAL_TRANSFER.
TransferSagaProcessor.handleFraudRejected() has no payment-specific
branching. Balance is released via ReleaseBalanceCommand → account-service,
AI generates failure explanation in Spanish, saga ends COMPENSATION_COMPLETED.

═══════════════════════════════════════════════════════════════
Step C1
Service:  nexus-transaction-service:8086
Trigger:  [KAFKA-L] replyType == "FraudRejectedReply"
          consumer group: transaction-service-saga-replies
DB:       nexus_transactions (PostgreSQL)
═══════════════════════════════════════════════════════════════
transactions — UPDATE
  status           = FRAUD_REJECTED → FAILED
  fraud_score      = <score>
  fraud_decision   = REJECTED
  failed_at        = NOW()
  version          = 3


═══════════════════════════════════════════════════════════════
Step C2
Service:  nexus-saga-orchestrator:8095
Trigger:  [KAFKA-L] replyType == "FraudRejectedReply"
          consumer group: saga-orchestrator-replies
DB:       nexus_saga (PostgreSQL)  +  OpenAI API (gpt-4o-mini)
═══════════════════════════════════════════════════════════════
transfer_saga_states — UPDATE
  current_step          = RELEASING_BALANCE
  failure_type          = FRAUD_REJECTED
  failure_explanation   = { AI-generated user-facing message in Spanish }
  compensation_attempts = 1
  version               = 2

outbox — INSERT
  commandType   = "ReleaseBalanceCommand"
  targetService = "nexus-account-service"
  topic         = saga.commands

  ↓ Debezium → saga.commands


═══════════════════════════════════════════════════════════════
Step C3
Service:  nexus-account-service:8085
Trigger:  [KAFKA-L] commandType == "ReleaseBalanceCommand"
          → targetService == "nexus-account-service"
DB:       MongoDB (accounts collection)
═══════════════════════════════════════════════════════════════
accounts — UPDATE  source
  available_balance += 500.00
  reserved_amount   -= 500.00

[PGVEC-W] nexus-account-service — SYNC (same thread, after release)
  table: transaction_embeddings  (nexus_accounts DB)
  TransactionIndexingService.indexOnRelease() fires
  → builds Document: { accountId, transactionId, amount,
                        currency, type: PAYMENT,
                        event: BALANCE_RELEASED, timestamp }
  → embeds via OpenAI → INSERT into transaction_embeddings

  ↓ produces DIRECTLY to Kafka
    topic: saga.replies
    body:  { replyType: "BalanceReleasedReply", sagaId }


═══════════════════════════════════════════════════════════════
Step C4
Service:  nexus-saga-orchestrator:8095
Trigger:  [KAFKA-L] replyType == "BalanceReleasedReply"
DB:       nexus_saga (PostgreSQL)
═══════════════════════════════════════════════════════════════
transfer_saga_states — UPDATE  (FINAL STATE)
  current_step   = COMPENSATION_COMPLETED
  funds_released = true
  completed_at   = NOW()
  version        = 3

outbox — INSERT
  commandType   = "SendTransactionFailureNotificationCommand"
  targetService = "nexus-notification-service"
  topic         = saga.commands

outbox — INSERT
  event_type = SagaFailed
  topic      = transactions.saga.failed

  ↓ nexus-notification-service sends failure SMS/email
    → NotificationSentReply → saga remains COMPENSATION_COMPLETED


══════════════════════════════════════════════════════════════
REQUEST BODY FOR TESTING
══════════════════════════════════════════════════════════════
POST {{baseUrl}}/api/v1/transactions/payment
X-User-Id: 679a186b-a5fa-4cca-8d41-658ff7be72d7

{
  "idempotencyKey": "pay-idempotency-key-20260626-001",
  "sourceAccountId": "5d2b5e8b-0386-496a-884a-70b0058e8230",
  "targetAccountId": "2bb657b1-d67d-41d7-b631-9a0e468e9a89",
  "targetAccountNumber": "2633-5535-3147-7193",
  "targetUserId": "679a186b-a5fa-4cca-8d41-658ff7be72d7",
  "amount": 500.00,
  "currency": "MXN",
  "transactionType": "PAYMENT",
  "channel": "API",
  "description": "Monthly subscription",
  "merchantName": "Netflix MX",
  "merchantCategoryCode": "7995",
  "referenceNumber": "PAY-2026-001"
}


══════════════════════════════════════════════════════════════
WHAT IS DIFFERENT FROM /transfer — QUICK REFERENCE
══════════════════════════════════════════════════════════════

  /transfer                      /payment
  ───────────────────────────────────────────────────────────
  transactionType                transactionType
    INTERNAL_TRANSFER              PAYMENT
  merchantName null              merchantName "Netflix MX"
  merchantCategoryCode null      merchantCategoryCode "7995"
  targetAccountId required       targetAccountId optional
  fee 0.00 (internal)            fee 0.00 (payment also exempt)
  no Step 8c                     Step 8c fires (currently broken
                                 — merchantName missing from
                                   completed payload)
  postingType "TRANSFER"         postingType "TRANSFER"
                                 (hardcoded — same value)

  pgvector — identical tables    pgvector — identical tables
  transaction_embeddings doc     transaction_embeddings doc
    has no merchantName            has merchantName + MCC
  fraud RAG: base 15 policies    fraud RAG: base 15 policies
                                   + MCC 7995 §6.1 surfaces higher
```
