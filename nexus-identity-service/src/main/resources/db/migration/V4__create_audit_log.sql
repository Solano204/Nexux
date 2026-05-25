-- ══════════════════════════════════════════════════════════════
-- AUDIT LOG TABLE — Immutable security event trail
-- NEVER updated or deleted (trigger enforces immutability)
-- Retained 7+ years for regulatory compliance
-- Links to Zipkin traces via trace_id column
-- ══════════════════════════════════════════════════════════════

CREATE TABLE audit_log (
    audit_id        UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id         UUID,                   -- Nullable: pre-registration events
    event_type      VARCHAR(100) NOT NULL,
    ip_address      INET,
    user_agent      TEXT,
    details         JSONB,                  -- Event-specific structured data
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    trace_id        VARCHAR(32),            -- Zipkin traceId for correlation

    CONSTRAINT pk_audit_log PRIMARY KEY (audit_id)
    -- NO foreign key on user_id — must survive user soft-delete
);

CREATE INDEX idx_audit_user_id ON audit_log (user_id);
CREATE INDEX idx_audit_event_type ON audit_log (event_type);
CREATE INDEX idx_audit_occurred_at ON audit_log (occurred_at);
CREATE INDEX idx_audit_user_occurred
    ON audit_log (user_id, occurred_at DESC);

-- IMMUTABILITY TRIGGER — Prevents any modification to audit records
CREATE OR REPLACE FUNCTION prevent_audit_log_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'audit_log entries are immutable. ' ||
        'Attempted operation: % on record: %',
        TG_OP, OLD.audit_id;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_audit_log_immutable
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_modification();