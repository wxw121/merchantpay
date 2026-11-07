-- New archives register event facts before any future cold retirement.
-- Existing WARM_COPY archives remain readable but are not cold-retirement eligible
-- until an object-verified backfill has installed their locators.
CREATE TABLE v3_history_event_locators (
 event_id VARCHAR(160) PRIMARY KEY,
 merchant_id VARCHAR(64) NOT NULL,
 payment_id VARCHAR(64) NOT NULL,
 logical_bucket TINYINT NOT NULL,
 archive_id VARCHAR(36) NOT NULL,
 payload_hash VARCHAR(64) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_v3_history_event_archive FOREIGN KEY(archive_id)
   REFERENCES v3_history_archives(id),
 CONSTRAINT ck_v3_history_event_bucket CHECK(logical_bucket BETWEEN 0 AND 15),
 INDEX ix_v3_history_event_bucket(logical_bucket,event_id),
 INDEX ix_v3_history_event_archive(archive_id,event_id)
);
