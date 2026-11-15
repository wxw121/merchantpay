-- Target-local identity witness. A copy and all of its mappings commit together.
-- The source business/event IDs stay unchanged; only target-local numeric IDs move.
CREATE TABLE v3_shard_copy_id_map (
 plan_id VARCHAR(36) NOT NULL,
 entity_name VARCHAR(32) NOT NULL,
 source_id BIGINT NOT NULL,
 target_id BIGINT NOT NULL,
 PRIMARY KEY(plan_id,entity_name,source_id),
 CONSTRAINT uq_v3_copy_target_id UNIQUE(entity_name,target_id),
 CONSTRAINT ck_v3_copy_entity CHECK(entity_name IN ('ledger_entries','domain_events')),
 CONSTRAINT ck_v3_copy_ids CHECK(source_id>0 AND target_id>0)
);
