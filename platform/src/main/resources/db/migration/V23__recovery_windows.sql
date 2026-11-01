-- Stop all old writers before migration. Existing unknown ages use the last recorded
-- business update as a conservative baseline; the original first-unknown time is unavailable.
ALTER TABLE payments ADD COLUMN unknown_since TIMESTAMP(6) NULL;
ALTER TABLE refunds ADD COLUMN unknown_since TIMESTAMP(6) NULL;
UPDATE payments SET unknown_since=updated_at WHERE status IN ('UNKNOWN','CLOSE_PENDING');
UPDATE refunds SET unknown_since=updated_at WHERE status='UNKNOWN';
CREATE INDEX ix_payment_unknown_age ON payments(status,unknown_since,id);
CREATE INDEX ix_refund_unknown_age ON refunds(status,unknown_since,id);

DROP VIEW outbox_task_records;
ALTER TABLE outbox_tasks ADD COLUMN retry_started_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
ALTER TABLE outbox_tasks ADD COLUMN recovery_stage VARCHAR(16) NOT NULL DEFAULT 'STANDARD';
ALTER TABLE outbox_task_history ADD COLUMN retry_started_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
ALTER TABLE outbox_task_history ADD COLUMN recovery_stage VARCHAR(16) NOT NULL DEFAULT 'STANDARD';
-- A manual replay retains the original task identity and created_at. Recover its
-- independently authorized window rather than expiring it at upgrade. Ordinary retry
-- updated_at values are deliberately ignored; they must not extend the window.
CREATE INDEX ix_audit_task_replay ON audit_records(operation,resource_id,merchant_id,created_at);
UPDATE outbox_tasks SET retry_started_at=COALESCE(
    (SELECT MAX(a.created_at) FROM audit_records a
     WHERE a.operation='TASK_REPLAY' AND a.resource_id=outbox_tasks.id
       AND a.merchant_id=outbox_tasks.merchant_id AND a.created_at>=outbox_tasks.created_at),
    created_at);
UPDATE outbox_task_history SET retry_started_at=COALESCE(
    (SELECT MAX(a.created_at) FROM audit_records a
     WHERE a.operation='TASK_REPLAY' AND a.resource_id=outbox_task_history.id
       AND a.merchant_id=outbox_task_history.merchant_id AND a.created_at>=outbox_task_history.created_at),
    created_at);
CREATE VIEW outbox_task_records AS
SELECT * FROM outbox_tasks UNION ALL SELECT * FROM outbox_task_history;
