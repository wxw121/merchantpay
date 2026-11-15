-- Core-origin bucket isolation is atomic with the plan, route and source fence.
-- No data copy or route switch is enabled by this table.
CREATE TABLE v3_shard_isolations (
 plan_id VARCHAR(36) PRIMARY KEY, bucket INT NOT NULL, source_epoch BIGINT NOT NULL,
 plan_version BIGINT NOT NULL, state VARCHAR(16) NOT NULL,
 isolated_by VARCHAR(160) NOT NULL, isolation_reason VARCHAR(1000) NOT NULL,
 released_by VARCHAR(160), release_reason VARCHAR(1000),
 created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_v3_isolation_plan FOREIGN KEY(plan_id) REFERENCES v3_shard_migrations(id),
 CONSTRAINT ck_v3_isolation_bucket CHECK(bucket BETWEEN 0 AND 15),
 CONSTRAINT ck_v3_isolation_epoch CHECK(source_epoch>0 AND plan_version>0),
 CONSTRAINT ck_v3_isolation_state CHECK(state IN ('QUIESCED','RELEASED')),
 CONSTRAINT ck_v3_isolation_release CHECK(
   (state='QUIESCED' AND released_by IS NULL AND release_reason IS NULL)
   OR (state='RELEASED' AND released_by IS NOT NULL AND release_reason IS NOT NULL))
);
CREATE INDEX ix_v3_isolation_bucket ON v3_shard_isolations(bucket,state);
