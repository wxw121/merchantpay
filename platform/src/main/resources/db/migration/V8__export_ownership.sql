ALTER TABLE exports ADD COLUMN requested_by VARCHAR(160);
ALTER TABLE exports ADD COLUMN expires_at TIMESTAMP(6);
CREATE INDEX ix_export_owner_time ON exports(requested_by,merchant_id,created_at,id);
-- Legacy exports have no requester proof and cannot be downloaded; regenerate them.
