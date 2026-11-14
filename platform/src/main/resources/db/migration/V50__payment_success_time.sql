-- The first successful payment transition is immutable; later refunds must not change its latency.
ALTER TABLE payments ADD COLUMN succeeded_at TIMESTAMP(6) NULL;
