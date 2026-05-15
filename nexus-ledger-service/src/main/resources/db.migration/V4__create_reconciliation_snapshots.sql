CREATE TABLE ledger_reconciliation_snapshots (
    snapshot_id             UUID        NOT NULL DEFAULT gen_random_uuid(),
    snapshot_date           DATE        NOT NULL,
    account_id              UUID        NOT NULL,
    balance_at_snapshot     DECIMAL(20, 4) NOT NULL,
    entry_number_at_snapshot BIGINT     NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    verified                BOOLEAN     NOT NULL DEFAULT FALSE,

    CONSTRAINT pk_reconciliation_snapshots
        PRIMARY KEY (snapshot_id)
);

CREATE UNIQUE INDEX uq_snapshot_account_date
    ON ledger_reconciliation_snapshots (account_id, snapshot_date);

CREATE TABLE ledger_reconciliation_failures (
    failure_id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    account_id          UUID        NOT NULL,
    ledger_balance      DECIMAL(20, 4) NOT NULL,
    account_balance     DECIMAL(20, 4) NOT NULL,
    discrepancy         DECIMAL(20, 4) NOT NULL,
    detected_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at         TIMESTAMPTZ,
    resolution_notes    TEXT,

    CONSTRAINT pk_reconciliation_failures
        PRIMARY KEY (failure_id)
);