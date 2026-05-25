-- ══════════════════════════════════════════════════════════════
-- PASSWORD HISTORY TABLE — Prevents reuse of last 5 passwords
-- Financial security requirement
-- ══════════════════════════════════════════════════════════════

CREATE TABLE password_history (
    history_id      UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id         UUID        NOT NULL,
    password_hash   VARCHAR(72) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_password_history PRIMARY KEY (history_id),
    CONSTRAINT fk_pw_history_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_pw_history_user ON password_history (user_id, created_at DESC);