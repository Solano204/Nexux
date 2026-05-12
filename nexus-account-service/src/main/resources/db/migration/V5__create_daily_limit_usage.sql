-- ══════════════════════════════════════════════════════════════
-- DAILY LIMIT USAGE — Separate table for efficient batch resets
-- One row per account per day.
-- Midnight job deletes previous days — more efficient than
-- updating millions of account rows.
-- ══════════════════════════════════════════════════════════════

CREATE TABLE daily_limit_usage (
    account_id          UUID        NOT NULL,
    usage_date          DATE        NOT NULL DEFAULT CURRENT_DATE,
    amount_used         DECIMAL(20, 4) NOT NULL DEFAULT 0,
    transaction_count   INTEGER     NOT NULL DEFAULT 0,
    last_updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_daily_limit_usage
        PRIMARY KEY (account_id, usage_date),
    CONSTRAINT fk_daily_limit_account
        FOREIGN KEY (account_id) REFERENCES accounts(account_id)
);

CREATE INDEX idx_daily_limit_date ON daily_limit_usage (usage_date);