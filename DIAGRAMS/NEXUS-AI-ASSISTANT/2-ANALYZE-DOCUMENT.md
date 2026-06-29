```mermaid
sequenceDiagram
    autonumber
    actor Client as 🧑‍💻 Client
    participant GW as 🟢 API Gateway
    participant AS as 🤖 AI Assistant Service
    participant DS as 🔍 DocumentAnalysisService
    participant LLM as 🧠 OpenAI Vision / Ollama

    rect rgb(200, 220, 255)
        Note over Client,GW: ═══ STEP 1: Document Analysis Request (SSE) ═══
        Note over Client: Two equivalent endpoints:<br/>POST /api/v1/ai/chat/analyze-document<br/>POST /api/v1/ai/documents/analyze
        Client->>+GW: POST /api/v1/ai/documents/analyze<br/>multipart/form-data: file (image/PDF), message, sessionId?
        GW->>GW: verify JWT → set X-User-Id
        GW->>+AS: forward multipart (expects text/event-stream)
    end

    rect rgb(255, 200, 200)
        Note over AS,DS: ═══ STEP 2: Prepare Multimodal Request ═══
        AS->>AS: extract userId, build conversationId = userId:sessionId
        AS->>+DS: analyzeAndRespond(fileBytes, contentType, message, convId)
        DS->>DS: convert file to base64
        DS->>DS: build multimodal message:<br/>[ { type: image_url, url: data:{contentType};base64,{b64} },<br/>  { type: text, text: message } ]
    end

    rect rgb(200, 255, 200)
        Note over DS,LLM: ═══ STEP 3: Vision Model Streaming ═══
        DS->>+LLM: chat({ model: gpt-4o, messages: [systemPrompt, multimodalContent] })
        Note over LLM: Vision model reads the image<br/>Extracts: amounts, dates, merchant, category
        loop SSE Token Stream
            LLM-->>DS: token chunk
            DS-->>AS: Flux<String> emit
            AS-->>GW: SSE: data: {token}
            GW-->>Client: SSE: data: {token}
        end
        LLM-->>-DS: [DONE]
    end

    rect rgb(255, 240, 200)
        Note over Client,LLM: ✅ DOCUMENT ANALYZED — vision model extracted financial data from image/PDF via SSE
    end
```
