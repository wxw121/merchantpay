-- Stop all old writers before migrating/enabling slot mode. UNKNOWN keeps its ownership.
CREATE TABLE payment_admission_mode (
    id INT PRIMARY KEY,
    slot_mode BOOLEAN NOT NULL,
    CONSTRAINT ck_admission_mode CHECK (id=1)
);
INSERT INTO payment_admission_mode(id,slot_mode) VALUES (1,FALSE);
CREATE TABLE payment_admission_slots (
    logical_shard INT NOT NULL,
    slot_no INT NOT NULL,
    payment_id VARCHAR(64) UNIQUE,
    PRIMARY KEY (logical_shard, slot_no),
    CONSTRAINT ck_slot_shard CHECK (logical_shard >= 0 AND logical_shard < 16),
    CONSTRAINT ck_slot_number CHECK (slot_no >= 0)
);
INSERT INTO payment_admission_slots(logical_shard,slot_no,payment_id)
SELECT logical_shard, ROW_NUMBER() OVER (PARTITION BY logical_shard ORDER BY id)-1, id
FROM payments WHERE status NOT IN ('SUCCEEDED','FAILED','CLOSED');
INSERT INTO payment_admission_slots(logical_shard,slot_no)
SELECT a.logical_shard, b.logical_shard FROM payment_admission a CROSS JOIN payment_admission b
WHERE NOT EXISTS (
    SELECT 1 FROM payment_admission_slots s
    WHERE s.logical_shard=a.logical_shard AND s.slot_no=b.logical_shard
);
CREATE INDEX ix_admission_free ON payment_admission_slots(logical_shard,payment_id,slot_no);
