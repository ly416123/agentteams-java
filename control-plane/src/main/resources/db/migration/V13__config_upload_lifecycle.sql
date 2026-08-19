CREATE TABLE config_uploads (
    id UUID PRIMARY KEY,
    snapshot_id UUID NOT NULL REFERENCES config_snapshots(id),
    path TEXT NOT NULL,
    storage_key TEXT NOT NULL UNIQUE,
    content_type TEXT NOT NULL,
    expected_checksum TEXT NOT NULL,
    expected_size_bytes BIGINT NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT config_uploads_size_non_negative CHECK (expected_size_bytes >= 0),
    CONSTRAINT config_uploads_status_valid CHECK (status IN ('PENDING', 'COMPLETED', 'DELETED')),
    UNIQUE (snapshot_id, path)
);

CREATE INDEX config_uploads_expiry_idx ON config_uploads (status, expires_at);
