-- ══════════════════════════════════════════════════════════════
-- KYC VERIFICATIONS TABLE — Complete KYC attempt history
-- Immutable once decided (trigger prevents updates to final fields)
-- JSONB columns store AI extraction + decision results
-- ══════════════════════════════════════════════════════════════

CREATE TABLE kyc_verifications (
    verification_id         UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id                 UUID        NOT NULL,
    attempt_number          INTEGER     NOT NULL DEFAULT 1,
    document_type           VARCHAR(50) NOT NULL
                            CHECK (document_type IN (
                                'PASSPORT', 'NATIONAL_ID', 'DRIVERS_LICENSE')),
    document_s3_path        VARCHAR(500),
    document_s3_bucket      VARCHAR(100),

    -- AWS Rekognition pre-analysis results
    rekognition_result      JSONB,

    -- Spring AI extraction results (Stage 1 — vision model)
    ai_extracted_data       JSONB,

    -- Spring AI verification decision (Stage 2 — comparison model)
    ai_verification_decision JSONB,

    -- Final decision
    final_decision          VARCHAR(20)
                            CHECK (final_decision IN (
                                'APPROVED', 'REJECTED', 'PENDING',
                                'MANUAL_REVIEW', 'TIMEOUT')),
    decided_at              TIMESTAMPTZ,
    reviewed_by             UUID,           -- Admin who manually reviewed (if applicable)
    failure_reasons         TEXT[],

    -- Lifecycle timestamps
    initiated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at            TIMESTAMPTZ,

    CONSTRAINT pk_kyc_verifications PRIMARY KEY (verification_id),
    CONSTRAINT fk_kyc_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
);

CREATE INDEX idx_kyc_user_id ON kyc_verifications (user_id);
CREATE INDEX idx_kyc_decision ON kyc_verifications (final_decision);
CREATE INDEX idx_kyc_user_attempt
    ON kyc_verifications (user_id, attempt_number);