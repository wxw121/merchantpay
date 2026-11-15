CREATE TABLE IF NOT EXISTS receipts (
  channel VARCHAR(32) NOT NULL, operation VARCHAR(16) NOT NULL, aggregate_id VARCHAR(64) NOT NULL,
  payment_id VARCHAR(64) NOT NULL, merchant_id VARCHAR(64) NOT NULL, business_no VARCHAR(100) NOT NULL,
  request_hash VARCHAR(64) NOT NULL, amount_minor BIGINT NOT NULL, currency VARCHAR(3) NOT NULL,
  status VARCHAR(24) NOT NULL, reference VARCHAR(128) NOT NULL, fee_minor BIGINT NOT NULL,
  refunded_minor BIGINT NOT NULL DEFAULT 0, refunded_fee_minor BIGINT NOT NULL DEFAULT 0,
  notified BOOLEAN NOT NULL DEFAULT FALSE, lost_response_sent BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(channel, operation, aggregate_id)
);
CREATE TABLE IF NOT EXISTS channel_locks (channel VARCHAR(32) NOT NULL, aggregate_id VARCHAR(64) NOT NULL, PRIMARY KEY(channel,aggregate_id));
CREATE TABLE IF NOT EXISTS merchant_webhooks (event_id VARCHAR(160) PRIMARY KEY, raw_body TEXT NOT NULL, deliveries INT NOT NULL DEFAULT 1, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP);
ALTER TABLE receipts ADD COLUMN IF NOT EXISTS business_created_at TIMESTAMP;
UPDATE receipts SET business_created_at=created_at WHERE business_created_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_receipt_business_day ON receipts(channel,merchant_id,business_created_at,operation,aggregate_id);
CREATE INDEX IF NOT EXISTS ix_receipt_unnotified ON receipts(notified,status,channel,merchant_id,created_at);
CREATE INDEX IF NOT EXISTS ix_receipt_pending ON receipts(status,channel,merchant_id);
ALTER TABLE receipts ADD COLUMN IF NOT EXISTS expected_callbacks INT NOT NULL DEFAULT 1;
ALTER TABLE receipts ADD COLUMN IF NOT EXISTS callback_deliveries INT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS ix_receipt_evidence ON receipts(channel,operation,aggregate_id);
CREATE INDEX IF NOT EXISTS ix_receipt_run_evidence ON receipts(channel,operation,business_no,aggregate_id);
