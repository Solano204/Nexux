ALTER TABLE saga_timeouts DROP COLUMN IF EXISTS timeout_at;
ALTER TABLE saga_timeouts DROP COLUMN IF EXISTS triggered_at;
ALTER TABLE saga_timeouts DROP COLUMN IF EXISTS cancelled_at;
