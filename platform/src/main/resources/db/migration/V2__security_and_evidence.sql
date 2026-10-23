CREATE TABLE signature_nonces (
 key_id VARCHAR(64) NOT NULL, nonce VARCHAR(100) NOT NULL, expires_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY(key_id,nonce)
);
CREATE INDEX ix_nonce_expiry ON signature_nonces(expires_at);
CREATE TABLE api_rate_limits (
 key_id VARCHAR(64) NOT NULL, window_second BIGINT NOT NULL, requests INT NOT NULL,
 PRIMARY KEY(key_id,window_second)
);
CREATE TABLE evidence_conflicts (
 id VARCHAR(64) PRIMARY KEY, merchant_id VARCHAR(64), aggregate_id VARCHAR(64) NOT NULL,
 channel VARCHAR(32) NOT NULL, detail TEXT NOT NULL, raw_body TEXT,
 created_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX ix_evidence_merchant ON evidence_conflicts(merchant_id,created_at,id);
