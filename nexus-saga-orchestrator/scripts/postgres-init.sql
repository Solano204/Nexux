-- scripts/postgres-init.sql
-- Creates nexus_saga database. Plain PostgreSQL — no pgvector needed.
-- Flyway handles all table creation via V1-V5 migrations.

SELECT 'CREATE DATABASE nexus_saga OWNER nexus'
WHERE NOT EXISTS (
    SELECT FROM pg_database WHERE datname = 'nexus_saga'
)\gexec

\c nexus_saga

GRANT ALL PRIVILEGES ON DATABASE nexus_saga TO nexus;
GRANT ALL PRIVILEGES ON SCHEMA public TO nexus;

ALTER DATABASE nexus_saga SET timezone TO 'UTC';
ALTER DATABASE nexus_saga SET search_path TO public;

-- SAGA transitions require fast lock acquisition
ALTER DATABASE nexus_saga SET lock_timeout TO '5s';
ALTER DATABASE nexus_saga SET statement_timeout TO '30s';
