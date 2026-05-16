-- ══════════════════════════════════════════════════════════════
-- RISK PROFILES — complete history retained for every user.
-- Not just the current score: every historical computation is kept.
-- Enables: trend analysis, regulatory audit, model validation.
-- ══════════════════════════════════════════════════════════════

CREATE TABLE risk_profiles (
    profile_id                  UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id                     UUID        NOT NULL,
    computed_at                 TIMESTAMPTZ NOT NULL,
    valid_until                 TIMESTAMPTZ NOT NULL,
    version                     INTEGER     NOT NULL DEFAULT 1,

    -- Overall scores
    overall_risk_score          INTEGER     NOT NULL
                                CHECK (overall_risk_score BETWEEN 0 AND 100),
    risk_tier                   VARCHAR(20) NOT NULL
                                CHECK (risk_tier IN ('VERY_LOW', 'LOW',
                                    'MEDIUM', 'HIGH', 'VERY_HIGH')),
    confidence_level            DECIMAL(4,3) NOT NULL
                                CHECK (confidence_level BETWEEN 0 AND 1),

    -- Full component scores (JSONB: flexible, no schema migration on model changes)
    credit_risk                 JSONB       NOT NULL,
    behavioral_risk             JSONB       NOT NULL,
    compliance_risk             JSONB       NOT NULL,
    velocity_profile            JSONB       NOT NULL,
    behavioral_profile          JSONB       NOT NULL,

    -- Agent metadata
    model_version               VARCHAR(50),
    agent_plan_summary          TEXT,
    tools_used                  TEXT[],
    data_gaps                   TEXT[],
    scoring_warnings            TEXT[],
    regulatory_classification   VARCHAR(20)
                                CHECK (regulatory_classification IN (
                                    'LOW_RISK','MEDIUM_RISK',
                                    'HIGH_RISK','CRITICAL')),

    -- Computation provenance
    computation_started_at      TIMESTAMPTZ,
    computation_completed_at    TIMESTAMPTZ,
    computation_duration_ms     INTEGER,
    triggered_by                VARCHAR(30)
                                CHECK (triggered_by IN (
                                    'SCHEDULED','EVENT_TRIGGERED','MANUAL')),
    trigger_event_id            UUID,

    CONSTRAINT pk_risk_profiles PRIMARY KEY (profile_id),
    CONSTRAINT uq_user_version UNIQUE (user_id, version)
);

-- Primary lookup: current valid profile for a user
CREATE INDEX idx_risk_profiles_user_valid
    ON risk_profiles (user_id, valid_until DESC)
    WHERE valid_until > NOW();

-- Monitoring: find users still needing recomputation
CREATE INDEX idx_risk_profiles_tier
    ON risk_profiles (risk_tier);

-- Trend analysis: chronological profile history per user
CREATE INDEX idx_risk_profiles_history
    ON risk_profiles (user_id, computed_at DESC);