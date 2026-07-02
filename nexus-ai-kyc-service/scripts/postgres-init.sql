-- scripts/postgres-init.sql
-- Creates nexus_kyc database for the immutable KYC audit trail.
-- Plain PostgreSQL — no pgvector extension needed for this service.

SELECT 'CREATE DATABASE nexus_kyc OWNER nexus'
WHERE NOT EXISTS (
    SELECT FROM pg_database WHERE datname = 'nexus_kyc'
)\gexec

\c nexus_kyc

GRANT ALL PRIVILEGES ON DATABASE nexus_kyc TO nexus;
GRANT ALL PRIVILEGES ON SCHEMA public TO nexus;

ALTER DATABASE nexus_kyc SET timezone TO 'UTC';
ALTER DATABASE nexus_kyc SET search_path TO public;
