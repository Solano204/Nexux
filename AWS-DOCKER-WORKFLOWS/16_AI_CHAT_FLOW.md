# AI CHAT Flow — Docker + AWS

## Endpoint
```
POST /api/v1/ai/chat
```
Authenticated. Returns SSE stream (text/event-stream).

## What it does
General-purpose financial AI assistant. Handles simple questions with standard
streaming, and complex multi-step requests (involving multiple tool calls or
conditional logic) with a Plan-then-Act ReAct agent loop (max 8 steps).

## Entry Point
```
App → POST /api/v1/ai/chat → localhost:8080
Headers:
  Authorization: Bearer <jwt>
  Accept: text/event-stream
Body: {
  "message": "What's my balance?",
  "sessionId": "optional-session-uuid"
}
```

---

## Full Flow

```
App
 │
 │ POST /api/v1/ai/chat
 │ {"message": "...", "sessionId": "..."}
 ▼
┌────────────────────────────────────────┐
│  nexus-api-gateway :8080               │
│  JWT validate → X-User-Id header       │
│  Route → nexus-ai-assistant-service    │
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│  nexus-ai-assistant-service :8090      │
│  AiAssistantController.chat()          │
│                                        │
│  userId = X-User-Id header             │
│  sessionId = body or auto-generate UUID│
│  conversationId = userId:sessionId     │
│                                        │
│  ChatService.chat(message, userId,     │
│                   sessionId)           │
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│  FinancialAssistantAgent.chat()        │
│                                        │
│  isComplexQuery(message)?              │
│  Detects: "transfer only if...",       │
│  "compare", "and also then",           │
│  "for each account"                    │
│                                        │
│  ┌──── SIMPLE ─────────────────────┐   │
│  │ primaryClient (OpenAI GPT-4o)   │   │
│  │ .prompt().user(message)         │   │
│  │ .advisors(conversationId)       │   │
│  │ .stream().content()             │   │
│  │                                 │   │
│  │ IF OpenAI fails:                │   │
│  │   fallbackClient (Ollama local) │   │
│  │   same prompt, same stream      │   │
│  └─────────────────────────────────┘   │
│                                        │
│  ┌──── COMPLEX (Agent mode) ───────┐   │
│  │ agentClient starts ReAct loop   │   │
│  │ [Planning your request...]      │   │
│  │                                 │   │
│  │ while (hasToolCalls &&          │   │
│  │        steps < 8):              │   │
│  │   toolCallingManager            │   │
│  │     .executeToolCalls()         │   │
│  │   Update conversation history   │   │
│  │   Continue with results         │   │
│  │                                 │   │
│  │ Stream final synthesis          │   │
│  └─────────────────────────────────┘   │
└──────────────────┬─────────────────────┘
                   │ SSE stream
                   ▼
App receives tokens:
data: "Tu saldo es $12,500 MXN."
...
data: [DONE]
```

---

## Available tools

The agent has access to 6 tools. For each, it makes an HTTP call to the
corresponding Docker service.

| Tool | Service | What it does |
|---|---|---|
| `get_account_balance` | nexus-account-service :8085 | Current balance |
| `get_transaction_history` | nexus-transaction-service :8086 | Recent transactions |
| `transfer_funds` | nexus-transaction-service :8086 | Initiates INTERNAL_TRANSFER saga |
| `spending_analysis` | nexus-analytics-service :8092 | Spend by category |
| `savings_recommendations` | nexus-account-service :8085 | AI savings tips |
| `get_fraud_alerts` | nexus-fraud-service :8087 | Active fraud alerts |

---

## Simple vs Agent mode

| Query | Mode | Why |
|---|---|---|
| "What's my balance?" | SIMPLE | Single data lookup |
| "How much did I spend on food this month?" | SIMPLE | Single analytics query |
| "Transfer $500 to John only if I have more than $1,000" | AGENT | Conditional logic |
| "Compare my spending this month vs last month" | AGENT | Multi-step comparison |
| "Check my balance, then pay my phone bill" | AGENT | Sequential operations |

---

## Session memory

```
conversationId = userId + ":" + sessionId

MessageChatMemoryAdvisor stores:
  - Each user message
  - Each assistant response
  - Tool call + result pairs

Stored in: Redis (session state) via SessionStateRepository
TTL: determined by session config

Effect: follow-up questions work naturally
  Turn 1: "What's my balance?"
  Turn 2: "And what about last month?"  ← knows what "what" refers to
```

---

## OpenAI + Ollama failover

```
primaryClient (OpenAI GPT-4o-mini)
        │
        │ HTTP call → api.openai.com
        │
        ▼
  IF OPENAI_API_KEY not set
  or OpenAI rate-limit/outage:
        │
        ▼ onErrorResume
fallbackClient (Ollama — runs locally in Docker)
  Model: llama3.1 or configured model
  No tools support in fallback mode
  Basic text-only response
```

---

## Analytics side effect

After every chat completion:
```
AiQueryEventProducer.publishQueryLogged(userId, sessionId, message, durationMs)
        │
        ▼ Kafka: ai.queries.logged
nexus-analytics-service :8092
  Updates: query frequency, popular questions, response latency metrics
```
