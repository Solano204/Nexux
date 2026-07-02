-- scripts/postgres-init.sql
-- Runs automatically when the PostgreSQL container starts for the first time.
-- Creates the nexus_identity database with proper settings.
-- This is idempotent — safe to run multiple times.

-- Create database if not exists (postgres extension trick)
SELECT 'CREATE DATABASE nexus_identity OWNER nexus'
WHERE NOT EXISTS (
    SELECT FROM pg_database WHERE datname = 'nexus_identity'
)\gexec

-- Connect to nexus_identity and set up defaults
\c nexus_identity

-- Grant all privileges to the nexus user
GRANT ALL PRIVILEGES ON DATABASE nexus_identity TO nexus;
GRANT ALL PRIVILEGES ON SCHEMA public TO nexus;

-- Set default search path
ALTER DATABASE nexus_identity SET search_path TO public;

-- Timezone: UTC for all stored timestamps
ALTER DATABASE nexus_identity SET timezone TO 'UTC';
