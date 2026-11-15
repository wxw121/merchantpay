-- A stopped-writer source witness; target copying and route cutover remain disabled.
CREATE TABLE v3_shard_source_manifests (
 plan_id VARCHAR(36) PRIMARY KEY, bucket INT NOT NULL, source_epoch BIGINT NOT NULL,
 plan_version BIGINT NOT NULL, table_count INT NOT NULL, manifest_hash VARCHAR(64) NOT NULL,
 recorded_by VARCHAR(160) NOT NULL, reason VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_v3_manifest_isolation FOREIGN KEY(plan_id) REFERENCES v3_shard_isolations(plan_id),
 CONSTRAINT ck_v3_manifest_bucket CHECK(bucket BETWEEN 0 AND 15),
 CONSTRAINT ck_v3_manifest_versions CHECK(source_epoch>0 AND plan_version>0),
 CONSTRAINT ck_v3_manifest_table_count CHECK(table_count>0)
);
CREATE TABLE v3_shard_source_manifest_tables (
 plan_id VARCHAR(36) NOT NULL, table_name VARCHAR(64) NOT NULL,
 source_count BIGINT NOT NULL, source_hash VARCHAR(64) NOT NULL,
 PRIMARY KEY(plan_id,table_name),
 CONSTRAINT fk_v3_manifest_table FOREIGN KEY(plan_id) REFERENCES v3_shard_source_manifests(plan_id),
 CONSTRAINT ck_v3_manifest_count CHECK(source_count>=0)
);
