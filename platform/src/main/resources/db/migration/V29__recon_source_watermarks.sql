-- Four-source reconciliation is provisional until a stopped-writer final seal exists.
ALTER TABLE recon_batches ADD COLUMN source_event_watermarks VARCHAR(120) NULL;
