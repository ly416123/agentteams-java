CREATE INDEX agent_specs_model_provider_dependency_idx
    ON agent_specs (model_provider, lifecycle_status, model_name);
