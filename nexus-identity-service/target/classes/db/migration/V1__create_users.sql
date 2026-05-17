-- ══════════════════════════════════════════════════════════════
-- USERS TABLE — Core identity table
-- UUID v7 (time-sortable) for chronological B-tree locality
-- Soft-delete: deleted_at instead of hard DELETE
-- Regulatory requirement: 7-year retention minimum
-- ══════════════════════════════════════════════════════════════

CREATE TABLE users (
    user_id         UUID            NOT NULL DEFAULT gen_random_uuid(),
    email           VARCHAR(255)    NOT NULL,
    phone_number    VARCHAR(20)     NOT NULL,
    password_hash   VARCHAR(72)     NOT NULL,
    full_name       VARCHAR(200)    NOT NULL,
    date_of_birth   DATE            NOT NULL,
    country         VARCHAR(2)      NOT NULL,

    -- Account lifecycle status
    status          VARCHAR(30)     NOT NULL DEFAULT 'PENDING_KYC'
                    CHECK (status IN (
                        'PENDING_KYC',
                        'KYC_IN_PROGRESS',
                        'ACTIVE',
                        'SUSPENDED',
                        'KYC_REJECTED',
                        'KYC_REJECTED_PERMANENT',
                        'REGISTRATION_CANCELLED',
                        'CLOSED'
                    )),

    -- Roles (PostgreSQL array — supports multiple roles per user)
    roles           TEXT[]          NOT NULL DEFAULT '{"USER"}',

    -- Failed login tracking
    failed_login_attempts   INTEGER     NOT NULL DEFAULT 0,
    lock_until              TIMESTAMPTZ,

    -- KYC tracking
    kyc_verified_at         TIMESTAMPTZ,
    kyc_next_review_at      TIMESTAMPTZ,

    -- Optimistic locking for concurrent update protection
    version         INTEGER         NOT NULL DEFAULT 0,

    -- Audit timestamps
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMPTZ,    -- Soft delete — never hard delete

    CONSTRAINT pk_users PRIMARY KEY (user_id)
);

-- Unique constraints
CREATE UNIQUE INDEX uq_users_email
    ON users (LOWER(email))
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX uq_users_phone
    ON users (phone_number)
    WHERE deleted_at IS NULL;

-- Query optimization indexes
CREATE INDEX idx_users_status ON users (status);
CREATE INDEX idx_users_kyc_next_review ON users (kyc_next_review_at)
    WHERE kyc_next_review_at IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX idx_users_status_kyc ON users (status, kyc_next_review_at)
    WHERE deleted_at IS NULL;

-- Auto-update updated_at on every modification
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();