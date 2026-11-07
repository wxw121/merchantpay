CREATE TABLE v3_shard_identity (
 id INT PRIMARY KEY,
 physical_shard INT NOT NULL,
 CONSTRAINT ck_v3_identity_single CHECK (id=1),
 CONSTRAINT ck_v3_identity_shard CHECK (physical_shard BETWEEN 0 AND 3)
);
INSERT INTO v3_shard_identity(id,physical_shard) VALUES (1,0);

CREATE TABLE v3_bucket_fences (
 bucket INT PRIMARY KEY,
 route_epoch BIGINT NOT NULL,
 access_state VARCHAR(16) NOT NULL,
 CONSTRAINT ck_v3_fence_bucket CHECK (bucket BETWEEN 0 AND 15),
 CONSTRAINT ck_v3_fence_epoch CHECK (route_epoch>0),
 CONSTRAINT ck_v3_fence_state CHECK (access_state IN ('ACTIVE','FENCED'))
);
-- An upgraded legacy core owns all existing buckets. Additional targets must be
-- provisioned and verified separately; applying migrations never grants shard 1..3 ownership.
INSERT INTO v3_bucket_fences(bucket,route_epoch,access_state)
 SELECT bucket,epoch,'ACTIVE' FROM v3_shard_routes WHERE physical_shard=0;
