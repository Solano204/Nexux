# ACCOUNT ADVISOR CHAT Flow — Docker + AWS

## Endpoint
```
POST /api/v1/accounts/{accountId}/advisor/chat
```
Authenticated. Returns SSE stream (text/event-stream).

## What it does
Account-scoped AI financial advisor. Unlike `/ai/chat` (general assistant),
this advisor is pre-loaded with the user's actual account analytics from MongoDB
and their transaction history from pgvector RAG. Specialized for personal finance advice.

## Entry Point
```
App → POST /api/v1/accounts/{accountId}/advisor/chat → localhost:8080
Headers:
  Authorization: Bearer <jwt>
  Accept: text/event-stream
Body: {
  "message": "¿Cómo puedo ahorrar más este mes?",
  "sessionId": "optional-session-id"
}
```

---

## Full Flow

```
App
 │
 │ POST /api/v1/accounts/{accountId}/advisor/chat
 ▼
┌────────────────────────────────────────┐
│  nexus-api-gateway :8080               │
│  JWT validate → X-User-Id header       │
│  Route → nexus-account-service :8085   │
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│  nexus-account-service :8085           │
│  AccountAdvisorController.chat()       │
│                                        │
│  userId = X-User-Id header             │
│  sessionId = body.sessionId            │
│     OR auto: "advisor-{acctId}-{user}" │
│                                        │
│  advisorService.getAdvisorResponseStream│
│    (accountId, userId, message, session)│
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│  AccountAdvisorService                 │
│                                        │
│  ── Pre-load analytics context ──      │
│  analyticsRepository                   │
│    .findByAccountId(accountId)         │
│  → MongoDB AccountAnalyticsDocument   │
│                                        │
│  Builds analyticsContext string:       │
│  "Monthly spending: MXN 8,500          │
│   Monthly income: MXN 15,000           │
│   Transaction count: 23                │
│   Spending by category:                │
│     FOOD: MXN 2,100                    │
│     TRANSPORT: MXN 800                 │
│     ENTERTAINMENT: MXN 1,500..."       │
│                                        │
│  Enriches message:                     │
│  originalMessage +                     │
│  "\n\n[Current Account Context]\n"     │
│  + analyticsContext                    │
│                                        │
│  ── accountAdvisorClient stream ──     │
│                                        │
│  .prompt()                             │
│  .user(enrichedMessage)                │
│  .advisors(a ->                        │
│    a.param(                            │
│      "chat_memory_conversation_id",    │
│      sessionId))                       │
│  .stream()                             │
│  .content()                            │
└──────────────────┬─────────────────────┘
                   │ SSE stream
                   ▼
App receives tokens:
data: "Basándome en tus datos, gastas"
data: " MXN 1,500 en entretenimiento"
data: " este mes, un 18% de tus ingresos."
data: " Para ahorrar más, considera..."
...
data: [DONE]
```

---

## What's inside accountAdvisorClient

Configured in `SpringAiConfig.java` (nexus-account-service):

```
ChatClient (accountAdvisorClient)
  ├── Model: OpenAI (from OPENAI_API_KEY)
  ├── SystemPrompt: "You are a personal financial advisor for NEXUS.
  │                  Give specific advice based on the user's actual
  │                  spending patterns. Be concrete with numbers."
  ├── Advisors:
  │   ├── QuestionAnswerAdvisor (pgvector RAG)
  │   │   Vector store: user's indexed transaction history
  │   │   → "What transactions is this user making?"
  │   │   → semantic search over past spending
  │   │
  │   └── MessageChatMemoryAdvisor
  │       key: chat_memory_conversation_id
  │       → conversation window for follow-up questions
  └── No tools (advisor does not execute transactions)
```

---

## Data sources for the advisor

| Source | What it provides | When fetched |
|---|---|---|
| MongoDB `AccountAnalyticsDocument` | Pre-aggregated spend by category, totals | Before every request (sync) |
| pgvector `transaction_history` | Semantic search over transaction descriptions | During RAG retrieval (advisor) |
| MessageChatMemoryAdvisor | Previous turns in this session | Per request |

---

## Difference vs /ai/chat

| Feature | `/ai/chat` | `/accounts/{id}/advisor/chat` |
|---|---|---|
| Scope | Platform-wide | Single account |
| Analytics | Not included | MongoDB analytics pre-loaded |
| Tools | 6 (can transfer, check fraud, etc.) | None (advice only) |
| Mode | Simple / Agent (ReAct) | Always streaming advisor |
| RAG | Financial literacy KB | User's own transaction history |
| Fallback | Ollama local model | None (fails if OpenAI down) |
| Service | nexus-ai-assistant-service :8090 | nexus-account-service :8085 |

---

## Example conversation

```
Turn 1: "¿En qué gasto más dinero?"
  → Advisor: "Tu mayor gasto es entretenimiento: $1,500 en junio
               (18% de tus ingresos). También destacan $2,100 en comida."

Turn 2: "¿Cómo puedo reducir eso?"
  → Advisor knows context from turn 1
  → "Para reducir entretenimiento, considera limitar a $1,000 mensual.
     Eso te ahorraría $500/mes, o $6,000 al año."

Turn 3: "¿Y si también reduzco comida?"
  → Advisor compounds advice: both categories + total savings estimate
```
