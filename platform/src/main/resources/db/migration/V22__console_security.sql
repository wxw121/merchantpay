CREATE TABLE console_sessions (
 PRIMARY_ID CHAR(36) NOT NULL PRIMARY KEY, SESSION_ID CHAR(36) NOT NULL,
 CREATION_TIME BIGINT NOT NULL, LAST_ACCESS_TIME BIGINT NOT NULL,
 MAX_INACTIVE_INTERVAL INT NOT NULL, EXPIRY_TIME BIGINT NOT NULL, PRINCIPAL_NAME VARCHAR(160)
);
CREATE UNIQUE INDEX ix_console_session_id ON console_sessions(SESSION_ID);
CREATE INDEX ix_console_session_expiry ON console_sessions(EXPIRY_TIME);
CREATE INDEX ix_console_session_principal ON console_sessions(PRINCIPAL_NAME);
CREATE TABLE console_sessions_ATTRIBUTES (
 SESSION_PRIMARY_ID CHAR(36) NOT NULL, ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
 ATTRIBUTE_BYTES BLOB NOT NULL, PRIMARY KEY(SESSION_PRIMARY_ID,ATTRIBUTE_NAME),
 FOREIGN KEY(SESSION_PRIMARY_ID) REFERENCES console_sessions(PRIMARY_ID) ON DELETE CASCADE
);
CREATE TABLE console_confirmations (
 id VARCHAR(36) PRIMARY KEY, actor VARCHAR(160) NOT NULL, session_hash VARCHAR(64) NOT NULL,
 method VARCHAR(8) NOT NULL, path VARCHAR(512) NOT NULL, body_hash VARCHAR(64) NOT NULL,
 expires_at TIMESTAMP(6) NOT NULL, consumed_at TIMESTAMP(6)
);
CREATE INDEX ix_console_confirmation_expiry ON console_confirmations(expires_at);
CREATE TABLE revoked_console_sessions (
 session_hash VARCHAR(64) PRIMARY KEY, expires_at BIGINT NOT NULL
);
CREATE INDEX ix_revoked_console_expiry ON revoked_console_sessions(expires_at);
CREATE INDEX ix_projection_merchant_payment ON payments(merchant_id,id);
CREATE INDEX ix_balance_ledger_scan ON ledger_entries(merchant_id,journal_id,id);
CREATE INDEX ix_projection_polling_fact ON domain_events(delivery_mode,sequence_no);
