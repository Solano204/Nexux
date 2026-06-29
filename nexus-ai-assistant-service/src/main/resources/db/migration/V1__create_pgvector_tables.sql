CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS ai_conversation_memory (
    id      UUID    DEFAULT uuid_generate_v4() PRIMARY KEY,
    content TEXT,
    metadata JSON,
    embedding vector(1536)
);

CREATE INDEX IF NOT EXISTS ai_conversation_memory_embedding_idx
    ON ai_conversation_memory USING hnsw (embedding vector_cosine_ops);

CREATE TABLE IF NOT EXISTS financial_knowledge_base (
    id      UUID    DEFAULT uuid_generate_v4() PRIMARY KEY,
    content TEXT,
    metadata JSON,
    embedding vector(1536)
);

CREATE INDEX IF NOT EXISTS financial_knowledge_base_embedding_idx
    ON financial_knowledge_base USING hnsw (embedding vector_cosine_ops);
