ALTER TABLE skill_versions
    ADD COLUMN package_storage_key TEXT,
    ADD COLUMN package_size_bytes BIGINT,
    ADD COLUMN package_sha256 TEXT,
    ADD COLUMN package_upload_status TEXT NOT NULL DEFAULT 'NOT_STARTED';

ALTER TABLE skill_versions
    ADD CONSTRAINT skill_versions_package_upload_status_check
        CHECK (package_upload_status IN ('NOT_STARTED', 'PENDING', 'COMPLETED')),
    ADD CONSTRAINT skill_versions_package_size_non_negative
        CHECK (package_size_bytes IS NULL OR package_size_bytes >= 0),
    ADD CONSTRAINT skill_versions_package_metadata_complete
        CHECK ((package_storage_key IS NULL AND package_size_bytes IS NULL AND package_sha256 IS NULL)
            OR (package_storage_key IS NOT NULL AND package_size_bytes IS NOT NULL AND package_sha256 IS NOT NULL)),
    ADD CONSTRAINT skill_versions_package_status_metadata_check
        CHECK ((package_upload_status = 'NOT_STARTED'
                AND package_storage_key IS NULL AND package_size_bytes IS NULL AND package_sha256 IS NULL)
            OR (package_upload_status IN ('PENDING', 'COMPLETED')
                AND package_storage_key IS NOT NULL AND package_size_bytes IS NOT NULL
                AND package_sha256 IS NOT NULL AND package_sha256 ~ '^[0-9a-f]{64}$'));

CREATE INDEX skill_versions_package_status_idx ON skill_versions(package_upload_status, updated_at);
