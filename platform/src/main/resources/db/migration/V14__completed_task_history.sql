-- Stop all old application writers before migrating; old binaries cannot read archived tasks.
-- Permanent uniqueness survives movement between the execution and history tables.
CREATE TABLE outbox_events (
    id VARCHAR(64) PRIMARY KEY,
    event_id VARCHAR(160) NOT NULL,
    CONSTRAINT uq_outbox_permanent_event UNIQUE(event_id)
);
INSERT INTO outbox_events(id,event_id) SELECT id,event_id FROM outbox_tasks;

CREATE TABLE outbox_task_history (
    id VARCHAR(64) PRIMARY KEY,
    event_id VARCHAR(160) NOT NULL,
    kind VARCHAR(40) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    merchant_id VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL,
    due_at TIMESTAMP(6) NOT NULL,
    lease_until TIMESTAMP(6),
    lease_token VARCHAR(64),
    last_error VARCHAR(1024),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    queue_class VARCHAR(8) NOT NULL,
    CONSTRAINT uq_outbox_history_event UNIQUE(event_id),
    CONSTRAINT ck_outbox_history_done CHECK(status='DONE')
);
CREATE INDEX ix_task_archive_due ON outbox_tasks(status,updated_at,id);
CREATE INDEX ix_task_history_merchant ON outbox_task_history(merchant_id,created_at,id);
CREATE INDEX ix_task_history_time ON outbox_task_history(created_at,id);
CREATE INDEX ix_task_history_aggregate ON outbox_task_history(aggregate_id,created_at);
CREATE VIEW outbox_task_records AS
SELECT * FROM outbox_tasks UNION ALL SELECT * FROM outbox_task_history;
