-- A staged reconciliation snapshot may only be resumed under the same bucket map.
ALTER TABLE recon_batches ADD COLUMN source_routes_hash VARCHAR(64) NULL;
