CREATE TABLE outbox (
    outbox_id       UUID        NOT NULL DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(100) NOT NULL,
    aggregate_id    UUID        NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ,

    CONSTRAINT pk_ledger_outbox PRIMARY KEY (outbox_id)
);

CREATE INDEX idx_ledger_outbox_created ON outbox (created_at);