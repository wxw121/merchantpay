ALTER TABLE outbox_tasks ADD COLUMN queue_class VARCHAR(8) NOT NULL DEFAULT 'FAST';
UPDATE outbox_tasks SET queue_class='BULK' WHERE kind IN ('RECONCILE','EXPORT');
CREATE INDEX ix_task_class_ready ON outbox_tasks(queue_class,status,due_at,id);
CREATE INDEX ix_task_class_lease ON outbox_tasks(queue_class,status,lease_until,id);
