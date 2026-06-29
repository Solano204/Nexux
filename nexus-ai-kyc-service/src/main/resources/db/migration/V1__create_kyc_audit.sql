-- ══════════════════════════════════════════════════════════════
-- KYC AUDIT TABLE — Immutable PostgreSQL audit trail
--
-- Every KYC decision is recorded here permanently.
-- This is the regulatory compliance record:
-- - Which document was submitted
-- - What the AI extracted
-- - What decision was made
-- - Why it was made
-- - Immutability trigger prevents any modification
--
-- MongoDB stores operational KYC documents.
-- PostgreSQL stores the immutable regulatory audit trail.
-- ══════════════════════════════════════════════════════════════

CREATE TABLE kyc_audit_entries (
    audit_id            UUID        NOT NULL DEFAULT gen_random_uuid(),

    -- Identity
    user_id             UUID        NOT NULL,
    verification_id     UUID        NOT NULL,
    attempt_number      INTEGER     NOT NULL DEFAULT 1,

    -- Document metadata (no raw image data stored here)
    document_type       VARCHAR(30) NOT NULL,
    document_storage_ref VARCHAR(500),   -- S3/GridFS reference

    -- AI pipeline results
    stage1_extraction_confidence DECIMAL(5, 4),
    stage1_duration_ms  INTEGER,
    stage2_comparison_confidence DECIMAL(5, 4),
    stage2_duration_ms  INTEGER,

    -- Decision
    decision            VARCHAR(20) NOT NULL
                        CHECK (decision IN (
                            'APPROVED', 'REJECTED',
                            'REVIEW_REQUIRED', 'FAILED')),
    rejection_reasons   TEXT[],     -- Array of reason codes
    user_facing_message TEXT,
    confidence_score    DECIMAL(5, 4),

    -- Field-level match results (JSONB for flexible schema)
    field_matches       JSONB,

    -- What was submitted vs what was extracted (hashed for privacy)
    submitted_name_hash VARCHAR(64),
    submitted_dob_hash  VARCHAR(64),
    extracted_name_hash VARCHAR(64),
    extracted_dob_hash  VARCHAR(64),

    -- Provenance
    ai_model_stage1     VARCHAR(50) NOT NULL,
    ai_model_stage2     VARCHAR(50) NOT NULL,
    processing_node     VARCHAR(100),
    trace_id            VARCHAR(32),

    -- Timing
    submitted_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    decided_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processing_duration_ms INTEGER,

    -- Regulatory
    retention_until     TIMESTAMPTZ NOT NULL
                        DEFAULT NOW() + INTERVAL '7 years',

    CONSTRAINT pk_kyc_audit PRIMARY KEY (audit_id)
);

-- Lookup indexes
CREATE INDEX idx_kyc_audit_user
    ON kyc_audit_entries (user_id, submitted_at DESC);

CREATE INDEX idx_kyc_audit_verification
    ON kyc_audit_entries (verification_id);

CREATE INDEX idx_kyc_audit_decision
    ON kyc_audit_entries (decision, submitted_at DESC);

-- ── Immutability trigger (same pattern as Ledger Service) ────
CREATE OR REPLACE FUNCTION prevent_kyc_audit_modification()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION
            'kyc_audit_entries are immutable: audit_id=% cannot be modified. '
            'Regulatory record — 7 year retention.',
            OLD.audit_id
            USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            'kyc_audit_entries are immutable: audit_id=% cannot be deleted. '
            'Required for regulatory compliance.',
            OLD.audit_id
            USING ERRCODE = '55000';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER enforce_kyc_audit_immutability
    BEFORE UPDATE OR DELETE ON kyc_audit_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_kyc_audit_modification();

-- ── Outbox for SAGA replies ────────────────────────────────
CREATE TABLE kyc_outbox (
    outbox_id       UUID        NOT NULL DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(50) NOT NULL DEFAULT 'KYC_VERIFICATION',
    aggregate_id    UUID        NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ,

    CONSTRAINT pk_kyc_outbox PRIMARY KEY (outbox_id)
);

CREATE INDEX idx_kyc_outbox_unprocessed
    ON kyc_outbox (created_at)
    WHERE processed_at IS NULL;