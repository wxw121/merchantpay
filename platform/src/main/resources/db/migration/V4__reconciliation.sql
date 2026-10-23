CREATE TABLE recon_batches (
 id VARCHAR(64) PRIMARY KEY, merchant_id VARCHAR(64) NOT NULL, channel VARCHAR(32) NOT NULL,
 business_day DATE NOT NULL, version INT NOT NULL, sha256 VARCHAR(64) NOT NULL, storage_key VARCHAR(160) NOT NULL,
 status VARCHAR(24) NOT NULL, expected_count BIGINT NOT NULL DEFAULT 0, actual_count BIGINT NOT NULL DEFAULT 0,
 expected_amount_minor BIGINT NOT NULL DEFAULT 0, actual_amount_minor BIGINT NOT NULL DEFAULT 0,
 processed_lines BIGINT NOT NULL DEFAULT 0, snapshot_at TIMESTAMP(6), error_detail VARCHAR(1024),
 created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uq_bill_version UNIQUE(merchant_id,channel,business_day,version)
);
CREATE INDEX ix_recon_merchant_time ON recon_batches(merchant_id,created_at,id);
CREATE TABLE recon_rows (
 batch_id VARCHAR(64) NOT NULL, line_no BIGINT NOT NULL, operation VARCHAR(16) NOT NULL,
 aggregate_id VARCHAR(64) NOT NULL, status VARCHAR(24) NOT NULL, amount_minor BIGINT NOT NULL,
 fee_minor BIGINT NOT NULL, currency VARCHAR(3) NOT NULL, reference VARCHAR(128) NOT NULL, raw_line VARCHAR(1024) NOT NULL,
 PRIMARY KEY(batch_id,line_no)
);
CREATE INDEX ix_recon_match ON recon_rows(batch_id,operation,aggregate_id);
CREATE TABLE recon_local_rows (
 batch_id VARCHAR(64) NOT NULL, operation VARCHAR(16) NOT NULL, aggregate_id VARCHAR(64) NOT NULL,
 status VARCHAR(24) NOT NULL, amount_minor BIGINT NOT NULL, fee_minor BIGINT NOT NULL, currency VARCHAR(3) NOT NULL,
 reference VARCHAR(128), PRIMARY KEY(batch_id,operation,aggregate_id)
);
CREATE TABLE recon_issues (
 id VARCHAR(64) PRIMARY KEY, batch_id VARCHAR(64) NOT NULL, merchant_id VARCHAR(64) NOT NULL,
 line_no BIGINT NOT NULL, operation VARCHAR(16) NOT NULL, aggregate_id VARCHAR(64) NOT NULL,
 issue_type VARCHAR(32) NOT NULL, local_status VARCHAR(24), channel_status VARCHAR(24), local_amount_minor BIGINT,
 channel_amount_minor BIGINT, local_fee_minor BIGINT, channel_fee_minor BIGINT, raw_line VARCHAR(1024), created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uq_recon_issue UNIQUE(batch_id,operation,aggregate_id,line_no,issue_type)
);
CREATE INDEX ix_issue_batch ON recon_issues(batch_id,id);
