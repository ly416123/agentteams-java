ALTER TABLE config_apply_records
    ADD COLUMN rollback BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX config_apply_records_rollback_idx
    ON config_apply_records (binding_id, rollback, phase, updated_at DESC);
