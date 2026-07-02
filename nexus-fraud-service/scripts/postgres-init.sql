-- scripts/postgres-init.sql
-- Creates nexus_fraud database with pgvector extension.
-- pgvector is required for fraud_policy_embeddings (RAG policy retrieval).

SELECT 'CREATE DATABASE nexus_fraud OWNER nexus'
WHERE NOT EXISTS (
    SELECT FROM pg_database WHERE datname = 'nexus_fraud'
)\gexec

\c nexus_fraud

GRANT ALL PRIVILEGES ON DATABASE nexus_fraud TO nexus;
GRANT ALL PRIVILEGES ON SCHEMA public TO nexus;

-- pgvector required for Spring AI fraud policy knowledge base
CREATE EXTENSION IF NOT EXISTS vector;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
        RAISE EXCEPTION 'pgvector extension failed to install';
    END IF;
    RAISE NOTICE 'pgvector extension installed successfully';
END $$;

ALTER DATABASE nexus_fraud SET timezone TO 'UTC';
ALTER DATABASE nexus_fraud SET search_path TO public;
