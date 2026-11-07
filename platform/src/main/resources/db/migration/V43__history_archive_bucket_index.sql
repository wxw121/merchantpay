-- Stable search bucket follows the immutable payment ID, not the creation source.
-- Backfill preserves manifests created by V42 before this index is installed.
ALTER TABLE v3_history_archives ADD COLUMN logical_bucket TINYINT NULL;
UPDATE v3_history_archives
 SET logical_bucket=LOCATE(LOWER(SUBSTRING(payment_id,2,1)),'0123456789abcdef')-1
 WHERE payment_id REGEXP '^p[0-9a-f]_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';
ALTER TABLE v3_history_archives MODIFY COLUMN logical_bucket TINYINT NOT NULL;
CREATE INDEX ix_v3_history_merchant_bucket_time
 ON v3_history_archives(merchant_id,logical_bucket,created_at,id);
CREATE INDEX ix_v3_history_bucket_id
 ON v3_history_archives(logical_bucket,id);
