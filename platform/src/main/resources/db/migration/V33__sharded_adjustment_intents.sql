CREATE TABLE sharded_adjustment_intents (
 adjustment_id VARCHAR(64) PRIMARY KEY, proposal_version BIGINT NOT NULL,
 payment_id VARCHAR(64) NOT NULL, physical_shard TINYINT NOT NULL, route_epoch BIGINT NOT NULL,
 digest VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL, journal_id VARCHAR(64),
 created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT ck_shard_adjustment_status CHECK(status IN ('PREPARED','FINALIZED'))
);
