CREATE TABLE outbox (
    outbox_id       UUID        NOT NULL DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(50) NOT NULL DEFAULT 'SAGA',
    aggregate_id    UUID        NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    topic           VARCHAR(200) NOT NULL,
    payload         JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ,

    CONSTRAINT pk_saga_outbox PRIMARY KEY (outbox_id)
);

CREATE INDEX idx_saga_outbox_unprocessed
    ON outbox (created_at)
    WHERE processed_at IS NULL;