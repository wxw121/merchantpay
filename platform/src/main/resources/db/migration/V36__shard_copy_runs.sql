-- A completed target copy is recorded only after its transaction commits and its
-- per-table fingerprints match the stopped source manifest. Route stays on source.
CREATE TABLE v3_shard_copy_runs (
 plan_id VARCHAR(36) PRIMARY KEY, bucket INT NOT NULL, source_epoch BIGINT NOT NULL,
 plan_version BIGINT NOT NULL, manifest_hash VARCHAR(64) NOT NULL,
 target_hash VARCHAR(64) NOT NULL, total_rows BIGINT NOT NULL,
 copied_by VARCHAR(160) NOT NULL, reason VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_v3_copy_manifest FOREIGN KEY(plan_id) REFERENCES v3_shard_source_manifests(plan_id),
 CONSTRAINT ck_v3_copy_bucket CHECK(bucket BETWEEN 0 AND 15),
 CONSTRAINT ck_v3_copy_versions CHECK(source_epoch>0 AND plan_version>0),
 CONSTRAINT ck_v3_copy_rows CHECK(total_rows>=0)
);
