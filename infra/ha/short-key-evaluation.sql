-- N05 ONLY: isolated merchantpay_key_eval schema. No production table alterations.
CREATE DATABASE IF NOT EXISTS merchantpay_key_eval;
CREATE TABLE merchantpay_key_eval.external_key (
 id VARCHAR(64) PRIMARY KEY, merchant_id VARCHAR(64) NOT NULL,
 external_no VARCHAR(96) NOT NULL, amount_minor BIGINT NOT NULL,
 UNIQUE KEY uq_business(merchant_id,external_no)
);
CREATE TABLE merchantpay_key_eval.short_key (
 internal_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 external_id VARCHAR(64) NOT NULL, merchant_id VARCHAR(64) NOT NULL,
 external_no VARCHAR(96) NOT NULL, amount_minor BIGINT NOT NULL,
 UNIQUE KEY uq_external(external_id), UNIQUE KEY uq_business(merchant_id,external_no)
);
-- Load identical generated data externally, measure median latency/redo/index bytes under the same cap.
-- Never infer a concurrency gain from key width alone; include external-id lookup and migration cost.
SELECT table_name,table_rows,data_length,index_length FROM information_schema.tables WHERE table_schema='merchantpay_key_eval';
