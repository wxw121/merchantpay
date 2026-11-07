-- Directory/control-plane metadata only. Every existing bucket stays in the current database.
-- Targets 1..3 are plans, not provisioned databases. No physical routing switch is enabled.
CREATE TABLE v3_physical_shards (
 id INT PRIMARY KEY, name VARCHAR(64) NOT NULL, state VARCHAR(24) NOT NULL,
 CONSTRAINT ck_v3_physical_id CHECK(id BETWEEN 0 AND 3),
 CONSTRAINT ck_v3_physical_state CHECK(state IN ('CURRENT_CORE','UNCONFIGURED'))
);
INSERT INTO v3_physical_shards(id,name,state) VALUES
 (0,'core-0','CURRENT_CORE'),(1,'core-1','UNCONFIGURED'),
 (2,'core-2','UNCONFIGURED'),(3,'core-3','UNCONFIGURED');
CREATE TABLE v3_shard_routes (
 bucket INT PRIMARY KEY, physical_shard INT NOT NULL, epoch BIGINT NOT NULL,
 CONSTRAINT ck_v3_bucket CHECK(bucket BETWEEN 0 AND 15),
 CONSTRAINT ck_v3_route_epoch CHECK(epoch>0),
 CONSTRAINT fk_v3_route_physical FOREIGN KEY(physical_shard) REFERENCES v3_physical_shards(id)
);
INSERT INTO v3_shard_routes(bucket,physical_shard,epoch) VALUES
 (0,0,1),(1,0,1),(2,0,1),(3,0,1),(4,0,1),(5,0,1),(6,0,1),(7,0,1),
 (8,0,1),(9,0,1),(10,0,1),(11,0,1),(12,0,1),(13,0,1),(14,0,1),(15,0,1);
CREATE TABLE v3_shard_migrations (
 id VARCHAR(36) PRIMARY KEY, request_key VARCHAR(64) NOT NULL UNIQUE,
 request_hash VARCHAR(64) NOT NULL, bucket INT NOT NULL, open_bucket INT UNIQUE,
 source_shard INT NOT NULL, target_shard INT NOT NULL, source_epoch BIGINT NOT NULL,
 version BIGINT NOT NULL, state VARCHAR(16) NOT NULL, proposer VARCHAR(160) NOT NULL,
 reason VARCHAR(1000) NOT NULL, cancelled_by VARCHAR(160), cancellation_reason VARCHAR(1000),
 created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_v3_migration_bucket FOREIGN KEY(bucket) REFERENCES v3_shard_routes(bucket),
 CONSTRAINT fk_v3_migration_source FOREIGN KEY(source_shard) REFERENCES v3_physical_shards(id),
 CONSTRAINT fk_v3_migration_target FOREIGN KEY(target_shard) REFERENCES v3_physical_shards(id),
 CONSTRAINT ck_v3_migration_distinct CHECK(source_shard<>target_shard),
 CONSTRAINT ck_v3_migration_versions CHECK(version>0 AND source_epoch>0),
 CONSTRAINT ck_v3_migration_state CHECK(state IN ('PLANNED','CANCELLED')),
 CONSTRAINT ck_v3_migration_open CHECK(
   (state='PLANNED' AND open_bucket IS NOT NULL AND open_bucket=bucket)
   OR (state='CANCELLED' AND open_bucket IS NULL))
);
CREATE INDEX ix_v3_migration_history ON v3_shard_migrations(state,updated_at,id);
