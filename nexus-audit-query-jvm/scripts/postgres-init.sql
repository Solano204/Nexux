-- scripts/postgres-init.sql
-- Creates nexus_audit database with pgvector extension.
-- pgvector required for Spring AI audit event embeddings
-- (compliance query RAG pipeline).

SELECT 'CREATE DATABASE nexus_audit OWNER nexus'
WHERE NOT EXISTS (
    SELECT FROM pg_database WHERE datname = 'nexus_audit'
)\gexec

\c nexus_audit

GRANT ALL PRIVILEGES ON DATABASE nexus_audit TO nexus;
GRANT ALL PRIVILEGES ON SCHEMA public TO nexus;

-- pgvector: required for audit event embedding store
CREATE EXTENSION IF NOT EXISTS vector;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
        RAISE EXCEPTION 'pgvector extension failed to install';
    END IF;
    RAISE NOTICE 'pgvector extension installed successfully';
END $$;

ALTER DATABASE nexus_audit SET timezone TO 'UTC';
ALTER DATABASE nexus_audit SET search_path TO public;
