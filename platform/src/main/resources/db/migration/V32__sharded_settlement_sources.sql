-- Entry IDs are allocated independently on each physical source. Existing reservations belong to core 0.
ALTER TABLE settlement_lines ADD COLUMN physical_shard TINYINT NOT NULL DEFAULT 0;
ALTER TABLE settlement_lines DROP PRIMARY KEY;
ALTER TABLE settlement_lines ADD PRIMARY KEY(settlement_id,revision,physical_shard,entry_id);
ALTER TABLE settlement_reservations ADD COLUMN physical_shard TINYINT NOT NULL DEFAULT 0;
ALTER TABLE settlement_reservations DROP PRIMARY KEY;
ALTER TABLE settlement_reservations ADD PRIMARY KEY(physical_shard,entry_id);
ALTER TABLE finance_settlements ADD COLUMN source_routes_hash VARCHAR(64) NULL;
ALTER TABLE finance_settlements ADD COLUMN source_event_watermarks VARCHAR(120) NULL;
CREATE TABLE sharded_settlement_cursors (
 merchant_id VARCHAR(64) NOT NULL, physical_shard TINYINT NOT NULL,
 last_entry_id BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(merchant_id,physical_shard)
);
