#!/bin/bash
set -e

echo "=== Creating all NEXUS service databases ==="

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-EOSQL
  CREATE DATABASE nexus_identity;
  CREATE DATABASE nexus_accounts;
  CREATE DATABASE nexus_transactions;
  CREATE DATABASE nexus_fraud;
  CREATE DATABASE nexus_ledger;
  CREATE DATABASE nexus_kyc;
  CREATE DATABASE nexus_ai_assistant;
  CREATE DATABASE nexus_risk;
  CREATE DATABASE nexus_saga;
  CREATE DATABASE nexus_audit;
EOSQL

echo "=== Enabling pgvector extension in vector-search databases ==="

for db in nexus_accounts nexus_fraud nexus_ledger nexus_ai_assistant nexus_audit; do
  echo "  -> $db"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" <<-EOSQL
    CREATE EXTENSION IF NOT EXISTS vector;
EOSQL
done

echo "=== Done. All NEXUS databases are ready. ==="
