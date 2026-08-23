ALTER TABLE config_apply_records
    ADD COLUMN observed_version BIGINT,
    ADD COLUMN failure_code TEXT;

ALTER TABLE config_apply_records
    ADD CONSTRAINT config_apply_records_observed_version_positive
    CHECK (observed_version IS NULL OR observed_version > 0);

CREATE INDEX config_apply_records_failure_idx
    ON config_apply_records (binding_id, phase, failure_code, updated_at DESC);
