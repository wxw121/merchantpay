-- Target-local checkpoints. Each cursor advances with the copied rows and
-- generated-ID mappings in the same target transaction.
CREATE TABLE v3_shard_copy_batch_plans (
 plan_id VARCHAR(36) PRIMARY KEY,
 bucket TINYINT NOT NULL,
 source_epoch BIGINT NOT NULL,
 plan_version BIGINT NOT NULL,
 source_hash VARCHAR(64) NOT NULL,
 copy_reason VARCHAR(1000) NOT NULL,
 discard_reason VARCHAR(1000),
 state VARCHAR(16) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT ck_v3_batch_state CHECK(
  (state IN ('COPYING','VERIFIED') AND discard_reason IS NULL)
  OR (state IN ('DISCARDING','DISCARDED') AND discard_reason IS NOT NULL))
);
CREATE TABLE v3_shard_copy_batch_tables (
 plan_id VARCHAR(36) NOT NULL,
 table_name VARCHAR(64) NOT NULL,
 source_count BIGINT NOT NULL,
 source_hash VARCHAR(64) NOT NULL,
 copied_count BIGINT NOT NULL DEFAULT 0,
 cursor_a VARCHAR(160),
 cursor_b VARCHAR(160),
 remap_id BOOLEAN NOT NULL DEFAULT FALSE,
 complete BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(plan_id,table_name),
 CONSTRAINT ck_v3_batch_counts CHECK(source_count>=0 AND copied_count>=0 AND copied_count<=source_count)
);
