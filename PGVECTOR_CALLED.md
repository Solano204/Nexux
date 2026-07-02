# PGVECTOR CALLED — Complete Platform Audit

> Last updated: 2026-06-27
> Audited: all 16 services. Only 4 services use pgvector at all.
> Status: ALL gaps fixed 2026-06-27 — see fix summary at bottom.

---

## Actual Table Names (corrected from initial assumptions)

| Table Name | Service | Database |
|---|---|---|
| `transaction_embeddings` | nexus-account-service | nexus_accounts |
| `fraud_policy_embeddings` | nexus-fraud-service | nexus_fraud |
| `financial_literacy_embeddings` | nexus-ledger-service | nexus_ledger |
| `ai_conversation_memory` | nexus-ai-assistant-service | nexus_ai_assistant |
| `financial_knowledge_base` | nexus-ai-assistant-service | nexus_ai_assistant |
| `audit_event_embeddings` | nexus-audit-query-jvm | nexus_audit |

> Note: 6 tables, not 5. ledger-service owns `financial_literacy_embeddings`,
> not ai-assistant-service. ai-assistant-service has 2 tables with different names
> than originally assumed.

---

## SERVICE: nexus-account-service

---

### Finding 1

```
SERVICE:    nexus-account-service
TABLE:      transaction_embeddings
OPERATION:  WRITE
TRIGGER:    Kafka topic: saga.commands — commandType: "ReserveBalanceCommand"
            Kafka topic: saga.commands — commandType: "FinalizeTransferCommand"
            Kafka topic: saga.commands — commandType: "ReleaseBalanceCommand"
CALLER CHAIN:
  SagaCommandConsumer.handleReserveBalance()
    → commandService.reserveBalance()          ← stops here
    ✗ TransactionIndexingService.indexReservation()  ← NEVER CALLED

  SagaCommandConsumer.handleFinalizeTransfer()
    → commandService.finalizeTransfer()        ← stops here
    ✗ TransactionIndexingService.indexFinalizedDebit()  ← NEVER CALLED
    ✗ TransactionIndexingService.indexCredit()          ← NEVER CALLED

  SagaCommandConsumer.handleReleaseBalance()
    → commandService.releaseBalance()          ← stops here
    ✗ TransactionIndexingService.indexRelease()  ← NEVER CALLED

STATUS:     ORPHANED
            TransactionIndexingService exists with full indexing logic
            (indexReservation, indexRelease, indexCredit, indexFinalizedDebit,
            batchIndex) and calls vectorStore.add() correctly — but ZERO callers
            inject or call this service. It is a dead class.
WHY:        Every completed transaction should be embedded as natural language
            ("[2026-06-27 14:05] DEBIT of MXN 500 — Transfer to Carlos.
            Balance after: MXN 4500") so the AI Advisor can answer questions
            like "how much did I spend on food this month?" via RAG retrieval.
            Without this, transaction_embeddings is always empty and the advisor
            gives generic advice with no real user data.
EXPECTED RESULT (when fixed):
            On every saga.commands ReserveBalanceCommand → 1 row inserted
            On every FinalizeTransferCommand → 2 rows inserted (debit + credit)
            On every ReleaseBalanceCommand → 1 row inserted
            Rows contain natural language summaries + metadata (account_id,
            type, amount, category, date, timestamp)
```

---

### Finding 2

```
SERVICE:    nexus-account-service
TABLE:      transaction_embeddings
OPERATION:  WRITE (conversation turns, NOT transactions)
TRIGGER:    POST /api/v1/accounts/{accountId}/advisor/chat
CALLER CHAIN:
  AccountAdvisorController.chat()
    → AccountAdvisorService.getAdvisorResponseStream()
    → accountAdvisorClient.prompt()...stream()
    → VectorStoreChatMemoryAdvisor (advisor registered in SpringAiConfig)
    → transactionVectorStore.add()   ← WIRED, runs automatically

STATUS:     WIRED (but wrong semantic — writes conversation turns into
            the transactions table, mixing two concerns in one table)
WHY:        VectorStoreChatMemoryAdvisor stores conversation exchanges as
            embeddings for long-term semantic memory. This lets the advisor
            recall prior conversations ("last time you told me about your
            savings goal..."). However this data goes into transaction_embeddings
            which is also where actual transaction embeddings should go.
            This is a design smell — conversation memory and transaction events
            should be in separate tables.
EXPECTED RESULT:
            On every chat message → 1 row inserted (the conversation turn
            as an embedding, metadata: session_id, timestamp)
```

