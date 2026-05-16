CREATE TABLE risk_computation_jobs (
    job_id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    job_type            VARCHAR(30) NOT NULL
                        CHECK (job_type IN (
                            'NIGHTLY_BATCH','EVENT_TRIGGERED','MANUAL')),
    triggered_by        VARCHAR(100),
    started_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMPTZ,
    users_scheduled     INTEGER,
    users_completed     INTEGER     NOT NULL DEFAULT 0,
    users_failed        INTEGER     NOT NULL DEFAULT 0,
    users_skipped       INTEGER     NOT NULL DEFAULT 0,
    status              VARCHAR(20) NOT NULL DEFAULT 'RUNNING'
                        CHECK (status IN (
                            'RUNNING','COMPLETED','FAILED','PARTIAL')),
    error_details       JSONB,

    CONSTRAINT pk_risk_computation_jobs PRIMARY KEY (job_id)
);

CREATE INDEX idx_risk_jobs_status
    ON risk_computation_jobs (status, started_at DESC)
    WHERE status = 'RUNNING';