CREATE TABLE payment_admission (
    logical_shard INT PRIMARY KEY,
    inflight BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_admission_shard CHECK (logical_shard >= 0 AND logical_shard < 16),
    CONSTRAINT ck_admission_inflight CHECK (inflight >= 0)
);
INSERT INTO payment_admission(logical_shard) VALUES
    (0),(1),(2),(3),(4),(5),(6),(7),(8),(9),(10),(11),(12),(13),(14),(15);
-- Run with old writers stopped. Existing UNKNOWN and CLOSE_PENDING still consume capacity.
UPDATE payment_admission SET inflight=(
    SELECT COUNT(*) FROM payments
    WHERE payments.logical_shard=payment_admission.logical_shard
      AND payments.status NOT IN ('SUCCEEDED','FAILED','CLOSED')
);
