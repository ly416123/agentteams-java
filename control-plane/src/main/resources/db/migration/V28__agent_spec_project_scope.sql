ALTER TABLE agent_specs
    ADD COLUMN tenant_id TEXT,
    ADD COLUMN project_id TEXT;

CREATE INDEX agent_specs_scope_idx
    ON agent_specs (tenant_id, project_id, name);
