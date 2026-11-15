-- Reverse moves have a separate durable stage because source fencing commits on
-- a noncore database before the core can safely clean its stale bucket copy.
ALTER TABLE v3_shard_cutovers DROP CONSTRAINT ck_v3_cutover_target;
ALTER TABLE v3_shard_cutovers ADD CONSTRAINT ck_v3_cutover_target
 CHECK(target_shard BETWEEN 0 AND 3);
CREATE TABLE v3_shard_reverse_runs (
 plan_id VARCHAR(36) PRIMARY KEY,
 predecessor_plan_id VARCHAR(36) NOT NULL,
 bucket TINYINT NOT NULL,
 source_shard TINYINT NOT NULL,
 source_epoch BIGINT NOT NULL,
 target_epoch BIGINT NOT NULL,
 plan_version BIGINT NOT NULL,
 state VARCHAR(20) NOT NULL,
 source_hash VARCHAR(64),
 requested_by VARCHAR(160) NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_v3_reverse_plan FOREIGN KEY(plan_id) REFERENCES v3_shard_migrations(id),
 CONSTRAINT fk_v3_reverse_predecessor FOREIGN KEY(predecessor_plan_id)
   REFERENCES v3_shard_cutovers(plan_id),
 CONSTRAINT ck_v3_reverse_state CHECK(state IN
   ('PREPARED','SOURCE_FENCED','CLEANING','COPYING','VERIFIED','ACTIVE')),
 CONSTRAINT ck_v3_reverse_shard CHECK(source_shard BETWEEN 1 AND 3),
 CONSTRAINT ck_v3_reverse_epoch CHECK(source_epoch>1 AND target_epoch=source_epoch+1),
 CONSTRAINT ck_v3_reverse_plan_version CHECK(plan_version>0)
);
CREATE INDEX ix_v3_reverse_bucket_state ON v3_shard_reverse_runs(bucket,state);
