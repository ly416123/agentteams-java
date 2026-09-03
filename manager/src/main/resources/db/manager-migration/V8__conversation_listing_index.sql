CREATE INDEX conversation_sessions_owner_project_updated_idx
    ON conversation_sessions (tenant_id, project_id, actor_subject, updated_at DESC, id DESC);
