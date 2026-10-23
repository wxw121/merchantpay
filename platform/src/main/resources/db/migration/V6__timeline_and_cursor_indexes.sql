CREATE INDEX ix_task_merchant_time ON outbox_tasks(merchant_id,created_at,id);
CREATE INDEX ix_task_aggregate ON outbox_tasks(aggregate_id,created_at);
CREATE INDEX ix_task_expired_lease ON outbox_tasks(status,lease_until,id);
CREATE INDEX ix_inbox_aggregate ON inbox_notifications(aggregate_id,created_at);
CREATE INDEX ix_payment_global_time ON payments(created_at,id);
CREATE INDEX ix_refund_global_time ON refunds(created_at,id);
CREATE INDEX ix_journal_global_time ON ledger_journals(created_at,id);
CREATE INDEX ix_audit_global_time ON audit_records(created_at,id);
