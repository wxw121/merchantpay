-- Drain and stop every old application instance (API writers and workers) before upgrading.
-- Older binaries neither produce nor claim the new lanes correctly; do not roll this migration.
-- Preserve task identities, attempts, leases and financial history.
UPDATE outbox_tasks SET queue_class='INBOX'
WHERE status<>'DONE' AND kind='PROCESS_INBOX';
UPDATE outbox_tasks SET queue_class='NOTIFY'
WHERE status<>'DONE' AND kind IN
('PAYMENT_SUCCEEDED','PAYMENT_FAILED','PAYMENT_CLOSED','REFUND_SUCCEEDED','REFUND_FAILED');
