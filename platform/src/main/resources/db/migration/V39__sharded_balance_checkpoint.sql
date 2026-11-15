-- A four-source checkpoint binds its ledger witness to route and event evidence.
-- Legacy core-only checkpoints keep both columns NULL.
ALTER TABLE balance_checkpoints ADD COLUMN source_routes_hash VARCHAR(64);
ALTER TABLE balance_checkpoints ADD COLUMN source_event_watermarks VARCHAR(128);
ALTER TABLE balance_checkpoints ADD CONSTRAINT ck_balance_sharded_evidence CHECK(
 (source_routes_hash IS NULL AND source_event_watermarks IS NULL)
 OR (source_routes_hash IS NOT NULL AND source_event_watermarks IS NOT NULL)
);
