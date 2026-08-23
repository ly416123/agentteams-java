CREATE TABLE model_providers (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    provider_type TEXT NOT NULL,
    endpoint TEXT NOT NULL,
    credential_ref TEXT,
    settings JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT model_providers_version_non_negative CHECK (version >= 0),
    CONSTRAINT model_providers_settings_object CHECK (jsonb_typeof(settings) = 'object')
);

CREATE INDEX model_providers_enabled_idx ON model_providers (enabled, name);

CREATE TABLE models (
    id UUID PRIMARY KEY,
    provider_id UUID NOT NULL REFERENCES model_providers (id),
    name TEXT NOT NULL,
    model_id TEXT NOT NULL,
    capabilities JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT models_version_non_negative CHECK (version >= 0),
    CONSTRAINT models_capabilities_object CHECK (jsonb_typeof(capabilities) = 'object'),
    CONSTRAINT models_provider_name_unique UNIQUE (provider_id, name)
);

CREATE INDEX models_provider_enabled_idx ON models (provider_id, enabled, name);
