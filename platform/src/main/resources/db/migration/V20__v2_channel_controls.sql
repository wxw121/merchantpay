CREATE TABLE channel_controls (
 channel VARCHAR(32) PRIMARY KEY, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 max_inflight INT NOT NULL, max_per_second INT NOT NULL, failure_threshold INT NOT NULL,
 open_seconds INT NOT NULL, failures INT NOT NULL DEFAULT 0, open_until TIMESTAMP(6),
 probe_token VARCHAR(36), probe_until TIMESTAMP(6), version BIGINT NOT NULL DEFAULT 1,
 window_second BIGINT NOT NULL DEFAULT 0, window_used INT NOT NULL DEFAULT 0,
 updated_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT ck_channel_limits CHECK(max_inflight BETWEEN 1 AND 256 AND max_per_second BETWEEN 1 AND 10000 AND failure_threshold BETWEEN 1 AND 100 AND open_seconds BETWEEN 1 AND 600)
);
INSERT INTO channel_controls(channel,max_inflight,max_per_second,failure_threshold,open_seconds,updated_at)
VALUES ('MOCK_A',32,500,5,30,CURRENT_TIMESTAMP(6)),('MOCK_B',32,500,5,30,CURRENT_TIMESTAMP(6)),('ALIPAY_SANDBOX',8,20,3,30,CURRENT_TIMESTAMP(6));
CREATE TABLE channel_leases (
 token VARCHAR(36) PRIMARY KEY, channel VARCHAR(32) NOT NULL,
 expires_at TIMESTAMP(6) NOT NULL, probe BOOLEAN NOT NULL,
 CONSTRAINT fk_channel_lease FOREIGN KEY(channel) REFERENCES channel_controls(channel)
);
CREATE INDEX ix_channel_lease_expiry ON channel_leases(channel,expires_at);
CREATE TABLE merchant_channel_routes (
 merchant_id VARCHAR(64) NOT NULL, channel VARCHAR(32) NOT NULL,
 priority INT NOT NULL, enabled BOOLEAN NOT NULL, version BIGINT NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL, PRIMARY KEY(merchant_id,channel)
);
CREATE INDEX ix_route_priority ON merchant_channel_routes(merchant_id,enabled,priority,channel);
CREATE TABLE sandbox_checkouts (
 payment_id VARCHAR(64) PRIMARY KEY, qr_code VARCHAR(2048) NOT NULL, created_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE alipay_evidence (
 id VARCHAR(36) PRIMARY KEY, aggregate_id VARCHAR(64) NOT NULL, operation VARCHAR(24) NOT NULL,
 body TEXT NOT NULL, sha256 VARCHAR(64) NOT NULL, created_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX ix_alipay_evidence ON alipay_evidence(aggregate_id,created_at,id);
