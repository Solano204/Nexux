# ACCOUNT ADVISOR INSIGHTS Flow — Docker + AWS

## Endpoint
```
GET /api/v1/accounts/{accountId}/advisor/insights
```
Authenticated. Returns JSON (NOT SSE — this is a synchronous structured response).

## What it does
Proactive weekly financial insight. The AI analyzes the account's spending
patterns and returns a structured JSON with savings opportunities, estimates,
and concrete action items. Designed for "Your weekly financial report" feature.

## Entry Point
```
App → GET /api/v1/accounts/{accountId}/advisor/insights → localhost:8080
Headers:
  Authorization: Bearer <jwt>
```

---

## Full Flow

```
App
 │
 │ GET /api/v1/accounts/{accountId}/advisor/insights
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
│  AccountAdvisorController.getInsights()│
│                                        │
│  advisorService.getProactiveAdvice     │
│    (accountId)                         │
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│  AccountAdvisorService                 │
│  .getProactiveAdvice(accountId)        │
│                                        │
│  ── Load analytics context ──          │
│  analyticsRepository                   │
│    .findByAccountId(accountId)         │
│  → MongoDB AccountAnalyticsDocument   │
│                                        │
│  analyticsContext =                    │
│  "Monthly spending: MXN 8,500          │
│   Monthly income: MXN 15,000           │
│   Transaction count: 23                │
│   Spending by category:                │
│     FOOD: MXN 2,100                    │
│     TRANSPORT: MXN 800                 │
│     ENTERTAINMENT: MXN 1,500..."       │
│                                        │
│  ── Non-streaming call ──              │
│  accountAdvisorClient.prompt()         │
│  .system(                              │
│    "Generate a proactive weekly        │
│     financial insight. Be specific     │
│     with numbers. Identify top 3       │
│     savings opportunities.             │
│     Return structured JSON matching    │
│     FinancialAdviceResponse schema.")  │
│  .user(                                │
│    "Analyze the past month for         │
│     account " + accountId +            │
│     "\n\n" + analyticsContext)         │
│  .call()                               │
│  .entity(FinancialAdviceResponse.class)│
│                                        │
│  → Spring AI parses JSON into record   │
└──────────────────┬─────────────────────┘
                   │
                   ▼
App ← HTTP 200 JSON response (synchronous, not streaming)
```

---

## Response structure

```json
{
  "summary": "Tu gasto en junio fue de $8,500 MXN. Tus ingresos 
              fueron $15,000, ahorrando un 43%. Hay oportunidades 
              para mejorar en entretenimiento y suscripciones.",

  "opportunities": [
    {
      "category": "ENTERTAINMENT",
      "currentMonthlySpend": 1500.00,
      "targetMonthlySpend": 900.00,
      "potentialSaving": 600.00,
      "recommendation": "Limita salidas a 2 por semana. 
                         Considera suscripciones compartidas."
    },
    {
      "category": "FOOD",
      "currentMonthlySpend": 2100.00,
      "targetMonthlySpend": 1600.00,
      "potentialSaving": 500.00,
      "recommendation": "Cocina en casa 3 días más por semana. 
                         Usa supermercado en lugar de delivery."
    },
    {
      "category": "SUBSCRIPTIONS",
      "currentMonthlySpend": 850.00,
      "targetMonthlySpend": 500.00,
      "potentialSaving": 350.00,
      "recommendation": "Revisa y cancela los servicios que 
                         no usas frecuentemente."
    }
  ],

  "estimatedMonthlySavings": 1450.00,

  "actionItems": [
    "Establece un presupuesto de $900 para entretenimiento este mes",
    "Prepara tu lista del supermercado antes de ir",
    "Revisa tus suscripciones activas esta semana",
    "Transfiere $1,000 a tu cuenta de ahorros antes del día 5"
  ]
}
```

---

## Key difference vs /advisor/chat

| Attribute | `/advisor/chat` | `/advisor/insights` |
|---|---|---|
| HTTP method | POST | GET |
| Response type | SSE stream | JSON (blocking) |
| Mode | Interactive | Proactive |
| Trigger | User asks | App polls (weekly) |
| Memory | Conversation session | No session memory |
| Return type | `Flux<String>` | `FinancialAdviceResponse` |
| Use case | "How can I save?" | Weekly insight card in app |

---

## Typical use in app

```
1. App calls /advisor/insights on dashboard load
   → Shows savings widget: "You could save $1,450/month"

2. User taps widget
   → Opens chat: POST /advisor/chat
   → "Tell me more about my entertainment spending"
   → Interactive conversation starts
```

---

## When OpenAI is not configured

The `.call().entity()` call will fail with an OpenAI API error.
This endpoint has no streaming fallback and no local Ollama fallback.
The response will be HTTP 500 until `OPENAI_API_KEY` is set to a real key.
