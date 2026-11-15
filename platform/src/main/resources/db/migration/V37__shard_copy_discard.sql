-- A verified copy may be discarded only while the source route remains fenced.
-- A crash after target deletion but before this state update is recovered by retry.
ALTER TABLE v3_shard_copy_runs ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'VERIFIED';
ALTER TABLE v3_shard_copy_runs ADD COLUMN discarded_by VARCHAR(160);
ALTER TABLE v3_shard_copy_runs ADD COLUMN discard_reason VARCHAR(1000);
ALTER TABLE v3_shard_copy_runs ADD COLUMN discarded_at TIMESTAMP(6);
ALTER TABLE v3_shard_copy_runs ADD CONSTRAINT ck_v3_copy_state CHECK(
 (state='VERIFIED' AND discarded_by IS NULL AND discard_reason IS NULL AND discarded_at IS NULL)
 OR (state='DISCARDED' AND discarded_by IS NOT NULL AND discard_reason IS NOT NULL
     AND discarded_at IS NOT NULL)
);
