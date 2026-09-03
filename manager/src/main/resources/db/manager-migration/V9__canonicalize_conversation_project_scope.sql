-- Conversation/session project scopes are text for backwards compatibility.
-- Resolve only tenant-local project names; null/unknown/global values are not touched.
UPDATE conversation_sessions s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;

UPDATE manager_sessions s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;
