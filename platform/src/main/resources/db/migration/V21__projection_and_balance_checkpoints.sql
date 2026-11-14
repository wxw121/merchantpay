CREATE TABLE projection_generations (
 id VARCHAR(36) PRIMARY KEY, state VARCHAR(16) NOT NULL,
 scan_cursor VARCHAR(64) NOT NULL DEFAULT '', scan_complete BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP(6) NOT NULL, activated_at TIMESTAMP(6)
);
INSERT INTO projection_generations(id,state,created_at) VALUES ('v2-initial','BUILDING',CURRENT_TIMESTAMP(6));
CREATE TABLE projection_control (
 id INT PRIMARY KEY, active_generation VARCHAR(36), building_generation VARCHAR(36),
 version BIGINT NOT NULL, CONSTRAINT ck_projection_single CHECK(id=1)
);
INSERT INTO projection_control(id,building_generation,version) VALUES (1,'v2-initial',1);
CREATE TABLE projection_counters (
 generation VARCHAR(36) NOT NULL, payment_id VARCHAR(64) NOT NULL, revision BIGINT NOT NULL,
 PRIMARY KEY(generation,payment_id)
);
CREATE TABLE projection_receipts (
 event_id VARCHAR(160) PRIMARY KEY, payload_hash VARCHAR(64) NOT NULL,
 topic VARCHAR(160) NOT NULL, partition_no INT NOT NULL, record_offset BIGINT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE projection_batches (
 id VARCHAR(36) PRIMARY KEY, generation VARCHAR(36) NOT NULL, payment_id VARCHAR(64) NOT NULL,
 merchant_id VARCHAR(64) NOT NULL, revision BIGINT NOT NULL, snapshot TEXT NOT NULL,
 snapshot_hash VARCHAR(64) NOT NULL, state VARCHAR(16) NOT NULL, attempts INT NOT NULL DEFAULT 0,
 due_at TIMESTAMP(6) NOT NULL, lease_token VARCHAR(36), lease_until TIMESTAMP(6),
 last_error VARCHAR(160), created_at TIMESTAMP(6) NOT NULL, delivered_at TIMESTAMP(6),
 CONSTRAINT uq_projection_revision UNIQUE(generation,payment_id,revision)
);
CREATE INDEX ix_projection_delivery ON projection_batches(state,due_at,lease_until,id);
CREATE INDEX ix_projection_entity ON projection_batches(generation,payment_id,revision);
CREATE TABLE projection_errors (
 id VARCHAR(36) PRIMARY KEY, topic VARCHAR(160) NOT NULL, partition_no INT NOT NULL,
 record_offset BIGINT NOT NULL, payload TEXT NOT NULL, payload_hash VARCHAR(64) NOT NULL,
 state VARCHAR(16) NOT NULL, reason VARCHAR(1024) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 replayed_by VARCHAR(160), CONSTRAINT uq_projection_error UNIQUE(topic,partition_no,record_offset)
);
CREATE TABLE balance_checkpoints (
 id VARCHAR(36) PRIMARY KEY, merchant_id VARCHAR(64) NOT NULL, cutoff_at TIMESTAMP(6) NOT NULL,
 source_hash VARCHAR(64) NOT NULL, source_count BIGINT NOT NULL, account_totals TEXT NOT NULL,
 status VARCHAR(16) NOT NULL, detail VARCHAR(2000) NOT NULL, actor VARCHAR(160) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX ix_balance_merchant ON balance_checkpoints(merchant_id,created_at,id);
CREATE TABLE merchant_finance_holds (
 merchant_id VARCHAR(64) NOT NULL, hold_type VARCHAR(32) NOT NULL, checkpoint_id VARCHAR(36) NOT NULL,
 active BOOLEAN NOT NULL, reason VARCHAR(2000) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY(merchant_id,hold_type)
);
