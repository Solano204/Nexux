-- scripts/postgres-init.sql
-- Runs on first container start.
-- Creates nexus_account database and installs pgvector extension.
--
-- CRITICAL: The pgvector extension must be installed BEFORE the account
-- service starts, because Spring AI's initialize-schema: true will try
-- to CREATE TABLE transaction_embeddings (embedding vector(1536))
-- and that column type requires the vector extension to exist first.

-- Create database
SELECT 'CREATE DATABASE nexus_account OWNER nexus'
WHERE NOT EXISTS (
    SELECT FROM pg_database WHERE datname = 'nexus_account'
)\gexec

\c nexus_account

GRANT ALL PRIVILEGES ON DATABASE nexus_account TO nexus;
GRANT ALL PRIVILEGES ON SCHEMA public TO nexus;

-- Install pgvector extension
-- This is what allows the vector(1536) column type used by Spring AI
CREATE EXTENSION IF NOT EXISTS vector;

-- Verify installation
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_extension WHERE extname = 'vector'
    ) THEN
        RAISE EXCEPTION 'pgvector extension failed to install';
    END IF;
    RAISE NOTICE 'pgvector extension installed successfully';
END $$;

-- Set database defaults
ALTER DATABASE nexus_account SET timezone TO 'UTC';
ALTER DATABASE nexus_account SET search_path TO public;