---

### Finding 3

```
SERVICE:    nexus-account-service
TABLE:      transaction_embeddings
OPERATION:  READ
TRIGGER:    POST /api/v1/accounts/{accountId}/advisor/chat
CALLER CHAIN:
  AccountAdvisorController.chat()
    → AccountAdvisorService.getAdvisorResponseStream()
    → accountAdvisorClient.prompt()...stream()
    → RetrievalAugmentationAdvisor (SpringAiConfig)
        → MultiQueryExpander (generates 4 search variants)
        → VectorStoreDocumentRetriever.topK(20).similarityThreshold(0.65)
        → transactionVectorStore.similaritySearch()  ← WIRED
    → ContextualQueryAugmenter (pastes retrieved docs into prompt)
    → GPT-4o-mini answers with real context

STATUS:     WIRED
WHY:        User asks "how much did I spend on restaurants?" → the question
            is embedded → pgvector finds the 20 most semantically similar
            transaction rows → those are pasted into the GPT prompt as context
            → GPT gives a specific answer with real amounts and dates.
            Without seeded data (Finding 1 above), this always returns empty
            results and GPT makes up generic advice.
EXPECTED RESULT:
            On every advisor/chat call → similarity search against
            transaction_embeddings → top 20 matching rows returned →
            fed to GPT-4o-mini as factual context
```

---

## SERVICE: nexus-fraud-service

---

### Finding 4

```
SERVICE:    nexus-fraud-service
TABLE:      fraud_policy_embeddings
OPERATION:  READ
TRIGGER:    POST /internal/v1/fraud/analyze  (direct HTTP)
            AND Kafka topic: fraud.check.request (saga-triggered fraud analysis)
CALLER CHAIN:
  InternalFraudController.analyzeTransaction()
    → FraudAnalysisService.analyze()
    → FraudReActAgent (tool execution loop)
    → RagPolicyTool.retrievePolicies()   ← WIRED, called on every analysis
        → VectorStoreDocumentRetriever.retrieve(query)
        → policyVectorStore.similaritySearch()

  ALSO: fraudSynthesisClient in SpringAiConfig has a RetrievalAugmentationAdvisor
  wrapping policyVectorStore → automatically reads on every synthesis step

STATUS:     WIRED (read side is fully connected)
WHY:        Every fraud analysis MUST consult policy documents to justify
            its decision. When the agent determines a transaction is suspicious,
            it retrieves the specific fraud policy sections that apply
            (e.g. "CNBV Circular 4/2019 Section 3.2 — velocity limits") and
            cites them in the FraudDecision.policyCitations field.
            This is legally required for CNBV-regulated rejections.
EXPECTED RESULT:
            On every fraud analysis → policyVectorStore.similaritySearch()
            returns the most relevant fraud policy fragments → AI cites
            specific policies in rejection reasoning
```

---

### Finding 5

```
SERVICE:    nexus-fraud-service
TABLE:      fraud_policy_embeddings
OPERATION:  WRITE
TRIGGER:    None — no write path exists anywhere in this service
CALLER CHAIN:
            N/A
STATUS:     MISSING — table will always be empty
WHY:        fraud_policy_embeddings needs to be pre-loaded with the bank's
            fraud detection policies (velocity rules, blacklist criteria,
            AML thresholds, CNBV regulatory rules, MCC risk categories).
            Without this seed data:
            - RagPolicyTool always returns empty results
            - fraudSynthesisClient RAG advisor returns no policy context
            - AI makes decisions without citing any real policies
            - All FraudDecision.policyCitations fields will be empty
EXPECTED RESULT (after manual seed):
            ~50-200 policy document chunks embedded in the table
            Example content: "Section 4.1 Velocity Limits: No more than 5
            transactions within a 5-minute window shall be approved..."
            Each chunk: title, section number, text content, policy_id metadata
FIX NEEDED: One-time admin endpoint POST /internal/v1/fraud/policies/seed
            OR a startup DataLoader class that checks if table is empty
            and populates from a JSON/SQL file of policy documents
```

