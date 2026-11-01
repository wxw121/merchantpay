-- Apply explicitly to the isolated analytics database; no business/ledger writes are authorized here.
CREATE DATABASE IF NOT EXISTS merchantpay;
CREATE TABLE IF NOT EXISTS merchantpay.payment_projection (
 generation String, merchant_id String, payment_id String, revision UInt64,
 created_at DateTime64(6,'UTC'), source_updated_at DateTime64(6,'UTC'),
 status LowCardinality(String), amount_minor Int64, refunded_minor Int64,
 snapshot String, snapshot_hash FixedString(64)
) ENGINE=ReplacingMergeTree(revision)
ORDER BY (generation,merchant_id,payment_id);
-- Application reads MUST use FINAL or argMax(revision), including while background merges are paused.
