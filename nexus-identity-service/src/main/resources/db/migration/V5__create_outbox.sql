-- ══════════════════════════════════════════════════════════════
-- OUTBOX TABLE — Transactional event staging for Debezium CDC
-- Written in same transaction as domain changes (atomicity guarantee)
-- Debezium reads WAL changes → publishes to Kafka
-- NOT read by the application after insert
-- ══════════════════════════════════════════════════════════════

CREATE TABLE outbox (
    outbox_id       UUID        NOT NULL DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(100) NOT NULL,  -- e.g., 'USER'
    aggregate_id    UUID        NOT NULL,   -- userId
    event_type      VARCHAR(100) NOT NULL,  -- e.g., 'UserRegistered'
    payload         JSONB       NOT NULL,   -- Full event payload
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ,            -- Set by cleanup job (not Debezium)

    CONSTRAINT pk_outbox PRIMARY KEY (outbox_id)
);

-- Debezium uses WAL, not table scans.
-- This index is for the cleanup scheduled job only.
CREATE INDEX idx_outbox_created ON outbox (created_at);
CREATE INDEX idx_outbox_aggregate ON outbox (aggregate_type, event_type);