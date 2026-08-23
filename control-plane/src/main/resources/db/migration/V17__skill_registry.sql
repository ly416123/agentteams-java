CREATE TABLE skills (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    visibility TEXT NOT NULL DEFAULT 'PRIVATE',
    lifecycle TEXT NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT skills_visibility_allowed CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    CONSTRAINT skills_lifecycle_allowed CHECK (lifecycle IN ('DRAFT', 'PUBLISHED', 'DISABLED')),
    CONSTRAINT skills_version_non_negative CHECK (version >= 0),
    CONSTRAINT skills_name_non_blank CHECK (length(trim(name)) > 0)
);

CREATE TABLE skill_versions (
    id UUID PRIMARY KEY,
    skill_id UUID NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    version TEXT NOT NULL,
    digest TEXT NOT NULL,
    manifest JSONB NOT NULL,
    visibility TEXT NOT NULL DEFAULT 'PRIVATE',
    lifecycle TEXT NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    record_version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (skill_id, version),
    UNIQUE (skill_id, digest),
    CONSTRAINT skill_versions_visibility_allowed CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    CONSTRAINT skill_versions_lifecycle_allowed CHECK (lifecycle IN ('DRAFT', 'PUBLISHED', 'DISABLED')),
    CONSTRAINT skill_versions_manifest_object CHECK (jsonb_typeof(manifest) = 'object'),
    CONSTRAINT skill_versions_record_version_non_negative CHECK (record_version >= 0),
    CONSTRAINT skill_versions_digest_non_blank CHECK (length(trim(digest)) > 0),
    CONSTRAINT skill_versions_version_non_blank CHECK (length(trim(version)) > 0)
);

CREATE INDEX skill_versions_skill_idx ON skill_versions(skill_id, created_at, id);
CREATE INDEX skill_versions_lifecycle_idx ON skill_versions(skill_id, lifecycle);

CREATE TABLE skill_idempotency_keys (
    idempotency_key TEXT PRIMARY KEY,
    operation TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    resource_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT skill_idempotency_key_non_blank CHECK (length(trim(idempotency_key)) > 0),
    CONSTRAINT skill_idempotency_operation_non_blank CHECK (length(trim(operation)) > 0)
);
