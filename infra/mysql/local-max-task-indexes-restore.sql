-- Restore the default global-claim access paths while writers remain stopped.
ALTER TABLE outbox_tasks
    ADD INDEX ix_task_ready(status,due_at,id),
    ADD INDEX ix_task_expired_lease(status,lease_until,id),
    ALGORITHM=INPLACE, LOCK=NONE;
