-- ══════════════════════════════════════════════════════════════
-- CHART OF ACCOUNTS — Registry of all valid ledger accounts
-- System accounts balance user transactions (contra accounts)
-- ══════════════════════════════════════════════════════════════

CREATE TABLE chart_of_accounts (
    account_id      UUID        NOT NULL,
    account_number  VARCHAR(30) NOT NULL,
    account_name    VARCHAR(200) NOT NULL,
    account_type    VARCHAR(20) NOT NULL
                    CHECK (account_type IN (
                        'ASSET', 'LIABILITY',
                        'EQUITY', 'REVENUE', 'EXPENSE')),
    account_subtype VARCHAR(50) NOT NULL,
    normal_balance  VARCHAR(10) NOT NULL
                    CHECK (normal_balance IN ('DEBIT', 'CREDIT')),
    currency        CHAR(3)     NOT NULL DEFAULT 'MXN',
    is_user_account BOOLEAN     NOT NULL DEFAULT FALSE,
    user_id         UUID,
    is_active       BOOLEAN     NOT NULL DEFAULT TRUE,
    opening_balance DECIMAL(20, 4) NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    closed_at       TIMESTAMPTZ,

    CONSTRAINT pk_chart_of_accounts PRIMARY KEY (account_id)
);

CREATE UNIQUE INDEX uq_coa_number ON chart_of_accounts (account_number);
CREATE INDEX idx_coa_user ON chart_of_accounts (user_id)
    WHERE user_id IS NOT NULL;
CREATE INDEX idx_coa_active ON chart_of_accounts (is_active)
    WHERE is_active = TRUE;

-- ── Insert system (contra) accounts ──────────────────────────
INSERT INTO chart_of_accounts
    (account_id, account_number, account_name,
     account_type, account_subtype, normal_balance,
     is_user_account)
VALUES
    (gen_random_uuid(), 'SYS-FEE-REVENUE',
     'Fee Revenue', 'REVENUE', 'FEE_REVENUE', 'CREDIT', FALSE),
    (gen_random_uuid(), 'SYS-INTEREST-EXPENSE',
     'Interest Expense', 'EXPENSE', 'INTEREST_EXPENSE', 'DEBIT', FALSE),
    (gen_random_uuid(), 'SYS-INTEREST-RECEIVABLE',
     'Interest Receivable', 'ASSET', 'INTEREST_RECEIVABLE', 'DEBIT', FALSE),
    (gen_random_uuid(), 'SYS-TRANSFER-TRANSIT',
     'Transfer in Transit', 'LIABILITY', 'TRANSIT', 'CREDIT', FALSE),
    (gen_random_uuid(), 'SYS-SUSPENSE',
     'Suspense Account', 'LIABILITY', 'SUSPENSE', 'CREDIT', FALSE),
    (gen_random_uuid(), 'SYS-FRAUD-RESERVE',
     'Fraud Reserve', 'LIABILITY', 'FRAUD_RESERVE', 'CREDIT', FALSE),
    (gen_random_uuid(), 'SYS-OPENING-BALANCE',
     'Opening Balance Equity', 'EQUITY', 'OPENING', 'CREDIT', FALSE),
    (gen_random_uuid(), 'SYS-REGULATORY-HOLD',
     'Regulatory Hold', 'LIABILITY', 'REGULATORY', 'CREDIT', FALSE);