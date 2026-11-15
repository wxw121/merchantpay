-- Stop old channel workers and drain before enabling the new dispatch path.
ALTER TABLE outbox_tasks ADD COLUMN logical_bucket INT NULL;
ALTER TABLE outbox_tasks ADD COLUMN lease_epoch BIGINT NULL;
ALTER TABLE outbox_task_history ADD COLUMN logical_bucket INT NULL;
ALTER TABLE outbox_task_history ADD COLUMN lease_epoch BIGINT NULL;
ALTER TABLE outbox_tasks ADD CONSTRAINT ck_task_bucket CHECK(logical_bucket IS NULL OR logical_bucket BETWEEN 0 AND 15);
ALTER TABLE outbox_tasks ADD CONSTRAINT ck_task_epoch CHECK(lease_epoch IS NULL OR lease_epoch>0);
CREATE INDEX ix_task_bucket_pending ON outbox_tasks(status,queue_class,logical_bucket,due_at,id);
CREATE INDEX ix_task_bucket_lease ON outbox_tasks(status,queue_class,logical_bucket,lease_until,id);
UPDATE outbox_tasks SET logical_bucket=LOCATE(LOWER(SUBSTRING(aggregate_id,2,1)),'0123456789abcdef')-1
 WHERE aggregate_id REGEXP '^[pr][0-9a-f]_[0-9a-f-]{36}$';
UPDATE outbox_tasks SET logical_bucket=(
 SELECT LOCATE(LOWER(SUBSTRING(i.aggregate_id,2,1)),'0123456789abcdef')-1
 FROM inbox_notifications i WHERE i.id=outbox_tasks.aggregate_id)
 WHERE kind='PROCESS_INBOX' AND EXISTS (
 SELECT 1 FROM inbox_notifications i WHERE i.id=outbox_tasks.aggregate_id
 AND i.aggregate_id REGEXP '^[pr][0-9a-f]_[0-9a-f-]{36}$');
UPDATE outbox_task_history SET logical_bucket=LOCATE(LOWER(SUBSTRING(aggregate_id,2,1)),'0123456789abcdef')-1
 WHERE aggregate_id REGEXP '^[pr][0-9a-f]_[0-9a-f-]{36}$';
UPDATE outbox_task_history SET logical_bucket=(
 SELECT LOCATE(LOWER(SUBSTRING(i.aggregate_id,2,1)),'0123456789abcdef')-1
 FROM inbox_notifications i WHERE i.id=outbox_task_history.aggregate_id)
 WHERE kind='PROCESS_INBOX' AND EXISTS (
 SELECT 1 FROM inbox_notifications i WHERE i.id=outbox_task_history.aggregate_id
 AND i.aggregate_id REGEXP '^[pr][0-9a-f]_[0-9a-f-]{36}$');

CREATE TABLE v3_receipt_claims (
 channel VARCHAR(32) NOT NULL,
 operation VARCHAR(16) NOT NULL,
 receipt_reference VARCHAR(128) NOT NULL,
 aggregate_id VARCHAR(64) NOT NULL,
 merchant_id VARCHAR(64) NOT NULL,
 amount_minor BIGINT NOT NULL,
 currency VARCHAR(3) NOT NULL,
 claimed_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY(channel,operation,receipt_reference),
 CONSTRAINT ck_receipt_operation CHECK(operation IN ('PAYMENT','REFUND')),
 CONSTRAINT ck_receipt_amount CHECK(amount_minor>0)
);
INSERT INTO v3_receipt_claims
 SELECT channel,'PAYMENT',channel_reference,id,merchant_id,amount_minor,currency,CURRENT_TIMESTAMP(6)
 FROM payments WHERE status='SUCCEEDED' AND channel_reference IS NOT NULL;
INSERT INTO v3_receipt_claims
 SELECT channel,'REFUND',channel_reference,id,merchant_id,amount_minor,'CNY',CURRENT_TIMESTAMP(6)
 FROM refunds WHERE status='SUCCEEDED' AND channel_reference IS NOT NULL;