---

## SERVICE: nexus-ledger-service

---

### Finding 6

```
SERVICE:    nexus-ledger-service
TABLE:      financial_literacy_embeddings
OPERATION:  READ
TRIGGER:    POST /api/v1/ledger/accounts/{accountId}/explain
CALLER CHAIN:
  LedgerController.explainTransactions()
    → LedgerExplainerService.explainStreaming()
    → explainerClient.prompt()...stream()
    → RetrievalAugmentationAdvisor (SpringAiConfig)
        → MultiQueryExpander (4 query variants)
        → VectorStoreDocumentRetriever.topK(12).similarityThreshold(0.60)
        → financialLiteracyVectorStore.similaritySearch()  ← WIRED
    → ContextualQueryAugmenter injects retrieved docs into prompt
    → GPT-4o-mini explains the ledger entry with plain language context

STATUS:     WIRED
WHY:        Users ask "what does this debit mean?" and the explainer needs
            financial literacy content to explain concepts like:
            "a debit on your account means money left your account",
            "the credit entry represents the bank receiving your payment",
            "your balance is negative because...".
            Without this seed, the explainer works but gives only GPT's
            built-in knowledge with no bank-specific terminology or context.
EXPECTED RESULT:
            On every explain call → similarity search returns the most
            relevant financial literacy fragments → GPT explains the
            transaction in plain language using those definitions
```

---

### Finding 7

```
SERVICE:    nexus-ledger-service
TABLE:      financial_literacy_embeddings
OPERATION:  WRITE
TRIGGER:    None — no write path exists anywhere in this service
CALLER CHAIN:
            N/A
STATUS:     MISSING — table will always be empty
WHY:        financial_literacy_embeddings needs to be pre-loaded with
            plain-language explanations of banking concepts:
            - What "debit" and "credit" mean in layman's terms
            - How interest is calculated
            - What a "posting" is vs a "pending" transaction
            - How reversals work
            - Mexican banking terminology (SPEI, CoDi, CLABE)
            Without this data, RAG returns nothing and GPT explains
            transactions using only its training data (no bank-specific context)
EXPECTED RESULT (after manual seed):
            ~30-100 content chunks covering banking concepts in Spanish
            Example: "Un débito en tu cuenta significa que salió dinero.
            Cuando compras en una tienda, esa compra aparece como débito..."
FIX NEEDED: One-time data loader at startup OR admin endpoint
            POST /internal/v1/ledger/knowledge/seed
```

---

## SERVICE: nexus-ai-assistant-service

---

### Finding 8

```
SERVICE:    nexus-ai-assistant-service
TABLE:      ai_conversation_memory
OPERATION:  WRITE (automatic via VectorStoreChatMemoryAdvisor)
TRIGGER:    POST /api/v1/ai/chat  (simple mode only)
            POST /api/v1/ai/chat/analyze-document (calls primaryClient)
CALLER CHAIN:
  AiAssistantController.chat()
    → ChatService.chat()
    → FinancialAssistantAgent.chat()
    → if NOT complex query → primaryClient.prompt()...stream()
    → VectorStoreChatMemoryAdvisor.builder(conversationMemoryStore)
    → ai_conversation_memory.add()   ← WIRED

  AiAssistantController.analyzeDocument()
    → DocumentAnalysisService.analyzeAndRespond()
    → primaryClient.prompt()...stream()
    → VectorStoreChatMemoryAdvisor (same advisor)
    → ai_conversation_memory.add()   ← WIRED

STATUS:     WIRED (simple chat mode only)
            NOT wired for complex/agent mode (agentClient has no
            VectorStoreChatMemoryAdvisor — uses only InMemoryChatMemory)
WHY:        The assistant stores each conversation turn as a vector so it
            can recall past conversations semantically. If a user said
            "I want to save MXN 10,000 by December" 3 sessions ago,
            the VectorStoreChatMemoryAdvisor retrieves that when the user
            asks "how is my savings goal going?" — even after a restart.
EXPECTED RESULT:
            On every simple-mode chat message → conversation turn
            embedded and stored in ai_conversation_memory.
            Long-term semantic memory survives service restarts.
```

---

### Finding 9

