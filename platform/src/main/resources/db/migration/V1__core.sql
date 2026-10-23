CREATE TABLE merchants (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    notification_url VARCHAR(1024),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE merchant_keys (
    id VARCHAR(64) PRIMARY KEY,
    merchant_id VARCHAR(64) NOT NULL,
    secret_cipher TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    expires_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE INDEX ix_keys_merchant ON merchant_keys(merchant_id, enabled);

CREATE TABLE payments (
    id VARCHAR(64) PRIMARY KEY,
    merchant_id VARCHAR(64) NOT NULL,
    merchant_order_no VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    logical_shard INT NOT NULL,
    amount_minor BIGINT NOT NULL,
    fee_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    fee_rule VARCHAR(32) NOT NULL DEFAULT 'CNY_006_V1',
    status VARCHAR(24) NOT NULL,
    refunded_minor BIGINT NOT NULL DEFAULT 0,
    reserved_minor BIGINT NOT NULL DEFAULT 0,
    refunded_fee_minor BIGINT NOT NULL DEFAULT 0,
    channel VARCHAR(32) NOT NULL,
    channel_reference VARCHAR(128),
    notification_url VARCHAR(1024),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_payment_business UNIQUE (merchant_id, merchant_order_no),
    CONSTRAINT uq_payment_receipt UNIQUE (channel, channel_reference),
    CONSTRAINT ck_payment_amount CHECK (amount_minor > 0 AND amount_minor <= 1000000000),
    CONSTRAINT ck_payment_refunds CHECK (refunded_minor >= 0 AND reserved_minor >= 0 AND refunded_minor + reserved_minor <= amount_minor),
    CONSTRAINT ck_payment_fee CHECK (fee_minor >= 0 AND fee_minor <= amount_minor AND refunded_fee_minor >= 0 AND refunded_fee_minor <= fee_minor)
);
CREATE INDEX ix_payment_merchant_time ON payments(merchant_id, created_at, id);
CREATE INDEX ix_payment_status ON payments(status, updated_at);

CREATE TABLE refunds (
    id VARCHAR(64) PRIMARY KEY,
    payment_id VARCHAR(64) NOT NULL,
    merchant_id VARCHAR(64) NOT NULL,
    merchant_refund_no VARCHAR(100) NOT NULL,
    amount_minor BIGINT NOT NULL,
    refunded_fee_minor BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    channel_reference VARCHAR(128),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_refund_business UNIQUE (merchant_id, payment_id, merchant_refund_no),
    CONSTRAINT uq_refund_receipt UNIQUE (channel, channel_reference),
    CONSTRAINT ck_refund_amount CHECK (amount_minor > 0)
);
CREATE INDEX ix_refund_payment ON refunds(payment_id, created_at, id);
CREATE INDEX ix_refund_merchant_time ON refunds(merchant_id, created_at, id);

CREATE TABLE ledger_journals (
    id VARCHAR(64) PRIMARY KEY,
    event_id VARCHAR(160) NOT NULL,
    payment_id VARCHAR(64) NOT NULL,
    merchant_id VARCHAR(64) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id VARCHAR(64) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_journal_event UNIQUE (event_id)
);

CREATE TABLE ledger_entries (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    journal_id VARCHAR(64) NOT NULL,
    payment_id VARCHAR(64) NOT NULL,
    merchant_id VARCHAR(64) NOT NULL,
    account_code VARCHAR(40) NOT NULL,
    side VARCHAR(8) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    CONSTRAINT ck_entry_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_entry_side CHECK (side IN ('DEBIT','CREDIT'))
);
CREATE INDEX ix_entry_journal ON ledger_entries(journal_id);
CREATE INDEX ix_entry_payment ON ledger_entries(payment_id, id);
CREATE INDEX ix_journal_merchant_time ON ledger_journals(merchant_id, created_at, id);

CREATE TABLE outbox_tasks (
    id VARCHAR(64) PRIMARY KEY,
    event_id VARCHAR(160) NOT NULL,
    kind VARCHAR(40) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    merchant_id VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    due_at TIMESTAMP(6) NOT NULL,
    lease_until TIMESTAMP(6),
    lease_token VARCHAR(64),
    last_error VARCHAR(1024),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_outbox_event UNIQUE (event_id)
);
CREATE INDEX ix_task_ready ON outbox_tasks(status, due_at, id);

CREATE TABLE channel_attempts (
    id VARCHAR(64) PRIMARY KEY,
    aggregate_id VARCHAR(64) NOT NULL,
    payment_id VARCHAR(64) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    operation VARCHAR(24) NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL,
    request_body TEXT,
    response_body TEXT,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_channel_request UNIQUE (channel, request_key)
);
CREATE INDEX ix_attempt_payment ON channel_attempts(payment_id, created_at);

CREATE TABLE inbox_notifications (
    id VARCHAR(64) PRIMARY KEY,
    channel VARCHAR(32) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    operation VARCHAR(24) NOT NULL,
    raw_body TEXT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'RECEIVED',
    error_detail VARCHAR(1024),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_inbox_fingerprint UNIQUE (channel, fingerprint)
);

CREATE TABLE audit_records (
    id VARCHAR(64) PRIMARY KEY,
    merchant_id VARCHAR(64),
    actor VARCHAR(160) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    resource_id VARCHAR(160),
    detail TEXT,
    created_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX ix_audit_merchant_time ON audit_records(merchant_id, created_at);
