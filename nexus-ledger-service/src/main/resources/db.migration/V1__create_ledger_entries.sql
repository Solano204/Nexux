-- ══════════════════════════════════════════════════════════════
-- LEDGER ENTRIES TABLE — The immutable financial truth
--
-- Append-only: PostgreSQL trigger prevents ANY update or delete.
-- SERIALIZABLE isolation in command service prevents concurrent
-- running_balance corruption.
-- Checksum column: SHA-256 of key fields for integrity verification.
-- entry_number: monotonically increasing BIGSERIAL for canonical ordering.
-- running_balance: pre-computed for O(1) current balance queries.
-- ══════════════════════════════════════════════════════════════

CREATE SEQUENCE ledger_entry_seq
    START WITH 1
    INCREMENT BY 1
    NO MAXVALUE
    CACHE 1;

CREATE TABLE ledger_entries (
    entry_id            UUID            NOT NULL,
    entry_number        BIGINT          NOT NULL DEFAULT nextval('ledger_entry_seq'),
    posting_id          UUID            NOT NULL,
    transaction_id      UUID,

    -- Account information (denormalized for query performance)
    account_id          UUID            NOT NULL,
    account_number      VARCHAR(30)     NOT NULL,
    account_type        VARCHAR(20)     NOT NULL,

    -- Double-entry core
    entry_type          VARCHAR(10)     NOT NULL
                        CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount              DECIMAL(20, 4)  NOT NULL
                        CHECK (amount > 0),
    currency            CHAR(3)         NOT NULL
                        CHECK (currency ~ '^[A-Z]{3}$'),

    -- Running balance: computed at insert time, stored for O(1) lookup
    running_balance     DECIMAL(20, 4)  NOT NULL
                        CHECK (running_balance >= 0),

    -- Metadata
    description         VARCHAR(500),
    category            VARCHAR(50)     NOT NULL DEFAULT 'TRANSFER'
                        CHECK (category IN (
                            'TRANSFER', 'PAYMENT', 'FEE',
                            'INTEREST', 'REFUND', 'REVERSAL',
                            'INITIAL_BALANCE', 'REGULATORY_HOLD',
                            'FRAUD_RESERVE', 'ADJUSTMENT')),
    subcategory         VARCHAR(50),

    -- Counterparty (for transfers — helps reconstruct full picture)
    source_account_id   UUID,
    source_account_number VARCHAR(30),

    -- Merchant context (for payment entries)
    merchant_name       VARCHAR(200),
    merchant_category_code VARCHAR(4),

    -- Reversal tracking
    is_reversal         BOOLEAN         NOT NULL DEFAULT FALSE,
    reference_entry_id  UUID,           -- Points to original entry being reversed

    -- Accounting period
    fiscal_year         INTEGER         NOT NULL,
    fiscal_month        INTEGER         NOT NULL
                        CHECK (fiscal_month BETWEEN 1 AND 12),
    fiscal_quarter      INTEGER         NOT NULL
                        CHECK (fiscal_quarter BETWEEN 1 AND 4),

    -- Timestamps
    posted_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    value_date          DATE            NOT NULL DEFAULT CURRENT_DATE,
    effective_date      DATE            NOT NULL DEFAULT CURRENT_DATE,

    -- Provenance
    posted_by_service   VARCHAR(50)     NOT NULL DEFAULT 'ledger-service',
    trace_id            VARCHAR(32),

    -- Integrity: SHA-256 of {entry_id:posting_id:account_id:
    --            entry_type:amount:currency:running_balance:posted_at}
    checksum            VARCHAR(64)     NOT NULL,

    CONSTRAINT pk_ledger_entries PRIMARY KEY (entry_id)
    -- NO FK to postings — entries are inserted in same transaction
    -- before posting record exists
);

-- entry_number must be unique and monotonically increasing
CREATE UNIQUE INDEX uq_ledger_entry_number
    ON ledger_entries (entry_number);

-- Most critical index: current balance + recent history per account
CREATE INDEX idx_ledger_account_entry_number
    ON ledger_entries (account_id, entry_number DESC);

-- Time-range queries (monthly statements)
CREATE INDEX idx_ledger_account_posted
    ON ledger_entries (account_id, posted_at DESC);

-- All entries for a posting (fetch debit + credit together)
CREATE INDEX idx_ledger_posting_id
    ON ledger_entries (posting_id);

-- Fiscal period queries (monthly statements, reporting)
CREATE INDEX idx_ledger_fiscal
    ON ledger_entries (account_id, fiscal_year, fiscal_month);

-- Transaction lookup (partial: most entries have a transaction)
CREATE INDEX idx_ledger_transaction
    ON ledger_entries (transaction_id)
    WHERE transaction_id IS NOT NULL;

-- Reversal lookup
CREATE INDEX idx_ledger_reversals
    ON ledger_entries (is_reversal)
    WHERE is_reversal = TRUE;

-- Category aggregation
CREATE INDEX idx_ledger_category
    ON ledger_entries (account_id, category, posted_at DESC);

-- ══════════════════════════════════════════════════════════════
-- IMMUTABILITY TRIGGER — Two-layer enforcement
-- Layer 1: Application code never calls UPDATE/DELETE on this table
-- Layer 2: This trigger fires even if someone connects directly via psql
-- ══════════════════════════════════════════════════════════════
CREATE OR REPLACE FUNCTION prevent_ledger_modification()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION
            'ledger_entries are immutable: entry_id=% cannot be updated. '
            'Create a reversal entry instead.',
            OLD.entry_id
            USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            'ledger_entries are immutable: entry_id=% cannot be deleted. '
            'Retained for 7-year regulatory compliance.',
            OLD.entry_id
            USING ERRCODE = '55000';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER enforce_ledger_immutability
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_modification();