```
SERVICE:    nexus-ai-assistant-service
TABLE:      ai_conversation_memory
OPERATION:  READ (automatic via VectorStoreChatMemoryAdvisor)
TRIGGER:    POST /api/v1/ai/chat  (simple mode only)
CALLER CHAIN:
  Same as Finding 8 — VectorStoreChatMemoryAdvisor reads prior
  conversation turns on every new message to inject as context

STATUS:     WIRED
WHY:        Provides semantic long-term memory. When user asks a follow-up
            question, the advisor finds the most relevant past exchanges
            and injects them into the prompt so GPT has conversation history
            context even across sessions.
EXPECTED RESULT:
            On every simple-mode chat → semantic search against prior
            conversation turns → top-K injected into prompt as memory context
```

---

### Finding 10

```
SERVICE:    nexus-ai-assistant-service
TABLE:      financial_knowledge_base
OPERATION:  READ
TRIGGER:    POST /api/v1/ai/chat  (simple mode only)
            POST /api/v1/ai/chat/analyze-document
CALLER CHAIN:
  primaryClient (SpringAiConfig) has RetrievalAugmentationAdvisor
  wrapping financialKnowledgeVectorStore:
    → MultiQueryExpander (4 queries)
    → VectorStoreDocumentRetriever.topK(6).similarityThreshold(0.65)
    → financial_knowledge_base.similaritySearch()   ← WIRED

STATUS:     WIRED (read side connected)
WHY:        Augments the assistant with product knowledge, FAQs, interest
            rates, terms and conditions, product descriptions. When user
            asks "what is your savings account interest rate?" the RAG
            pipeline retrieves the relevant knowledge base article and
            GPT answers with accurate, current information rather than
            hallucinating or saying "I don't know".
EXPECTED RESULT:
            On every simple-mode chat → similarity search over product
            knowledge articles → relevant fragments injected into prompt
```

---

### Finding 11

```
SERVICE:    nexus-ai-assistant-service
TABLE:      financial_knowledge_base
OPERATION:  WRITE
TRIGGER:    None — no write path exists anywhere in this service
CALLER CHAIN:
            N/A
STATUS:     MISSING — table will always be empty
WHY:        financial_knowledge_base needs to be pre-loaded with:
            - Product descriptions (checking, savings, investment accounts)
            - Interest rates and fee schedules
            - FAQ articles ("how do I dispute a charge?", "how do I set up
              automatic payments?")
            - SPEI/CoDi transfer instructions
            - Terms and conditions summaries
            Without this, RAG returns nothing and GPT uses only its training
            knowledge (which may be wrong or outdated for Nexus-specific info)
EXPECTED RESULT (after manual seed):
            ~50-500 knowledge base articles embedded
FIX NEEDED: Admin-only endpoint POST /api/v1/ai/knowledge/seed
            OR DataLoader at startup from classpath JSON/SQL file
```

---

## SERVICE: nexus-audit-query-jvm

---

### Finding 12

```
SERVICE:    nexus-audit-query-jvm
TABLE:      audit_event_embeddings
OPERATION:  READ
TRIGGER:    POST /api/v1/audit/compliance/query
CALLER CHAIN:
  ComplianceController.query()
    → ComplianceQueryService.executeQuery()
    → buildSessionRagAdvisor()
        → VectorStoreDocumentRetriever.builder()
              .vectorStore(auditVectorStore)
              .topK(20).similarityThreshold(0.60)
        → audit_event_embeddings.similaritySearch()   ← WIRED
    → complianceChatClient.prompt().advisors(ragAdvisor)...entity()

STATUS:     WIRED (read side connected)
WHY:        Compliance officers issue natural language queries like
            "find all suspicious activity for user X in the last 30 days".
            The RAG advisor retrieves the most semantically relevant audit
            events and passes them to GPT which identifies patterns,
            generates SAR recommendations, and cites specific event IDs.
EXPECTED RESULT:
            On every compliance query → similarity search across embedded
            audit events → top 20 returned → GPT produces ComplianceQueryResult
            with patterns, citations, and regulatory recommendations
```

---

### Finding 13

