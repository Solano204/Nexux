-- ══════════════════════════════════════════════════════════════
-- FRAUD DECISIONS TABLE — Immutable compliance audit record
--
-- Every AI fraud decision is permanently recorded here.
-- Full reasoning chain, tool call history, policy citations.
-- Regulators can audit every decision.
-- Never updated (except review fields) — regulatory requirement.
-- ══════════════════════════════════════════════════════════════

CREATE TABLE fraud_decisions (
    decision_id         UUID        NOT NULL DEFAULT gen_random_uuid(),
    transaction_id      UUID        NOT NULL,
    user_id             UUID        NOT NULL,
    source_account_id   UUID        NOT NULL,
    target_account_id   UUID,
    amount              DECIMAL(20, 4) NOT NULL,
    currency            CHAR(3)     NOT NULL DEFAULT 'MXN',
    transaction_type    VARCHAR(30) NOT NULL,

    -- Decision outcome
    decision_outcome    VARCHAR(20) NOT NULL
                        CHECK (decision_outcome IN (
                            'APPROVE', 'REVIEW', 'REJECT')),
    risk_score          DECIMAL(5, 2) NOT NULL
                        CHECK (risk_score BETWEEN 0 AND 100),
    confidence_level    DECIMAL(4, 3)
                        CHECK (confidence_level BETWEEN 0 AND 1),
    recommended_action  VARCHAR(50) NOT NULL,
    review_priority     VARCHAR(10)
                        CHECK (review_priority IN (
                            'HIGH', 'MEDIUM', 'LOW', NULL)),

    -- AI reasoning — JSONB for rich structured data
    triggering_factors  JSONB       NOT NULL DEFAULT '[]',
    clearing_factors    JSONB       NOT NULL DEFAULT '[]',
    reasoning           TEXT,
    policy_citations    JSONB       NOT NULL DEFAULT '[]',
    tool_call_summary   JSONB       NOT NULL DEFAULT '[]',
    raw_signals         JSONB       NOT NULL DEFAULT '{}',
    analysis_plan       JSONB,
    tools_called        TEXT[]      NOT NULL DEFAULT '{}',

    -- Timing
    analysis_started_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    analysis_completed_at   TIMESTAMPTZ,
    analysis_time_ms        INTEGER,

    -- AI model provenance (for reproducibility audits)
    model_used          VARCHAR(50),
    model_version       VARCHAR(20),
    spring_ai_version   VARCHAR(20),
    trace_id            VARCHAR(32),

    -- Manual review fields (updatable — compliance officer fills these)
    reviewed_by         UUID,
    review_outcome      VARCHAR(20)
                        CHECK (review_outcome IN (
                            'CONFIRMED_FRAUD', 'CLEARED',
                            'ESCALATED', NULL)),
    review_notes        TEXT,
    reviewed_at         TIMESTAMPTZ,

    -- SAR (Suspicious Activity Report) tracking
    sar_filed           BOOLEAN     NOT NULL DEFAULT FALSE,
    sar_filed_at        TIMESTAMPTZ,
    sar_reference       VARCHAR(100),

    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_fraud_decisions PRIMARY KEY (decision_id)
);

-- One decision per transaction (idempotency)
CREATE UNIQUE INDEX uq_fraud_decisions_transaction
    ON fraud_decisions (transaction_id);

CREATE INDEX idx_fraud_decisions_user
    ON fraud_decisions (user_id);
CREATE INDEX idx_fraud_decisions_outcome
    ON fraud_decisions (decision_outcome);
CREATE INDEX idx_fraud_decisions_risk_score
    ON fraud_decisions (risk_score DESC);
CREATE INDEX idx_fraud_decisions_created
    ON fraud_decisions (analysis_completed_at DESC);
CREATE INDEX idx_fraud_decisions_user_created
    ON fraud_decisions (user_id, analysis_completed_at DESC);
CREATE INDEX idx_fraud_decisions_review_pending
    ON fraud_decisions (review_priority, analysis_completed_at DESC)
    WHERE decision_outcome = 'REVIEW' AND reviewed_at IS NULL;
CREATE INDEX idx_fraud_decisions_sar
    ON fraud_decisions (sar_filed_at)
    WHERE sar_filed = TRUE;