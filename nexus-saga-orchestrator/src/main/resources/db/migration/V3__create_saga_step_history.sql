-- Append-only audit trail — immutability trigger
CREATE TABLE saga_step_history (
    history_id  UUID        NOT NULL DEFAULT gen_random_uuid(),
    saga_id     UUID        NOT NULL,
    saga_type   VARCHAR(20) NOT NULL
                CHECK (saga_type IN ('TRANSFER','ONBOARDING')),
    from_step   VARCHAR(50),
    to_step     VARCHAR(50) NOT NULL,
    reason      TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    duration_ms INTEGER,
    trace_id    VARCHAR(32),

    CONSTRAINT pk_saga_step_history PRIMARY KEY (history_id)
);

CREATE INDEX idx_step_history_saga_id
    ON saga_step_history (saga_id, occurred_at DESC);

-- Immutability trigger
CREATE OR REPLACE FUNCTION prevent_saga_history_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'saga_step_history is immutable'
        USING ERRCODE = '55000';
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER enforce_saga_history_immutability
    BEFORE UPDATE OR DELETE ON saga_step_history
    FOR EACH ROW EXECUTE FUNCTION prevent_saga_history_modification();