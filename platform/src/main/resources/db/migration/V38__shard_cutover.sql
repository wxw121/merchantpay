-- Route and target fence live in different databases. ROUTED is a durable,
-- unavailable intermediate state; the target is activated only after route commit.
ALTER TABLE v3_shard_migrations DROP CONSTRAINT ck_v3_migration_open;
ALTER TABLE v3_shard_migrations DROP CONSTRAINT ck_v3_migration_state;
ALTER TABLE v3_shard_migrations ADD CONSTRAINT ck_v3_migration_state
 CHECK(state IN ('PLANNED','ROUTED','MOVED','CANCELLED'));
ALTER TABLE v3_shard_migrations ADD CONSTRAINT ck_v3_migration_open CHECK(
 (state IN ('PLANNED','ROUTED') AND open_bucket IS NOT NULL AND open_bucket=bucket)
 OR (state IN ('MOVED','CANCELLED') AND open_bucket IS NULL)
);
CREATE TABLE v3_shard_cutovers (
 plan_id VARCHAR(36) PRIMARY KEY, bucket INT NOT NULL,
 source_epoch BIGINT NOT NULL, target_epoch BIGINT NOT NULL,
 target_shard INT NOT NULL, plan_version BIGINT NOT NULL,
 manifest_hash VARCHAR(64) NOT NULL, state VARCHAR(16) NOT NULL,
 initiated_by VARCHAR(160) NOT NULL, reason VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
 activated_at TIMESTAMP(6),
 CONSTRAINT fk_v3_cutover_copy FOREIGN KEY(plan_id) REFERENCES v3_shard_copy_runs(plan_id),
 CONSTRAINT ck_v3_cutover_bucket CHECK(bucket BETWEEN 0 AND 15),
 CONSTRAINT ck_v3_cutover_epoch CHECK(source_epoch>0 AND target_epoch=source_epoch+1),
 CONSTRAINT ck_v3_cutover_target CHECK(target_shard BETWEEN 1 AND 3),
 CONSTRAINT ck_v3_cutover_version CHECK(plan_version>0),
 CONSTRAINT ck_v3_cutover_state CHECK(
   (state IN ('PREPARING','ROUTED') AND activated_at IS NULL)
   OR (state='ACTIVE' AND activated_at IS NOT NULL))
);
CREATE INDEX ix_v3_cutover_state ON v3_shard_cutovers(state,bucket);
