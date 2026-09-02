ALTER TABLE model_call_audits
    ADD COLUMN organization_id TEXT;

UPDATE model_call_audits audits
   SET organization_id = mappings.organization_id::text
  FROM legacy_tenant_mappings mappings
 WHERE mappings.legacy_tenant_key = audits.tenant_id;

CREATE INDEX model_call_audits_organization_usage_idx
    ON model_call_audits (organization_id, occurred_at DESC);
