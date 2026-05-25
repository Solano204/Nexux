CREATE TABLE transfer_sagas (
    saga_id                 UUID        NOT NULL,
    transaction_id          UUID        NOT NULL,
    source_account_id       UUID        NOT NULL,
    target_account_id       UUID        NOT NULL,
    source_user_id          UUID        NOT NULL,
    target_user_id          UUID,
    amount                  DECIMAL(20,4) NOT NULL,
    currency                CHAR(3)     NOT NULL,
    transaction_type        VARCHAR(30) NOT NULL,
    description             VARCHAR(500),
    merchant_name           VARCHAR(200),
    target_name             VARCHAR(200),
    language                VARCHAR(5)  NOT NULL DEFAULT 'es',
    current_step            VARCHAR(50) NOT NULL,

    -- Step 2: balance reservation
    reservation_id          UUID,
    new_available_balance   DECIMAL(20,4),
    funds_reserved          BOOLEAN     NOT NULL DEFAULT FALSE,

    -- Step 3: fraud check
    fraud_score             DECIMAL(5,2),
    fraud_decision          VARCHAR(20),
    fraud_reject_reasons    JSONB,
    review_id               UUID,
    review_priority         VARCHAR(10),

    -- Step 4: ledger posting
    ledger_posting_id       UUID,
    debit_entry_id          UUID,
    credit_entry_id         UUID,

    -- Failure / compensation
    failure_reason          TEXT,
    failure_type            VARCHAR(50),
    failure_explanation     JSONB,
    funds_released          BOOLEAN     NOT NULL DEFAULT FALSE,
    compensation_attempts   INTEGER     NOT NULL DEFAULT 0,

    -- Timing
    started_at              TIMESTAMPTZ NOT NULL,
    completed_at            TIMESTAMPTZ,
    expires_at              TIMESTAMPTZ NOT NULL,
    last_updated_at         TIMESTAMPTZ NOT NULL,
    version                 INTEGER     NOT NULL DEFAULT 0,

    CONSTRAINT pk_transfer_sagas PRIMARY KEY (saga_id)
);

CREATE UNIQUE INDEX uq_transfer_sagas_txn
    ON transfer_sagas (transaction_id);

CREATE INDEX idx_transfer_step
    ON transfer_sagas (current_step);
CREATE INDEX idx_transfer_source_user
    ON transfer_sagas (source_user_id);
CREATE INDEX idx_transfer_expires
    ON transfer_sagas (expires_at)
    WHERE completed_at IS NULL;
CREATE INDEX idx_transfer_review
    ON transfer_sagas (review_id)
    WHERE review_id IS NOT NULL;