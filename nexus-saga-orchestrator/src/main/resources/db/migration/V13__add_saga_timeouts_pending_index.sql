-- The V4 partial index (idx_saga_timeouts_pending) was built against
-- timeout_at/triggered_at/cancelled_at, all dropped in V7 when the schema
-- was renamed to fires_at/is_cancelled/fired_at (V8) — PostgreSQL drops an
-- index when a column it depends on is dropped, so the every-5-second poll
-- in SagaTimeoutMonitor.checkTimeouts() (WHERE fires_at < now() AND
-- is_cancelled = false AND fired_at IS NULL) has been running as a full
-- table scan since V7/V8, with no migration ever restoring the index for
-- the new column names.
CREATE INDEX idx_saga_timeouts_pending_v2
    ON saga_timeouts (fires_at)
    WHERE is_cancelled = false AND fired_at IS NULL;
