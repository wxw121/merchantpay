-- Original payment/refund history is append-only; settlements have no payment root.
ALTER TABLE ledger_journals MODIFY payment_id VARCHAR(64) NULL;
ALTER TABLE ledger_journals ADD CONSTRAINT ck_journal_root CHECK(payment_id IS NOT NULL OR source_type='SETTLEMENT');
ALTER TABLE ledger_entries MODIFY payment_id VARCHAR(64) NULL;
ALTER TABLE ledger_entries ADD COLUMN entry_kind VARCHAR(16) NOT NULL DEFAULT 'BUSINESS';
ALTER TABLE ledger_entries ADD CONSTRAINT ck_entry_root CHECK(payment_id IS NOT NULL OR entry_kind='SETTLEMENT');
CREATE INDEX ix_entry_merchant_account ON ledger_entries(merchant_id,account_code,id);

CREATE TABLE finance_cases (
 id VARCHAR(64) PRIMARY KEY, issue_id VARCHAR(64) NOT NULL UNIQUE, merchant_id VARCHAR(64) NOT NULL,
 payment_id VARCHAR(64), assigned_to VARCHAR(160), evidence VARCHAR(2000),
 status VARCHAR(24) NOT NULL, version BIGINT NOT NULL, created_by VARCHAR(160) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX ix_case_merchant ON finance_cases(merchant_id,created_at,id);
CREATE TABLE finance_adjustments (
 id VARCHAR(64) PRIMARY KEY, case_id VARCHAR(64) NOT NULL UNIQUE, merchant_id VARCHAR(64) NOT NULL,
 payment_id VARCHAR(64), case_version BIGINT NOT NULL, version BIGINT NOT NULL,
 status VARCHAR(24) NOT NULL, direction VARCHAR(16) NOT NULL, amount_minor BIGINT NOT NULL,
 reason VARCHAR(2000) NOT NULL, proposer VARCHAR(160) NOT NULL, last_editor VARCHAR(160) NOT NULL,
 digest VARCHAR(64) NOT NULL, approved_digest VARCHAR(64), approved_version BIGINT, reviewer VARCHAR(160),
 journal_id VARCHAR(64), created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT ck_adjustment_amount CHECK((direction='NONE' AND amount_minor=0) OR (direction IN ('INCREASE','DECREASE') AND payment_id IS NOT NULL AND amount_minor>0 AND amount_minor<=1000000000))
);
CREATE INDEX ix_adjustment_merchant ON finance_adjustments(merchant_id,created_at,id);
CREATE TABLE finance_reviews (
 id VARCHAR(64) PRIMARY KEY, object_type VARCHAR(24) NOT NULL, object_id VARCHAR(64) NOT NULL,
 object_version BIGINT NOT NULL, digest VARCHAR(64) NOT NULL, reviewer VARCHAR(160) NOT NULL,
 decision VARCHAR(16) NOT NULL, reason VARCHAR(2000) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uq_finance_review UNIQUE(object_type,object_id,object_version)
);
CREATE TABLE finance_settlements (
 id VARCHAR(64) PRIMARY KEY, merchant_id VARCHAR(64) NOT NULL, version BIGINT NOT NULL,
 status VARCHAR(24) NOT NULL, cutoff_at TIMESTAMP(6) NOT NULL, source_hash VARCHAR(64) NOT NULL,
 net_minor BIGINT NOT NULL, reason VARCHAR(2000) NOT NULL, proposer VARCHAR(160) NOT NULL,
 last_editor VARCHAR(160) NOT NULL, approved_digest VARCHAR(64), approved_version BIGINT,
 reviewer VARCHAR(160), journal_id VARCHAR(64), created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX ix_settlement_merchant ON finance_settlements(merchant_id,created_at,id);
CREATE TABLE settlement_lines (
 settlement_id VARCHAR(64) NOT NULL, revision BIGINT NOT NULL, entry_id BIGINT NOT NULL,
 payment_id VARCHAR(64) NOT NULL, journal_id VARCHAR(64) NOT NULL, signed_minor BIGINT NOT NULL,
 prior_settlement_id VARCHAR(64), prior_revision BIGINT,
 PRIMARY KEY(settlement_id,revision,entry_id)
);
CREATE TABLE settlement_reservations (
 entry_id BIGINT PRIMARY KEY, settlement_id VARCHAR(64) NOT NULL, revision BIGINT NOT NULL
);
CREATE INDEX ix_reservation_settlement ON settlement_reservations(settlement_id,revision);
