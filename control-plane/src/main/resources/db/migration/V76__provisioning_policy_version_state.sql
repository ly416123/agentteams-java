CREATE TABLE provisioning_policy_versions (
    integration_id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT provisioning_policy_versions_version_non_negative CHECK (version >= 0)
);
