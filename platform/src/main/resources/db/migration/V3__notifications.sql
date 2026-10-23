CREATE TABLE notification_deliveries (
 id VARCHAR(64) PRIMARY KEY, task_id VARCHAR(64) NOT NULL, event_id VARCHAR(160) NOT NULL,
 merchant_id VARCHAR(64) NOT NULL, url VARCHAR(1024) NOT NULL, status VARCHAR(24) NOT NULL,
 http_status INT, error_detail VARCHAR(1024), created_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX ix_delivery_merchant_time ON notification_deliveries(merchant_id,created_at,id);
