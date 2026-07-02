CREATE TABLE saga_timeouts (
    timeout_id      UUID        NOT NULL DEFAULT gen_random_uuid(),
    saga_id         UUID        NOT NULL,
    saga_type       VARCHAR(20) NOT NULL
                    CHECK (saga_type IN ('TRANSFER','ONBOARDING')),
    expected_step   VARCHAR(50) NOT NULL,
    timeout_at      TIMESTAMPTZ NOT NULL,
    triggered_at    TIMESTAMPTZ,
    cancelled_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_saga_timeouts PRIMARY KEY (timeout_id)
);

CREATE INDEX idx_saga_timeouts_pending
    ON saga_timeouts (timeout_at)
    WHERE triggered_at IS NULL AND cancelled_at IS NULL;

CREATE INDEX idx_saga_timeouts_saga_id
    ON saga_timeouts (saga_id);
