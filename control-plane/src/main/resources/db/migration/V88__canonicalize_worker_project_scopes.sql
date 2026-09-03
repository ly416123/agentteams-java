-- Worker resource scopes must use the stable Project UUID, matching Console routes.
UPDATE resource_scopes AS rs
SET project_id = p.id::text,
    updated_at = CURRENT_TIMESTAMP
FROM projects AS p
WHERE rs.resource_type = 'WORKER'
  AND rs.project_id = p.name
  AND rs.tenant_id = p.tenant_id;
