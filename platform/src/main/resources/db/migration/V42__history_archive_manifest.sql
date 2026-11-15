-- Immutable object replicas. The hot payment root, dedup keys and financial
-- source rows remain authoritative until a separately verified tier retirement.
CREATE TABLE v3_history_archives (
 id VARCHAR(36) PRIMARY KEY,
 merchant_id VARCHAR(64) NOT NULL,
 payment_id VARCHAR(64) NOT NULL,
 physical_shard TINYINT NOT NULL,
 route_epoch BIGINT NOT NULL,
 source_hash VARCHAR(64) NOT NULL,
 object_hash VARCHAR(64) NOT NULL,
 object_key VARCHAR(180) NOT NULL,
 fact_count BIGINT NOT NULL,
 object_bytes BIGINT NOT NULL,
 archived_by VARCHAR(160) NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uq_v3_history_source UNIQUE(payment_id,source_hash),
 CONSTRAINT uq_v3_history_object UNIQUE(object_key),
 CONSTRAINT ck_v3_history_counts CHECK(fact_count>0 AND object_bytes>0)
);
CREATE INDEX ix_v3_history_merchant_time ON v3_history_archives(merchant_id,created_at,id);
