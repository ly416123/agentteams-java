CREATE TABLE worker_templates (
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    name TEXT NOT NULL,
    display_name TEXT NOT NULL,
    current_published_revision BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, project_id, name),
    CONSTRAINT worker_templates_version_non_negative CHECK (version >= 0),
    CONSTRAINT worker_templates_current_revision_positive CHECK
        (current_published_revision IS NULL OR current_published_revision > 0)
);

CREATE TABLE worker_template_create_idempotency (
    idempotency_key TEXT PRIMARY KEY,
    request_hash TEXT NOT NULL,
    template_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT worker_template_create_idempotency_key_non_blank CHECK (length(trim(idempotency_key)) > 0),
    CONSTRAINT worker_template_create_idempotency_hash_non_blank CHECK (length(trim(request_hash)) > 0)
);

CREATE TABLE worker_template_revisions (
    template_id UUID NOT NULL REFERENCES worker_templates(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL,
    spec JSONB NOT NULL,
    digest TEXT NOT NULL,
    status TEXT NOT NULL,
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    idempotency_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    PRIMARY KEY (template_id, revision),
    UNIQUE (template_id, idempotency_key),
    CONSTRAINT worker_template_revisions_spec_object CHECK (jsonb_typeof(spec) = 'object'),
    CONSTRAINT worker_template_revisions_revision_positive CHECK (revision > 0),
    CONSTRAINT worker_template_revisions_digest_non_blank CHECK (length(trim(digest)) > 0),
    CONSTRAINT worker_template_revisions_status_valid CHECK
        (status IN ('DRAFT', 'REVIEWING', 'PUBLISHED', 'DEPRECATED')),
    CONSTRAINT worker_template_revisions_version_non_negative CHECK (version >= 0),
    CONSTRAINT worker_template_revisions_request_hash_non_blank CHECK (length(trim(request_hash)) > 0)
);

ALTER TABLE worker_templates ADD CONSTRAINT worker_templates_current_revision_fk
    FOREIGN KEY (id, current_published_revision)
    REFERENCES worker_template_revisions(template_id, revision);
CREATE UNIQUE INDEX worker_template_revisions_one_published
    ON worker_template_revisions(template_id) WHERE status = 'PUBLISHED';

CREATE TABLE worker_template_operations (
    template_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    operation TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (template_id, revision, operation, idempotency_key),
    FOREIGN KEY (template_id, revision) REFERENCES worker_template_revisions(template_id, revision),
    CONSTRAINT worker_template_operations_key_non_blank CHECK (length(trim(idempotency_key)) > 0),
    CONSTRAINT worker_template_operations_hash_non_blank CHECK (length(trim(request_hash)) > 0),
    CONSTRAINT worker_template_operations_name_valid CHECK (operation IN ('REVIEW', 'PUBLISH', 'UPGRADE'))
);

CREATE TABLE worker_template_instances (
    id UUID PRIMARY KEY,
    template_id UUID NOT NULL REFERENCES worker_templates(id) ON DELETE CASCADE,
    template_revision BIGINT NOT NULL,
    agent_spec_id UUID REFERENCES agent_specs(id),
    worker_id UUID REFERENCES agents(id),
    status TEXT NOT NULL,
    current_template_revision BIGINT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (template_id, idempotency_key),
    FOREIGN KEY (template_id, template_revision) REFERENCES worker_template_revisions(template_id, revision),
    FOREIGN KEY (template_id, current_template_revision) REFERENCES worker_template_revisions(template_id, revision),
    CONSTRAINT worker_template_instances_status_valid CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT worker_template_instances_revision_positive CHECK
        (template_revision > 0 AND current_template_revision > 0),
    CONSTRAINT worker_template_instances_version_non_negative CHECK (version >= 0)
);
