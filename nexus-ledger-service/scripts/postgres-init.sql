-- scripts/postgres-init.sql
-- Creates nexus_ledger database and installs pgvector.
-- pgvector is required for the financial_literacy_embeddings table
-- used by the ledger explainer RAG pipeline.

SELECT 'CREATE DATABASE nexus_ledger OWNER nexus'
WHERE NOT EXISTS (
    SELECT FROM pg_database WHERE datname = 'nexus_ledger'
)\gexec

\c nexus_ledger

GRANT ALL PRIVILEGES ON DATABASE nexus_ledger TO nexus;
GRANT ALL PRIVILEGES ON SCHEMA public TO nexus;

-- Install pgvector — required for Spring AI financial_literacy_embeddings
CREATE EXTENSION IF NOT EXISTS vector;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
        RAISE EXCEPTION 'pgvector extension failed to install';
    END IF;
    RAISE NOTICE 'pgvector extension installed successfully';
END $$;

ALTER DATABASE nexus_ledger SET timezone TO 'UTC';
ALTER DATABASE nexus_ledger SET search_path TO public;

-- SERIALIZABLE isolation for all ledger transactions
-- Individual transactions override this as needed
ALTER DATABASE nexus_ledger SET default_transaction_isolation TO 'serializable';
