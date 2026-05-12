-- ══════════════════════════════════════════════════════════════
-- BALANCE RESERVATIONS TABLE
-- Audit trail for in-flight SAGA operations.
-- Unique constraint on (account_id, transaction_id) ensures
-- idempotent reservation handling for Kafka at-least-once delivery.
-- ══════════════════════════════════════════════════════════════

CREATE TABLE balance_reservations (
    reservation_id      UUID            NOT NULL DEFAULT gen_random_uuid(),
    account_id          UUID            NOT NULL,
    transaction_id      UUID            NOT NULL,
    reserved_amount     DECIMAL(20, 4)  NOT NULL,
    status              VARCHAR(25)     NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN (
                            'ACTIVE', 'RELEASED', 'FINALIZED',
                            'RELEASED_BY_EXPIRY')),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    expires_at          TIMESTAMPTZ     NOT NULL
                            DEFAULT (NOW() + INTERVAL '24 hours'),
    finalized_at        TIMESTAMPTZ,
    released_at         TIMESTAMPTZ,

    CONSTRAINT pk_balance_reservations PRIMARY KEY (reservation_id),
    CONSTRAINT fk_reservation_account
        FOREIGN KEY (account_id) REFERENCES accounts(account_id),

    -- IDEMPOTENCY: one active reservation per transaction per account
    CONSTRAINT uq_active_reservation
        UNIQUE (account_id, transaction_id)
);

CREATE INDEX idx_reservations_account ON balance_reservations (account_id);
CREATE INDEX idx_reservations_status ON balance_reservations (status);
CREATE INDEX idx_reservations_expires ON balance_reservations (expires_at)
    WHERE status = 'ACTIVE';
CREATE INDEX idx_reservations_transaction
    ON balance_reservations (transaction_id);