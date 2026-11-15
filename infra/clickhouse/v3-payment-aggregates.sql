-- A derived, replaceable store. Apply separately; this is not a money ledger.
CREATE DATABASE IF NOT EXISTS merchantpay;
CREATE TABLE IF NOT EXISTS merchantpay.v3_payment_aggregates (
  generation String, merchant_id String, business_day Date, channel String,
  payments UInt64, succeeded UInt64, succeeded_minor Int64,
  refunded_minor Int64, latest_source_updated_at String,
  deleted UInt8, kafka_offset UInt64
) ENGINE = ReplacingMergeTree(kafka_offset)
ORDER BY (generation, merchant_id, business_day, channel);
-- Readers must use FINAL, filter deleted=0, and require a separately verified generation.
