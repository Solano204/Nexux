-- ══════════════════════════════════════════════════════════════
-- POSTINGS TABLE — Atomic double-entry unit
--
-- Every posting must balance: total_debit = total_credit
-- entry_count must be even (minimum 2 entries per posting)
-- PostgreSQL CHECK constraint enforces balance at INSERT time
-- ══════════════════════════════════════════════════════════════

CREATE TABLE postings (
    posting_id          UUID        NOT NULL,
    transaction_id      UUID,
    posting_type        VARCHAR(50) NOT NULL
                        CHECK (posting_type IN (
                            'TRANSFER', 'PAYMENT', 'FEE',
                            'INTEREST_PAYMENT', 'INTEREST_ACCRUAL',
                            'REVERSAL', 'INITIAL_BALANCE',
                            'ADJUSTMENT', 'REGULATORY_HOLD')),
    status              VARCHAR(20) NOT NULL DEFAULT 'POSTED'
                        CHECK (status IN (
                            'PENDING', 'POSTED',
                            'REVERSED', 'PARTIAL_REVERSE')),
    entry_count         INTEGER     NOT NULL
                        CHECK (entry_count > 0 AND
                               entry_count % 2 = 0),
    total_debit         DECIMAL(20, 4) NOT NULL CHECK (total_debit > 0),
    total_credit        DECIMAL(20, 4) NOT NULL CHECK (total_credit > 0),

    -- The most important constraint in the entire service:
    -- double-entry balance invariant enforced at the database level
    is_balanced         BOOLEAN     NOT NULL,
    CONSTRAINT chk_posting_balanced
        CHECK (total_debit = total_credit AND is_balanced = TRUE),

    currency            VARCHAR(3)  NOT NULL,
    description         VARCHAR(500),
    posted_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    posted_by_service   VARCHAR(50) NOT NULL DEFAULT 'ledger-service',
    trace_id            VARCHAR(32),

    CONSTRAINT pk_postings PRIMARY KEY (posting_id)
);

CREATE UNIQUE INDEX uq_postings_transaction
    ON postings (transaction_id)
    WHERE transaction_id IS NOT NULL;

CREATE INDEX idx_postings_status ON postings (status);
CREATE INDEX idx_postings_type ON postings (posting_type);
CREATE INDEX idx_postings_posted ON postings (posted_at DESC);