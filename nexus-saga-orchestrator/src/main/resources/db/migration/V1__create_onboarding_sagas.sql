CREATE TABLE onboarding_sagas (
    saga_id                 UUID        NOT NULL,
    user_id                 UUID        NOT NULL,
    email                   VARCHAR(255),
    current_step            VARCHAR(50) NOT NULL,
    failure_reason          TEXT,
    failure_type            VARCHAR(50),
    language                VARCHAR(5)  NOT NULL DEFAULT 'es',
    checking_account_id     UUID,
    savings_account_id      UUID,
    failure_explanation     JSONB,
    attempt_count           INTEGER     NOT NULL DEFAULT 0,
    started_at              TIMESTAMPTZ NOT NULL,
    completed_at            TIMESTAMPTZ,
    expires_at              TIMESTAMPTZ NOT NULL,
    last_updated_at         TIMESTAMPTZ NOT NULL,
    version                 INTEGER     NOT NULL DEFAULT 0,

    CONSTRAINT pk_onboarding_sagas PRIMARY KEY (saga_id)
);

CREATE UNIQUE INDEX uq_onboarding_sagas_user
    ON onboarding_sagas (user_id)
    WHERE completed_at IS NULL;

CREATE INDEX idx_onboarding_step
    ON onboarding_sagas (current_step);
CREATE INDEX idx_onboarding_expires
    ON onboarding_sagas (expires_at)
    WHERE completed_at IS NULL;