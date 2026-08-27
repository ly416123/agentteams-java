ALTER TABLE gateway_agent_state
    ADD COLUMN spec_digest TEXT NOT NULL DEFAULT '',
    ADD COLUMN config_revision TEXT NOT NULL DEFAULT '',
    ADD COLUMN secret_generation TEXT NOT NULL DEFAULT '';

ALTER TABLE gateway_agent_state
    ADD CONSTRAINT gateway_agent_state_version_lengths CHECK (
        length(spec_digest) <= 512
        AND length(config_revision) <= 512
        AND length(secret_generation) <= 512
    );
