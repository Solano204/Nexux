-- Adds distributed-trace context to outbox rows so Debezium can copy it
-- onto the Kafka record as a 'b3' header (see debezium/register.sh).
-- All columns nullable: existing rows are untouched, no backfill needed.

ALTER TABLE outbox ADD COLUMN trace_id VARCHAR(32) NULL;
ALTER TABLE outbox ADD COLUMN span_id VARCHAR(16) NULL;
ALTER TABLE outbox ADD COLUMN trace_sampled VARCHAR(1) NULL;
ALTER TABLE outbox ADD COLUMN trace_b3 VARCHAR(64) NULL;
