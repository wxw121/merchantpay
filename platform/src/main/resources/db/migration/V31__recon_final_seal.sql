ALTER TABLE recon_batches ADD COLUMN sealed_at TIMESTAMP(6) NULL;
ALTER TABLE recon_batches ADD COLUMN sealed_by VARCHAR(160) NULL;
ALTER TABLE recon_batches ADD COLUMN seal_reason VARCHAR(1000) NULL;
ALTER TABLE recon_batches ADD COLUMN seal_source_hash VARCHAR(64) NULL;
