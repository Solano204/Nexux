-- ══════════════════════════════════════════════════════════════
-- ACCOUNTS TABLE — Core financial account with balance integrity
--
-- Key design decisions:
-- - DECIMAL(20,4) for financial precision — NEVER float/double
-- - total_balance is a GENERATED column (computed, stored)
-- - Three CHECK constraints form the last line of defense against
--   negative balances and accounting inconsistencies
-- - Row-level locking via SELECT FOR UPDATE during SAGA operations
-- ══════════════════════════════════════════════════════════════

CREATE TABLE accounts (
    account_id                  UUID            NOT NULL,
    account_number              VARCHAR(19)     NOT NULL,
    user_id                     UUID            NOT NULL,
    account_type                VARCHAR(20)     NOT NULL
                                CHECK (account_type IN (
                                    'CHECKING', 'SAVINGS', 'INVESTMENT')),
    currency                    CHAR(3)         NOT NULL DEFAULT 'MXN',
    status                      VARCHAR(25)     NOT NULL DEFAULT 'PENDING_ACTIVATION'
                                CHECK (status IN (
                                    'PENDING_ACTIVATION', 'ACTIVE',
                                    'FROZEN', 'CLOSED')),

    -- ── Balance fields — invariant: available >= 0, reserved >= 0
    available_balance           DECIMAL(20, 4)  NOT NULL DEFAULT 0.0000,
    reserved_amount             DECIMAL(20, 4)  NOT NULL DEFAULT 0.0000,
    total_balance               DECIMAL(20, 4)
                                GENERATED ALWAYS AS
                                    (available_balance + reserved_amount)
                                STORED,
    pending_credit              DECIMAL(20, 4)  NOT NULL DEFAULT 0.0000,

    -- ── Transaction limits
    daily_transaction_limit     DECIMAL(20, 4)  NOT NULL DEFAULT 50000.0000,
    daily_transaction_used      DECIMAL(20, 4)  NOT NULL DEFAULT 0.0000,
    daily_reset_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    monthly_transaction_limit   DECIMAL(20, 4)  NOT NULL DEFAULT 500000.0000,
    monthly_transaction_used    DECIMAL(20, 4)  NOT NULL DEFAULT 0.0000,
    minimum_balance             DECIMAL(20, 4)  NOT NULL DEFAULT 0.0000,
    interest_rate               DECIMAL(5, 4)   NOT NULL DEFAULT 0.0000,

    -- ── Lifecycle
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    closed_at                   TIMESTAMPTZ,
    frozen_at                   TIMESTAMPTZ,
    frozen_reason               TEXT,

    -- ── Optimistic lock
    version                     INTEGER         NOT NULL DEFAULT 0,

    CONSTRAINT pk_accounts PRIMARY KEY (account_id),

    -- ── Financial integrity constraints (DATABASE level — last line of defense)
    CONSTRAINT chk_available_balance_non_negative
        CHECK (available_balance >= 0),
    CONSTRAINT chk_reserved_amount_non_negative
        CHECK (reserved_amount >= 0),
    CONSTRAINT chk_daily_used_non_negative
        CHECK (daily_transaction_used >= 0),
    CONSTRAINT chk_monthly_used_non_negative
        CHECK (monthly_transaction_used >= 0)
);

CREATE UNIQUE INDEX uq_accounts_number ON accounts (account_number);
CREATE INDEX idx_accounts_user_id ON accounts (user_id);
CREATE INDEX idx_accounts_status ON accounts (status);
CREATE INDEX idx_accounts_user_status ON accounts (user_id, status)
    WHERE status != 'CLOSED';
CREATE INDEX idx_accounts_daily_reset ON accounts (daily_reset_at);

-- Auto-update updated_at
CREATE OR REPLACE FUNCTION accounts_update_updated_at()
RETURNS TRIGGER AS $$
BEGIN NEW.updated_at = NOW(); RETURN NEW; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_accounts_updated_at
    BEFORE UPDATE ON accounts
    FOR EACH ROW EXECUTE FUNCTION accounts_update_updated_at();