#!/bin/bash
# Creates multiple PostgreSQL databases from the POSTGRES_MULTIPLE_DATABASES env var.
# Called automatically by the postgres entrypoint on first init.
# Usage: POSTGRES_MULTIPLE_DATABASES=db1,db2,db3

set -e

function create_db() {
    local db=$1
    echo "Creating database: $db"
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
        CREATE DATABASE "$db";
        GRANT ALL PRIVILEGES ON DATABASE "$db" TO "$POSTGRES_USER";
EOSQL
}

if [ -n "$POSTGRES_MULTIPLE_DATABASES" ]; then
    echo "Multiple databases requested: $POSTGRES_MULTIPLE_DATABASES"
    for db in $(echo "$POSTGRES_MULTIPLE_DATABASES" | tr ',' ' '); do
        create_db "$db"
    done
    echo "All databases created."
fi
