-- ══════════════════════════════════════════════════════════════
-- ACCOUNT EVENTS TABLE — Immutable balance change audit trail
-- Records EVERY balance operation with before/after snapshots.
-- Trigger prevents modification — regulatory requirement.
-- ══════════════════════════════════════════════════════════════

CREATE TABLE account_events (
    event_id            UUID            NOT NULL DEFAULT gen_random_uuid(),
    account_id          UUID            NOT NULL,
    transaction_id      UUID,
    event_type          VARCHAR(50)     NOT NULL,
    amount              DECIMAL(20, 4),
    balance_before      DECIMAL(20, 4)  NOT NULL,
    balance_after       DECIMAL(20, 4)  NOT NULL,
    available_before    DECIMAL(20, 4)  NOT NULL,
    available_after     DECIMAL(20, 4)  NOT NULL,
    reserved_before     DECIMAL(20, 4)  NOT NULL DEFAULT 0,
    reserved_after      DECIMAL(20, 4)  NOT NULL DEFAULT 0,
    details             JSONB,
    occurred_at         TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    trace_id            VARCHAR(32),

    CONSTRAINT pk_account_events PRIMARY KEY (event_id)
    -- NO FK to accounts — must retain events after account closure
);

CREATE INDEX idx_account_events_account ON account_events (account_id);
CREATE INDEX idx_account_events_occurred
    ON account_events (account_id, occurred_at DESC);
CREATE INDEX idx_account_events_type ON account_events (event_type);
CREATE INDEX idx_account_events_transaction
    ON account_events (transaction_id)
    WHERE transaction_id IS NOT NULL;

-- IMMUTABILITY TRIGGER
CREATE OR REPLACE FUNCTION prevent_account_event_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'account_events are immutable: %', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_account_events_immutable
    BEFORE UPDATE OR DELETE ON account_events
    FOR EACH ROW EXECUTE FUNCTION prevent_account_event_modification();