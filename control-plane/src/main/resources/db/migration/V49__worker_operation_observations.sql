CREATE TABLE worker_operation_observations (
    operation_id UUID PRIMARY KEY REFERENCES worker_operations (id) ON DELETE CASCADE,
    operator_ready BOOLEAN NOT NULL DEFAULT FALSE,
    operator_spec_digest TEXT NOT NULL DEFAULT '',
    operator_runtime TEXT NOT NULL DEFAULT '',
    operator_config_revision TEXT NOT NULL DEFAULT '',
    operator_secret_generation TEXT NOT NULL DEFAULT '',
    operator_observed_at TIMESTAMPTZ,
    gateway_online BOOLEAN NOT NULL DEFAULT FALSE,
    gateway_spec_digest TEXT NOT NULL DEFAULT '',
    gateway_runtime TEXT NOT NULL DEFAULT '',
    gateway_config_revision TEXT NOT NULL DEFAULT '',
    gateway_secret_generation TEXT NOT NULL DEFAULT '',
    gateway_observed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT worker_operation_observations_operator_digest_check
        CHECK (length(operator_spec_digest) <= 512),
    CONSTRAINT worker_operation_observations_gateway_digest_check
        CHECK (length(gateway_spec_digest) <= 512)
);
