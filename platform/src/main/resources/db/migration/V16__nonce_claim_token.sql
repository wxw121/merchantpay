-- Nullable for old writers and existing unexpired nonces; the primary key remains replay authority.
ALTER TABLE signature_nonces ADD COLUMN claim_token VARCHAR(36) NULL;
