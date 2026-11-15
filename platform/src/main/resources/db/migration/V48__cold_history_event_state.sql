-- Hot row deletion and locator state change commit in one owner transaction.
-- The immutable archive object and receipt rows remain available for replay.
ALTER TABLE v3_history_event_locators ADD COLUMN state VARCHAR(12) NOT NULL DEFAULT 'WARM_COPY';
ALTER TABLE v3_history_event_locators ADD COLUMN retired_at TIMESTAMP(6);
ALTER TABLE v3_history_event_locators ADD COLUMN retired_by VARCHAR(160);
ALTER TABLE v3_history_event_locators ADD COLUMN retire_reason VARCHAR(1000);
ALTER TABLE v3_history_event_locators ADD COLUMN event_payload_hash VARCHAR(64);
ALTER TABLE v3_history_event_locators ADD COLUMN event_row_id VARCHAR(64);
UPDATE v3_history_event_locators SET
 event_payload_hash=(SELECT e.payload_hash FROM domain_events e
  WHERE e.event_id=v3_history_event_locators.event_id),
 event_row_id=(SELECT e.id FROM domain_events e
  WHERE e.event_id=v3_history_event_locators.event_id)
 WHERE EXISTS(SELECT 1 FROM domain_events e
  WHERE e.event_id=v3_history_event_locators.event_id);
ALTER TABLE v3_history_event_locators ADD CONSTRAINT ck_v3_history_event_state CHECK(
 (state='WARM_COPY' AND retired_at IS NULL AND retired_by IS NULL AND retire_reason IS NULL)
 OR (state='COLD' AND retired_at IS NOT NULL AND retired_by IS NOT NULL
     AND retire_reason IS NOT NULL));
