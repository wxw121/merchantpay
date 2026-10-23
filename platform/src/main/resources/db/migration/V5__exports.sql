CREATE TABLE exports (
 id VARCHAR(64) PRIMARY KEY, merchant_id VARCHAR(64) NOT NULL, resource VARCHAR(24) NOT NULL,
 from_time TIMESTAMP(6) NOT NULL, until_time TIMESTAMP(6) NOT NULL, status VARCHAR(24) NOT NULL,
 storage_key VARCHAR(160), row_count BIGINT NOT NULL DEFAULT 0, error_detail VARCHAR(1024),
 created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX ix_export_merchant_time ON exports(merchant_id,created_at,id);
