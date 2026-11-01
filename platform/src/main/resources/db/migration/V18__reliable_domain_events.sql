CREATE TABLE event_transport (
 id INT PRIMARY KEY, mode VARCHAR(16) NOT NULL, cutoff_sequence BIGINT NOT NULL DEFAULT 0,
 changed_at TIMESTAMP(6) NOT NULL, CONSTRAINT ck_transport_single CHECK(id=1),
 CONSTRAINT ck_transport_mode CHECK(mode IN ('POLLING','CDC'))
);
INSERT INTO event_transport(id,mode,changed_at) VALUES(1,'POLLING',CURRENT_TIMESTAMP(6));
CREATE TABLE domain_event_versions (
 merchant_id VARCHAR(64) NOT NULL, aggregate_id VARCHAR(64) NOT NULL, version BIGINT NOT NULL,
 PRIMARY KEY(merchant_id,aggregate_id)
);
CREATE TABLE domain_events (
 sequence_no BIGINT AUTO_INCREMENT PRIMARY KEY,
 id VARCHAR(36) NOT NULL UNIQUE, event_id VARCHAR(160) NOT NULL UNIQUE,
 aggregatetype VARCHAR(40) NOT NULL, aggregateid VARCHAR(64) NOT NULL,
 merchant_id VARCHAR(64) NOT NULL, event_type VARCHAR(64) NOT NULL,
 aggregate_version BIGINT NOT NULL, contract_version INT NOT NULL,
 occurred_at TIMESTAMP(6) NOT NULL, delivery_mode VARCHAR(16) NOT NULL,
 trace_id VARCHAR(128) NOT NULL, payload TEXT NOT NULL, payload_hash VARCHAR(64) NOT NULL,
 CONSTRAINT ck_domain_mode CHECK(delivery_mode IN ('POLLING','CDC'))
);
CREATE INDEX ix_domain_merchant_time ON domain_events(merchant_id,occurred_at,sequence_no);
CREATE TABLE event_receipts (
 consumer_name VARCHAR(64) NOT NULL, event_id VARCHAR(160) NOT NULL,
 payload_hash VARCHAR(64) NOT NULL, topic VARCHAR(160) NOT NULL,
 partition_no INT NOT NULL, record_offset BIGINT NOT NULL, received_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY(consumer_name,event_id)
);
CREATE TABLE event_quarantine (
 id VARCHAR(36) PRIMARY KEY, topic VARCHAR(160) NOT NULL, partition_no INT NOT NULL,
 record_offset BIGINT NOT NULL, payload TEXT NOT NULL, payload_hash VARCHAR(64) NOT NULL, reason VARCHAR(1024) NOT NULL,
 status VARCHAR(16) NOT NULL, attempts INT NOT NULL DEFAULT 0, created_at TIMESTAMP(6) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL, replayed_by VARCHAR(160),
 CONSTRAINT uq_event_poison UNIQUE(topic,partition_no,record_offset)
);
CREATE INDEX ix_quarantine_status ON event_quarantine(status,created_at,id);
