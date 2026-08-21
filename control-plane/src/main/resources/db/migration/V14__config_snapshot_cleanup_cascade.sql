ALTER TABLE config_files
    DROP CONSTRAINT config_files_snapshot_id_fkey,
    ADD CONSTRAINT config_files_snapshot_id_fkey
        FOREIGN KEY (snapshot_id) REFERENCES config_snapshots(id) ON DELETE CASCADE;

ALTER TABLE config_uploads
    DROP CONSTRAINT config_uploads_snapshot_id_fkey,
    ADD CONSTRAINT config_uploads_snapshot_id_fkey
        FOREIGN KEY (snapshot_id) REFERENCES config_snapshots(id) ON DELETE CASCADE;

ALTER TABLE config_apply_records
    DROP CONSTRAINT config_apply_records_snapshot_id_fkey,
    ADD CONSTRAINT config_apply_records_snapshot_id_fkey
        FOREIGN KEY (snapshot_id) REFERENCES config_snapshots(id) ON DELETE CASCADE;