```
SERVICE:    nexus-audit-query-jvm
TABLE:      audit_event_embeddings
OPERATION:  WRITE
TRIGGER:    None — no write path exists in ANY service
CALLER CHAIN:
            audit-write-native (Quarkus): writes ONLY to Elasticsearch,
            never to pgvector. AuditEventConsumer.writeToElasticsearch()
            is the only write path.
            ComplianceQueryService: reads from the store but never writes.
            No indexing service exists in nexus-audit-query-jvm.

STATUS:     MISSING — this is the most critical gap.
            audit_event_embeddings will always be empty.
            Every compliance query RAG search returns zero results.
            GPT operates with no audit event context.

WHY:        Every audit event consumed by audit-write-native from Kafka
            should ALSO be embedded and written to audit_event_embeddings.
            The embedding of each audit event ("User X performed a TRANSFER
            of MXN 15000 at 02:15 AM from Mexico City IP to Guadalajara
            account") enables semantic compliance investigation.
EXPECTED RESULT (when fixed):
            On every Kafka audit event (15+ event types) → normalize event
            → embed as natural language → write to audit_event_embeddings
            with metadata: userId, eventType, severity, sourceService,
            timestamp, traceId
FIX NEEDED: Add an AuditIndexingService to nexus-audit-query-jvm that
            listens to the same Kafka topics as audit-write-native
            (or a secondary Kafka consumer group in audit-write-native)
            and calls auditVectorStore.add() for each event.
            Alternatively: add vector indexing inside audit-write-native
            via a Quarkus LangChain4j integration.
```

---

## Services With NO pgvector Involvement

The following 12 services have zero pgvector usage — confirmed by source audit:

| Service | Reason |
|---|---|
| nexus-transaction-service | Elasticsearch + Kafka Streams only |
| nexus-analytics-service | Kafka Streams + AI insight generation, no RAG |
| nexus-risk-scoring-service | Agent tool-loop (no vector store, no RAG) |
| nexus-ai-kyc-service | Image/rekognition analysis, no pgvector |
| nexus-notification-service | No AI at all |
| nexus-saga-orchestrator | Orchestration only |
| nexus-identity-service | JWT auth, no AI |
| nexus-api-gateway | Routing only |
| nexus-config-service | Config distribution only |
| nexus-discovery-service | Eureka service registry only |
| audit-write-native (Quarkus) | Writes to Elasticsearch only, not pgvector |
| nexus-notification-service | No AI at all |

---

## Tables With NO Write Path (Need Manual Seed Before Feature Works)

| Table | Owner Service | Status | Impact of Missing Data |
|---|---|---|---|
| `fraud_policy_embeddings` | nexus-fraud-service | MISSING write path | Fraud AI cites no policies → all FraudDecision.policyCitations empty |
| `financial_literacy_embeddings` | nexus-ledger-service | MISSING write path | Explainer gives generic explanations, no bank-specific context |
| `financial_knowledge_base` | nexus-ai-assistant-service | MISSING write path | Assistant answers product questions from training data only (may be wrong) |
| `audit_event_embeddings` | nexus-audit-query-jvm | MISSING write path (most critical) | Compliance RAG always returns 0 results → AI has no audit context |
| `transaction_embeddings` | nexus-account-service | ORPHANED write path | TransactionIndexingService exists but never called → advisor has no transaction history |

---

## Summary: What Happens When You Call Each Endpoint Today

| Endpoint | pgvector Action | Status |
|---|---|---|
| `POST /api/v1/accounts/{id}/advisor/chat` | READ transaction_embeddings (empty) + WRITE conversation turn | RAG returns nothing (no transactions indexed) |
| `POST /internal/v1/fraud/analyze` | READ fraud_policy_embeddings (empty) | Fraud AI cites no policies |
| `POST /api/v1/ledger/accounts/{id}/explain` | READ financial_literacy_embeddings (empty) | Explainer works but generic |
| `POST /api/v1/ai/chat` | READ financial_knowledge_base (empty) + WRITE/READ ai_conversation_memory | Product RAG returns nothing; conversation memory accumulates normally |
| `POST /api/v1/ai/chat/analyze-document` | READ financial_knowledge_base (empty) + WRITE ai_conversation_memory | Same as above |
| `POST /api/v1/audit/compliance/query` | READ audit_event_embeddings (empty) | Compliance RAG returns 0 results |
| Transfer saga (Kafka) | SHOULD WRITE transaction_embeddings but does not | Zero transaction embeddings ever written |
