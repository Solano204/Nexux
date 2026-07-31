-- Adds distributed-trace context to kyc_outbox rows so Debezium can copy it
-- onto the Kafka record as a 'b3' header (see debezium/register.sh), same
-- pattern already applied to the other 6 services' outbox tables (see
-- CHANGES-BESTPRACTICES/08_EVENT_DESIGN_CHANGES.md Section 6). All columns
-- nullable: kyc_outbox has never been written to (no Java code used it
-- until this change), so there is nothing to backfill.

ALTER TABLE kyc_outbox ADD COLUMN trace_id VARCHAR(32) NULL;
ALTER TABLE kyc_outbox ADD COLUMN span_id VARCHAR(16) NULL;
ALTER TABLE kyc_outbox ADD COLUMN trace_sampled VARCHAR(1) NULL;
ALTER TABLE kyc_outbox ADD COLUMN trace_b3 VARCHAR(64) NULL;
