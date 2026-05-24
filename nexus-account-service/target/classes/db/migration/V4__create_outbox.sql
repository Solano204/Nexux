CREATE TABLE outbox (
    outbox_id       UUID        NOT NULL DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(100) NOT NULL,
    aggregate_id    UUID        NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ,
    CONSTRAINT pk_account_outbox PRIMARY KEY (outbox_id)
);
CREATE INDEX idx_account_outbox_created ON outbox (created_at);
CREATE INDEX idx_account_outbox_aggregate ON outbox (aggregate_type, event_type);