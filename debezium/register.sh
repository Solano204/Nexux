#!/bin/sh
# Registers/updates all Debezium outbox connectors via Kafka Connect REST API.
# Runs once on startup. Uses PUT /connectors/{name}/config, which creates the
# connector if it doesn't exist and OVERWRITES its config if it does - unlike
# a POST-based "create if missing" script, this always converges the running
# connector onto whatever config is below, even if it was registered earlier
# under an older version of this file (e.g. before the trace_b3 header
# mapping existed). A POST + ignore-409 script would silently keep serving
# the stale config forever since "already exists" looks like success.
#
# TRACE PROPAGATION: each connector's EventRouter transform carries
# "transforms.outbox.table.fields.additional.placement": "trace_b3:header:b3".
# This is Debezium's native mechanism for exposing extra outbox columns as
# Kafka record headers - it does NOT need a second SMT chained after
# EventRouter (EventRouter already does the equivalent of "unwrap" itself,
# extracting payload/routing by aggregate_type or topic in one step). A
# generic ExtractNewRecordState + HeaderFrom$Value chain would either
# conflict with this transform or run too late to see the outbox row's
# columns at all, since EventRouter's own output envelope drops any column
# not explicitly declared via table.fields.additional.placement.
#
# The header is named 'b3' (singular, not 3 separate X-B3-* headers) because
# nexus-platform-config's management.tracing.propagation.type is B3, which
# Spring Boot maps to Brave's SINGLE-header B3 format ("traceId-spanId-
# sampled"), not B3_MULTI. trace_b3 is written pre-concatenated by the
# application (OutboxEntry.attachTraceContext) since Kafka Connect SMTs can
# only copy a field 1:1 into a header, not concatenate multiple columns.

CONNECT="http://nexus-kafka-connect:8083"

echo "Waiting for Kafka Connect to accept connections..."
until curl -sf -o /dev/null "$CONNECT/connectors"; do
  sleep 3
done
echo "Kafka Connect ready."

reg() {
  name="$1"
  config="$2"
  resp=$(curl -s -o /tmp/resp.txt -w "%{http_code}" \
    -X PUT "$CONNECT/connectors/$name/config" \
    -H "Content-Type: application/json" \
    -d "$config")
  case $resp in
    200) echo "  [UPDATED] $name" ;;
    201) echo "  [CREATED] $name" ;;
    *)   echo "  [WARN]    $name HTTP $resp: $(cat /tmp/resp.txt)" ;;
  esac
}

# Debezium 2.x: use "topic.prefix" (renamed from "database.server.name")

# ── Identity Service → nexus_identity ────────────────────────────
CONFIG=$(cat << EOF
{
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
  "transforms.outbox.table.fields.additional.placement": "trace_b3:header:b3",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.storage.StringConverter"
}
EOF
)
reg "nexus-identity-outbox" "$CONFIG"

# ── Saga Orchestrator → nexus_saga ────────────────────────────────
# Routes by the `topic` column (explicit topic field in saga's OutboxEntry)
CONFIG=$(cat << EOF
{
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
  "transforms.outbox.table.fields.additional.placement": "trace_b3:header:b3",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.storage.StringConverter"
}
EOF
)
reg "nexus-saga-outbox" "$CONFIG"

# ── Account Service → nexus_accounts ──────────────────────────────
CONFIG=$(cat << EOF
{
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
  "transforms.outbox.table.fields.additional.placement": "trace_b3:header:b3",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.storage.StringConverter"
}
EOF
)
reg "nexus-accounts-outbox" "$CONFIG"

# ── Transaction Service → nexus_transactions ──────────────────────
CONFIG=$(cat << EOF
{
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
  "transforms.outbox.table.fields.additional.placement": "trace_b3:header:b3",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.storage.StringConverter"
}
EOF
)
reg "nexus-transactions-outbox" "$CONFIG"

# ── Ledger Service → nexus_ledger ─────────────────────────────────
CONFIG=$(cat << EOF
{
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
  "transforms.outbox.table.fields.additional.placement": "trace_b3:header:b3",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.storage.StringConverter"
}
EOF
)
reg "nexus-ledger-outbox" "$CONFIG"

# ── Fraud Service → nexus_fraud ───────────────────────────────────
CONFIG=$(cat << EOF
{
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
  "transforms.outbox.table.fields.additional.placement": "trace_b3:header:b3",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.storage.StringConverter"
}
EOF
)
reg "nexus-fraud-outbox" "$CONFIG"

# ── AI KYC Service → nexus_kyc ────────────────────────────────────
# Table is kyc_outbox (not "outbox" like the other 6) - see
# V1__create_kyc_audit.sql. Same column names though, so the EventRouter
# transform config below is otherwise identical to the others.
CONFIG=$(cat << EOF
{
  "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
  "topic.prefix": "nexus-kyc",
  "database.hostname": "nexus-postgres",
  "database.port": "5432",
  "database.user": "nexus",
  "database.password": "$POSTGRES_PASSWORD",
  "database.dbname": "nexus_kyc",
  "table.include.list": "public.kyc_outbox",
  "plugin.name": "pgoutput",
  "publication.autocreate.mode": "filtered",
  "slot.name": "debezium_kyc",
  "transforms": "outbox",
  "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
  "transforms.outbox.table.field.event.id": "outbox_id",
  "transforms.outbox.table.field.event.key": "aggregate_id",
  "transforms.outbox.table.field.event.type": "event_type",
  "transforms.outbox.route.by.field": "aggregate_type",
  "transforms.outbox.route.topic.replacement": "\${routedByValue}",
  "transforms.outbox.table.fields.additional.placement": "trace_b3:header:b3",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.storage.StringConverter"
}
EOF
)
reg "nexus-kyc-outbox" "$CONFIG"

echo "Connector registration complete."
