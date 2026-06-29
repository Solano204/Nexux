#!/bin/sh
# Registers all Debezium outbox connectors via Kafka Connect REST API.
# Runs once on startup; 409 responses (already exists) are silently skipped.

CONNECT="http://nexus-kafka-connect:8083"

echo "Waiting for Kafka Connect to accept connections..."
until curl -sf -o /dev/null "$CONNECT/connectors"; do
  sleep 3
done
echo "Kafka Connect ready."

reg() {
  name="$1"
  body="$2"
  resp=$(curl -s -o /tmp/resp.txt -w "%{http_code}" \
    -X POST "$CONNECT/connectors" \
    -H "Content-Type: application/json" \
    -d "$body")
  case $resp in
    201) echo "  [OK]     $name" ;;
    409) echo "  [EXISTS] $name" ;;
    *)   echo "  [WARN]   $name HTTP $resp: $(cat /tmp/resp.txt)" ;;
  esac
}

# Debezium 2.x: use "topic.prefix" (renamed from "database.server.name")

# ── Identity Service → nexus_identity ────────────────────────────
BODY=$(cat << EOF
{
  "name": "nexus-identity-outbox",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "topic.prefix": "nexus-identity",
    "database.hostname": "nexus-postgres",
    "database.port": "5432",
    "database.user": "nexus",
    "database.password": "$POSTGRES_PASSWORD",
    "database.dbname": "nexus_identity",
    "table.include.list": "public.outbox",
    "plugin.name": "pgoutput",
    "publication.autocreate.mode": "filtered",
    "slot.name": "debezium_identity",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.table.field.event.id": "outbox_id",
    "transforms.outbox.table.field.event.key": "aggregate_id",
    "transforms.outbox.table.field.event.type": "event_type",
    "transforms.outbox.route.by.field": "aggregate_type",
    "transforms.outbox.route.topic.replacement": "\${routedByValue}",
    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "value.converter": "org.apache.kafka.connect.storage.StringConverter"
  }
}
EOF
)
reg "nexus-identity-outbox" "$BODY"

# ── Saga Orchestrator → nexus_saga ────────────────────────────────
# Routes by the `topic` column (explicit topic field in saga's OutboxEntry)
BODY=$(cat << EOF
{
  "name": "nexus-saga-outbox",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "topic.prefix": "nexus-saga",
    "database.hostname": "nexus-postgres",
    "database.port": "5432",
    "database.user": "nexus",
    "database.password": "$POSTGRES_PASSWORD",
    "database.dbname": "nexus_saga",
    "table.include.list": "public.outbox",
    "plugin.name": "pgoutput",
    "publication.autocreate.mode": "filtered",
    "slot.name": "debezium_saga",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.table.field.event.id": "outbox_id",
    "transforms.outbox.table.field.event.key": "aggregate_id",
    "transforms.outbox.table.field.event.type": "event_type",
    "transforms.outbox.route.by.field": "topic",
    "transforms.outbox.route.topic.replacement": "\${routedByValue}",
    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "value.converter": "org.apache.kafka.connect.storage.StringConverter"
  }
}
EOF
)
reg "nexus-saga-outbox" "$BODY"

# ── Account Service → nexus_accounts ──────────────────────────────
BODY=$(cat << EOF
{
  "name": "nexus-accounts-outbox",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "topic.prefix": "nexus-accounts",
    "database.hostname": "nexus-postgres",
    "database.port": "5432",
    "database.user": "nexus",
    "database.password": "$POSTGRES_PASSWORD",
    "database.dbname": "nexus_accounts",
    "table.include.list": "public.outbox",
    "plugin.name": "pgoutput",
    "publication.autocreate.mode": "filtered",
    "slot.name": "debezium_accounts",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.table.field.event.id": "outbox_id",
    "transforms.outbox.table.field.event.key": "aggregate_id",
    "transforms.outbox.table.field.event.type": "event_type",
    "transforms.outbox.route.by.field": "aggregate_type",
    "transforms.outbox.route.topic.replacement": "\${routedByValue}",
    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "value.converter": "org.apache.kafka.connect.storage.StringConverter"
  }
}
EOF
)
reg "nexus-accounts-outbox" "$BODY"

# ── Transaction Service → nexus_transactions ──────────────────────
BODY=$(cat << EOF
{
  "name": "nexus-transactions-outbox",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "topic.prefix": "nexus-transactions",
    "database.hostname": "nexus-postgres",
    "database.port": "5432",
    "database.user": "nexus",
    "database.password": "$POSTGRES_PASSWORD",
    "database.dbname": "nexus_transactions",
    "table.include.list": "public.outbox",
    "plugin.name": "pgoutput",
    "publication.autocreate.mode": "filtered",
    "slot.name": "debezium_transactions",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.table.field.event.id": "outbox_id",
    "transforms.outbox.table.field.event.key": "aggregate_id",
    "transforms.outbox.table.field.event.type": "event_type",
    "transforms.outbox.route.by.field": "aggregate_type",
    "transforms.outbox.route.topic.replacement": "\${routedByValue}",
    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "value.converter": "org.apache.kafka.connect.storage.StringConverter"
  }
}
EOF
)
reg "nexus-transactions-outbox" "$BODY"

# ── Ledger Service → nexus_ledger ─────────────────────────────────
BODY=$(cat << EOF
{
  "name": "nexus-ledger-outbox",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "topic.prefix": "nexus-ledger",
    "database.hostname": "nexus-postgres",
    "database.port": "5432",
    "database.user": "nexus",
    "database.password": "$POSTGRES_PASSWORD",
    "database.dbname": "nexus_ledger",
    "table.include.list": "public.outbox",
    "plugin.name": "pgoutput",
    "publication.autocreate.mode": "filtered",
    "slot.name": "debezium_ledger",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.table.field.event.id": "outbox_id",
    "transforms.outbox.table.field.event.key": "aggregate_id",
    "transforms.outbox.table.field.event.type": "event_type",
    "transforms.outbox.route.by.field": "aggregate_type",
    "transforms.outbox.route.topic.replacement": "\${routedByValue}",
    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "value.converter": "org.apache.kafka.connect.storage.StringConverter"
  }
}
EOF
)
reg "nexus-ledger-outbox" "$BODY"

# ── Fraud Service → nexus_fraud ───────────────────────────────────
BODY=$(cat << EOF
{
  "name": "nexus-fraud-outbox",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "topic.prefix": "nexus-fraud",
    "database.hostname": "nexus-postgres",
    "database.port": "5432",
    "database.user": "nexus",
    "database.password": "$POSTGRES_PASSWORD",
    "database.dbname": "nexus_fraud",
    "table.include.list": "public.outbox",
    "plugin.name": "pgoutput",
    "publication.autocreate.mode": "filtered",
    "slot.name": "debezium_fraud",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.table.field.event.id": "outbox_id",
    "transforms.outbox.table.field.event.key": "aggregate_id",
    "transforms.outbox.table.field.event.type": "event_type",
    "transforms.outbox.route.by.field": "aggregate_type",
    "transforms.outbox.route.topic.replacement": "\${routedByValue}",
    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "value.converter": "org.apache.kafka.connect.storage.StringConverter"
  }
}
EOF
)
reg "nexus-fraud-outbox" "$BODY"

echo "Connector registration complete."
