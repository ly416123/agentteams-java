CREATE TABLE config_snapshots (
    id UUID PRIMARY KEY,
    subject TEXT NOT NULL,
    version BIGINT NOT NULL,
    manifest JSONB NOT NULL,
    checksum TEXT NOT NULL,
    actor TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (subject, version),
    UNIQUE (subject, checksum),
    CONSTRAINT config_snapshots_version_positive CHECK (version > 0),
    CONSTRAINT config_snapshots_manifest_object CHECK (jsonb_typeof(manifest) = 'object')
);

CREATE TABLE config_files (
    id UUID PRIMARY KEY,
    snapshot_id UUID NOT NULL REFERENCES config_snapshots(id),
    path TEXT NOT NULL,
    storage_key TEXT NOT NULL,
    checksum TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    content_type TEXT NOT NULL,
    UNIQUE (snapshot_id, path),
    CONSTRAINT config_files_size_non_negative CHECK (size_bytes >= 0)
);

CREATE TABLE config_bindings (
    id UUID PRIMARY KEY,
    subject TEXT NOT NULL,
    agent_id UUID NOT NULL REFERENCES agents(id),
    snapshot_id UUID NOT NULL REFERENCES config_snapshots(id),
    desired_at TIMESTAMPTZ NOT NULL,
    UNIQUE (subject, agent_id)
);

CREATE TABLE config_apply_records (
    id UUID PRIMARY KEY,
    binding_id UUID NOT NULL REFERENCES config_bindings(id),
    agent_id UUID NOT NULL REFERENCES agents(id),
    snapshot_id UUID NOT NULL REFERENCES config_snapshots(id),
    phase TEXT NOT NULL,
    error_message TEXT,
    applied_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (binding_id, snapshot_id)
);
