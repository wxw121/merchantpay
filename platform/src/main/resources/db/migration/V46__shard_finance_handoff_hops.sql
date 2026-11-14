-- Immutable per-plan finance ID hops. V41 remains the canonical current owner
-- for each original payable entry; a later move updates only its target.
CREATE TABLE v3_shard_finance_handoff_hops (
 plan_id VARCHAR(36) NOT NULL,
 source_shard TINYINT NOT NULL,
 source_entry_id BIGINT NOT NULL,
 target_shard TINYINT NOT NULL,
 target_entry_id BIGINT NOT NULL,
 merchant_id VARCHAR(64) NOT NULL,
 payment_id VARCHAR(64) NOT NULL,
 PRIMARY KEY(plan_id,source_shard,source_entry_id),
 CONSTRAINT uq_v3_finance_hop_target UNIQUE(plan_id,target_shard,target_entry_id),
 CONSTRAINT ck_v3_finance_hop_shards CHECK(source_shard BETWEEN 0 AND 3
   AND target_shard BETWEEN 0 AND 3 AND source_shard<>target_shard),
 CONSTRAINT ck_v3_finance_hop_ids CHECK(source_entry_id>0 AND target_entry_id>0)
);
INSERT INTO v3_shard_finance_handoff_hops
 (plan_id,source_shard,source_entry_id,target_shard,target_entry_id,merchant_id,payment_id)
 SELECT plan_id,source_shard,source_entry_id,target_shard,target_entry_id,
   merchant_id,payment_id FROM v3_shard_finance_handoffs;
