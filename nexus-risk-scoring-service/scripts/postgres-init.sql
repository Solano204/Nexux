-- scripts/postgres-init.sql
-- Plain PostgreSQL — no pgvector needed for risk scoring.

SELECT 'CREATE DATABASE nexus_risk OWNER nexus'
WHERE NOT EXISTS (
    SELECT FROM pg_database WHERE datname = 'nexus_risk'
)\gexec

\c nexus_risk

GRANT ALL PRIVILEGES ON DATABASE nexus_risk TO nexus;
GRANT ALL PRIVILEGES ON SCHEMA public TO nexus;
ALTER DATABASE nexus_risk SET timezone TO 'UTC';
ALTER DATABASE nexus_risk SET search_path TO public;
