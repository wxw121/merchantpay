-- Core-owned alias for reserved entries copied to a new physical source.
-- The original settlement line remains immutable; the live reservation moves
-- with the route in the same core transaction.
CREATE TABLE v3_shard_finance_handoffs (
 plan_id VARCHAR(36) NOT NULL,
 source_shard TINYINT NOT NULL,
 source_entry_id BIGINT NOT NULL,
 target_shard TINYINT NOT NULL,
 target_entry_id BIGINT NOT NULL,
 merchant_id VARCHAR(64) NOT NULL,
 payment_id VARCHAR(64) NOT NULL,
 PRIMARY KEY(source_shard,source_entry_id),
 CONSTRAINT uq_v3_finance_handoff_target UNIQUE(target_shard,target_entry_id),
 INDEX ix_v3_finance_handoff_plan(plan_id)
);
