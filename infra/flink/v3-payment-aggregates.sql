-- Flink SQL 1.20. Run only after all four analytics CDC snapshots are complete.
-- Replace placeholders with deployment values. Use a new job/transactional prefix for rebuilds.
SET 'execution.checkpointing.interval' = '30s';
SET 'execution.checkpointing.mode' = 'EXACTLY_ONCE';
SET 'execution.checkpointing.storage' = 'filesystem';
SET 'execution.checkpointing.dir' = '__CHECKPOINT_URI__';
SET 'execution.checkpointing.savepoint-dir' = '__SAVEPOINT_URI__';
SET 'state.backend.type' = 'rocksdb';
SET 'table.exec.source.idle-timeout' = '60s';

CREATE TABLE payment_changes (
  id STRING,
  merchant_id STRING,
  logical_shard INT,
  amount_minor BIGINT,
  refunded_minor BIGINT,
  status STRING,
  channel STRING,
  created_at STRING,
  updated_at STRING,
  source_topic STRING METADATA FROM 'topic' VIRTUAL
) WITH (
  'connector' = 'kafka',
  'topic-pattern' = 'merchantpay-v3-analytics-s[0-3][.]__DATABASE__[.]payments',
  'properties.bootstrap.servers' = '__KAFKA_BOOTSTRAP__',
  'properties.security.protocol' = 'SSL',
  'properties.group.id' = '__ANALYTICS_GROUP__',
  'scan.startup.mode' = 'earliest-offset',
  'value.format' = 'debezium-json'
);

CREATE TABLE route_changes (
  bucket INT,
  physical_shard INT,
  epoch BIGINT,
  PRIMARY KEY (bucket) NOT ENFORCED
) WITH (
  'connector' = 'kafka',
  'topic' = 'merchantpay-v3-analytics-s0.__DATABASE__.v3_shard_routes',
  'properties.bootstrap.servers' = '__KAFKA_BOOTSTRAP__',
  'properties.security.protocol' = 'SSL',
  'properties.group.id' = '__ANALYTICS_GROUP__-routes',
  'scan.startup.mode' = 'earliest-offset',
  'value.format' = 'debezium-json'
);

-- A copied payment exists in both physical databases. The core route CDC selects
-- its current owner; route changes retract old contributions and add new ones.
-- The generation is a deployment token. Rebuild into a new token and topic.
CREATE VIEW owned_payments AS
SELECT '__GENERATION__' AS generation, p.id AS payment_id, p.merchant_id,
  CAST(SUBSTRING(CONVERT_TZ(
    REPLACE(SUBSTRING(p.created_at, 1, 19), 'T', ' '),
    'UTC', 'Asia/Shanghai'), 1, 10) AS DATE) AS business_day,
  p.channel, p.status, p.amount_minor, p.refunded_minor, p.updated_at
FROM payment_changes AS p
JOIN route_changes AS r
  ON r.bucket = p.logical_shard
 AND r.physical_shard = CAST(SUBSTRING(p.source_topic,
      CHAR_LENGTH('merchantpay-v3-analytics-s') + 1, 1) AS INT);

CREATE TABLE payment_aggregate_upserts (
  generation STRING,
  merchant_id STRING,
  business_day STRING,
  channel STRING,
  payments BIGINT,
  succeeded BIGINT,
  succeeded_minor BIGINT,
  refunded_minor BIGINT,
  latest_source_updated_at STRING,
  PRIMARY KEY (generation, merchant_id, business_day, channel) NOT ENFORCED
) WITH (
  'connector' = 'upsert-kafka',
  'topic' = '__AGGREGATE_TOPIC__',
  'properties.bootstrap.servers' = '__KAFKA_BOOTSTRAP__',
  'properties.security.protocol' = 'SSL',
  'key.format' = 'json',
  'value.format' = 'json',
  'value.fields-include' = 'EXCEPT_KEY',
  'sink.delivery-guarantee' = 'exactly-once',
  'sink.transactional-id-prefix' = '__UNIQUE_ANALYTICS_TRANSACTION_PREFIX__'
);

INSERT INTO payment_aggregate_upserts
SELECT generation, merchant_id, CAST(business_day AS STRING), channel,
  COUNT(*),
  SUM(CASE WHEN status = 'SUCCEEDED' THEN CAST(1 AS BIGINT) ELSE CAST(0 AS BIGINT) END),
  SUM(CASE WHEN status = 'SUCCEEDED' THEN amount_minor ELSE CAST(0 AS BIGINT) END),
  SUM(refunded_minor),
  MAX(CONCAT(SUBSTRING(updated_at, 1, 19), 'Z'))
FROM owned_payments
GROUP BY generation, merchant_id, business_day, channel;
