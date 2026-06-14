-- scripts/postgres-init.sql
-- Creates nexus_ai_assistant database with pgvector extension.
-- pgvector is required for Spring AI's vector store (RAG financial knowledge base).
-- JDBC chat memory schema is created automatically by Spring AI
-- (spring.ai.chat.memory.repository.jdbc.initialize-schema: always).

SELECT 'CREATE DATABASE nexus_ai_assistant OWNER nexus'
WHERE NOT EXISTS (
    SELECT FROM pg_database WHERE datname = 'nexus_ai_assistant'
)\gexec

\c nexus_ai_assistant

GRANT ALL PRIVILEGES ON DATABASE nexus_ai_assistant TO nexus;
GRANT ALL PRIVILEGES ON SCHEMA public TO nexus;

-- pgvector: required for Spring AI vector store (RAG embeddings)
CREATE EXTENSION IF NOT EXISTS vector;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
        RAISE EXCEPTION 'pgvector extension failed to install';
    END IF;
    RAISE NOTICE 'pgvector extension installed successfully';
END $$;

ALTER DATABASE nexus_ai_assistant SET timezone TO 'UTC';
ALTER DATABASE nexus_ai_assistant SET search_path TO public;
