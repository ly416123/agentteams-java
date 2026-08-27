CREATE TABLE runtime_resource_apply_records (
    binding_id UUID NOT NULL REFERENCES config_bindings(id) ON DELETE CASCADE,
    snapshot_id UUID NOT NULL REFERENCES config_snapshots(id) ON DELETE CASCADE,
    agent_id UUID NOT NULL REFERENCES agents(id),
    config_version BIGINT NOT NULL,
    resource_type TEXT NOT NULL,
    resource_id TEXT NOT NULL,
    resource_revision TEXT NOT NULL,
    expected_digest TEXT NOT NULL,
    observed_digest TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL,
    failure_category TEXT NOT NULL DEFAULT '',
    observed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (binding_id, resource_type, resource_id, resource_revision),
    CONSTRAINT runtime_resource_apply_config_version_positive CHECK (config_version > 0),
    CONSTRAINT runtime_resource_apply_status_check CHECK (status IN ('APPLIED', 'REJECTED', 'FAILED')),
    CONSTRAINT runtime_resource_apply_failure_check CHECK (failure_category IN ('', 'NOT_VISIBLE',
        'NOT_PUBLISHED', 'DIGEST_MISMATCH', 'DOWNLOAD_FAILED', 'AUTH_UNAVAILABLE',
        'POLICY_REJECTED', 'RUNTIME_UNSUPPORTED')),
    CONSTRAINT runtime_resource_apply_applied_failure_check CHECK (
        (status = 'APPLIED' AND failure_category = '') OR
        (status <> 'APPLIED' AND failure_category <> '')
    )
);

CREATE INDEX runtime_resource_apply_revision_idx
    ON runtime_resource_apply_records (binding_id, config_version, observed_at DESC);
