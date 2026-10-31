-- Local opt-in only: stop all writers and drain before using the maintenance wrapper.
-- Keep lane, uniqueness, merchant, aggregate and archive indexes. Global claims may filesort.
ALTER TABLE outbox_tasks
    DROP INDEX ix_task_ready,
    DROP INDEX ix_task_expired_lease,
    ALGORITHM=INPLACE, LOCK=NONE;
