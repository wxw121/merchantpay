-- Source-local CDC routing; deploy only after old writers have stopped and V26 is applied.
ALTER TABLE domain_events ADD COLUMN logical_bucket INT NULL;
ALTER TABLE domain_events ADD CONSTRAINT ck_domain_event_bucket
 CHECK(logical_bucket IS NULL OR logical_bucket BETWEEN 0 AND 15);
UPDATE domain_events SET logical_bucket=LOCATE(LOWER(SUBSTRING(aggregateid,2,1)),'0123456789abcdef')-1
 WHERE aggregateid REGEXP '^[pr][0-9a-f]_[0-9a-f-]{36}$';
UPDATE domain_events SET logical_bucket=(
 SELECT LOCATE(LOWER(SUBSTRING(i.aggregate_id,2,1)),'0123456789abcdef')-1
 FROM inbox_notifications i WHERE i.id=domain_events.aggregateid)
 WHERE event_type='PROCESS_INBOX' AND EXISTS (
 SELECT 1 FROM inbox_notifications i WHERE i.id=domain_events.aggregateid
 AND i.aggregate_id REGEXP '^[pr][0-9a-f]_[0-9a-f-]{36}$');
