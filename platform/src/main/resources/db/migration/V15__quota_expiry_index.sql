-- Quota cleanup is ordered by time, rather than scanning every merchant's leading PK.
CREATE INDEX ix_rate_expiry ON api_rate_limits(window_second,key_id);
