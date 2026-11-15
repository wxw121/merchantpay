ALTER TABLE recon_batches ADD COLUMN bill_format VARCHAR(16) NOT NULL DEFAULT 'SINGLE';
ALTER TABLE recon_batches ADD COLUMN segment_count INT NULL;

CREATE TABLE recon_segments (
 batch_id VARCHAR(64) NOT NULL, segment_index INT NOT NULL,
 sha256 VARCHAR(64) NOT NULL, storage_key VARCHAR(160) NOT NULL,
 expected_count BIGINT NOT NULL, expected_amount_minor BIGINT NOT NULL,
 PRIMARY KEY(batch_id,segment_index)
);
