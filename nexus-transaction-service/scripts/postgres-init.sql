-- scripts/postgres-init.sql
-- Runs on first container start.
-- Creates nexus_transaction database with optimized settings.
-- Plain PostgreSQL — no pgvector extension needed here.

SELECT 'CREATE DATABASE nexus_transaction OWNER nexus'
WHERE NOT EXISTS (
    SELECT FROM pg_database WHERE datname = 'nexus_transaction'
)\gexec

\c nexus_transaction

GRANT ALL PRIVILEGES ON DATABASE nexus_transaction TO nexus;
GRANT ALL PRIVILEGES ON SCHEMA public TO nexus;

ALTER DATABASE nexus_transaction SET timezone TO 'UTC';
ALTER DATABASE nexus_transaction SET search_path TO public;

-- Tune for write-heavy financial workload
ALTER DATABASE nexus_transaction SET synchronous_commit TO 'on';
ALTER DATABASE nexus_transaction SET default_transaction_isolation TO 'read committed';
