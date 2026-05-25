-- ══════════════════════════════════════════════════════════════
-- TRANSACTIONS TABLE — 16-state lifecycle
--
-- The definitive record of every financial transaction.
-- Append-only in practice (status only moves forward).
-- All monetary values in DECIMAL(20,4) — NEVER float.
--
-- State machine transitions:
-- INITIATED → FRAUD_CHECKING → FRAUD_CLEARED/FRAUD_REJECTED
-- FRAUD_CLEARED → BALANCE_RESERVING → BALANCE_RESERVED/RESERVE_FAILED
-- BALANCE_RESERVED → LEDGER_POSTING → LEDGER_POSTED/LEDGER_FAILED
-- LEDGER_POSTED → COMPLETING → COMPLETED
-- Any state → FAILED / REVERSED / CANCELLED
-- ══════════════════════════════════════════════════════════════

CREATE TABLE transactions (
    transaction_id          UUID            NOT NULL,
    idempotency_key         VARCHAR(64)     NOT NULL,
    user_id                 UUID            NOT NULL,
    source_account_id       UUID            NOT NULL,
    target_account_id       UUID,
    target_account_number   VARCHAR(19),
    target_user_id          UUID,

    -- Financial fields
    amount                  DECIMAL(20, 4)  NOT NULL
                            CHECK (amount > 0),
    currency                CHAR(3)         NOT NULL DEFAULT 'MXN',
    exchange_rate           DECIMAL(10, 6)  NOT NULL DEFAULT 1.000000,
    fee_amount              DECIMAL(20, 4)  NOT NULL DEFAULT 0.0000,
    net_amount              DECIMAL(20, 4)
                            GENERATED ALWAYS AS (amount - fee_amount) STORED,

    -- Transaction metadata
    transaction_type        VARCHAR(30)     NOT NULL
                            CHECK (transaction_type IN (
                                'INTERNAL_TRANSFER', 'EXTERNAL_TRANSFER',
                                'PAYMENT', 'DIRECT_DEPOSIT',
                                'CASH_IN', 'CASH_OUT',
                                'FEE', 'INTEREST', 'REVERSAL')),
    channel                 VARCHAR(20)     NOT NULL DEFAULT 'API'
                            CHECK (channel IN (
                                'API', 'MOBILE', 'WEB',
                                'ATPM', 'BRANCH', 'BATCH')),
    description             VARCHAR(500),
    merchant_name           VARCHAR(200),
    merchant_category_code  VARCHAR(4),
    reference_number        VARCHAR(100),

    -- 16-state lifecycle
    status                  VARCHAR(30)     NOT NULL DEFAULT 'INITIATED'
                            CHECK (status IN (
                                'INITIATED',
                                'FRAUD_CHECKING',
                                'FRAUD_CLEARED',
                                'FRAUD_REJECTED',
                                'BALANCE_RESERVING',
                                'BALANCE_RESERVED',
                                'RESERVE_FAILED',
                                'LEDGER_POSTING',
                                'LEDGER_POSTED',
                                'LEDGER_FAILED',
                                'COMPLETING',
                                'COMPLETED',
                                'FAILED',
                                'REVERSING',
                                'REVERSED',
                                'CANCELLED')),

    -- Fraud intelligence
    fraud_score             DECIMAL(5, 4),
    fraud_decision          VARCHAR(20)
                            CHECK (fraud_decision IN (
                                'CLEARED', 'REJECTED',
                                'MANUAL_REVIEW', NULL)),
    fraud_reasons           TEXT[],
    fraud_checked_at        TIMESTAMPTZ,

    -- Ledger reference
    ledger_entry_id         UUID,
    ledger_posted_at        TIMESTAMPTZ,

    -- Saga tracking
    saga_id                 UUID,
    saga_step               VARCHAR(50),

    -- IP and device context (for fraud)
    ip_address              INET,
    device_fingerprint      VARCHAR(255),
    user_agent              TEXT,

    -- Timing
    initiated_at            TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    fraud_check_started_at  TIMESTAMPTZ,
    balance_reserved_at     TIMESTAMPTZ,
    ledger_posted_at_ts     TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ,
    failed_at               TIMESTAMPTZ,
    failure_reason          TEXT,

    -- Idempotency + optimistic lock
    version                 INTEGER         NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_transactions PRIMARY KEY (transaction_id)
);

-- Idempotency: one transaction per idempotency key per user
CREATE UNIQUE INDEX uq_transactions_idempotency
    ON transactions (user_id, idempotency_key);

CREATE INDEX idx_transactions_user ON transactions (user_id);
CREATE INDEX idx_transactions_source_account
    ON transactions (source_account_id);
CREATE INDEX idx_transactions_status ON transactions (status);
CREATE INDEX idx_transactions_initiated
    ON transactions (initiated_at DESC);
CREATE INDEX idx_transactions_saga ON transactions (saga_id)
    WHERE saga_id IS NOT NULL;
CREATE INDEX idx_transactions_user_status
    ON transactions (user_id, status, initiated_at DESC);

-- State transition validation trigger
CREATE OR REPLACE FUNCTION validate_transaction_state_transition()
RETURNS TRIGGER AS $$
DECLARE
    valid_transitions TEXT[][] := ARRAY[
        ARRAY['INITIATED', 'FRAUD_CHECKING'],
        ARRAY['INITIATED', 'CANCELLED'],
        ARRAY['FRAUD_CHECKING', 'FRAUD_CLEARED'],
        ARRAY['FRAUD_CHECKING', 'FRAUD_REJECTED'],
        ARRAY['FRAUD_CLEARED', 'BALANCE_RESERVING'],
        ARRAY['BALANCE_RESERVING', 'BALANCE_RESERVED'],
        ARRAY['BALANCE_RESERVING', 'RESERVE_FAILED'],
        ARRAY['BALANCE_RESERVED', 'LEDGER_POSTING'],
        ARRAY['LEDGER_POSTING', 'LEDGER_POSTED'],
        ARRAY['LEDGER_POSTING', 'LEDGER_FAILED'],
        ARRAY['LEDGER_POSTED', 'COMPLETING'],
        ARRAY['COMPLETING', 'COMPLETED'],
        ARRAY['RESERVE_FAILED', 'FAILED'],
        ARRAY['LEDGER_FAILED', 'REVERSING'],
        ARRAY['REVERSING', 'REVERSED'],
        ARRAY['FRAUD_REJECTED', 'FAILED']
    ];
    transition TEXT[];
    is_valid BOOLEAN := FALSE;
BEGIN
    IF OLD.status = NEW.status THEN
        RETURN NEW; -- No change, always valid
    END IF;

    FOREACH transition SLICE 1 IN ARRAY valid_transitions LOOP
        IF transition[1] = OLD.status
                AND transition[2] = NEW.status THEN
            is_valid := TRUE;
            EXIT;
        END IF;
    END LOOP;

    IF NOT is_valid THEN
        RAISE EXCEPTION
            'Invalid transaction state transition: % → % for txn %',
            OLD.status, NEW.status, OLD.transaction_id;
    END IF;

    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_validate_state_transition
    BEFORE UPDATE ON transactions
    FOR EACH ROW
    WHEN (OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION validate_transaction_state_transition();

CREATE OR REPLACE FUNCTION transactions_update_updated_at()
RETURNS TRIGGER AS $$
BEGIN NEW.updated_at = NOW(); RETURN NEW; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_transactions_updated_at
    BEFORE UPDATE ON transactions
    FOR EACH ROW EXECUTE FUNCTION transactions_update_updated_at();