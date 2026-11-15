-- Core-owned, immutable evidence. Current-serving status also checks live GTIDs.
CREATE TABLE v3_analytics_generation_publications (
 generation VARCHAR(36) PRIMARY KEY,
 evidence_hash VARCHAR(64) NOT NULL,
 evidence_payload TEXT NOT NULL,
 signature VARCHAR(64) NOT NULL,
 published_by VARCHAR(160) NOT NULL,
 published_at TIMESTAMP(6) NOT NULL
);
