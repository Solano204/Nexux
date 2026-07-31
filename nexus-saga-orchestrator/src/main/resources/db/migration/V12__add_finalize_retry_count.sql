ALTER TABLE transfer_sagas
    ADD COLUMN finalize_retry_count INTEGER NOT NULL DEFAULT 0;
