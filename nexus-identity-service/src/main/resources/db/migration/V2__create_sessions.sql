-- ══════════════════════════════════════════════════════════════
-- SESSIONS TABLE — Active JWT sessions
-- One row per issued JWT. Multiple sessions per user supported
-- (multiple devices / browsers)
-- ══════════════════════════════════════════════════════════════

CREATE TABLE sessions (
    session_id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id                 UUID        NOT NULL,
    jti                     UUID        NOT NULL,   -- JWT ID claim (unique per token)
    device_fingerprint      VARCHAR(255),
    ip_address              INET,
    user_agent              TEXT,
    issued_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at              TIMESTAMPTZ NOT NULL,
    last_activity_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    is_active               BOOLEAN     NOT NULL DEFAULT TRUE,
    refresh_token_hash      VARCHAR(72),            -- BCrypt hash of refresh token

    CONSTRAINT pk_sessions PRIMARY KEY (session_id),
    CONSTRAINT fk_sessions_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
        ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_sessions_jti ON sessions (jti);
CREATE INDEX idx_sessions_user_id ON sessions (user_id);
CREATE INDEX idx_sessions_expires ON sessions (expires_at);
CREATE INDEX idx_sessions_active ON sessions (user_id, is_active)
    WHERE is_active = TRUE;