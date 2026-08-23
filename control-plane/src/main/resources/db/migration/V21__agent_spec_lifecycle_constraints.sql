ALTER TABLE agent_specs
    ADD CONSTRAINT agent_specs_desired_state_check
        CHECK (desired_state IN ('RUNNING', 'STOPPED')),
    ADD CONSTRAINT agent_specs_lifecycle_status_check
        CHECK (lifecycle_status IN ('DRAFT', 'PUBLISHED', 'DISABLED'));
