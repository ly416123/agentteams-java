-- Project scope values were historically persisted as either project.name or project.id.
-- Only an unambiguous tenant-local name match is migrated. Unknown/global values remain intact.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM project_quota_policies old
          JOIN projects p ON p.tenant_id = old.tenant_id AND p.name = old.project_id
          JOIN project_quota_policies canonical
            ON canonical.tenant_id = old.tenant_id AND canonical.project_id = p.id::text
         WHERE old.project_id <> p.id::text
    ) THEN
        RAISE EXCEPTION 'project scope migration conflict in project_quota_policies';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM worker_templates old
          JOIN projects p ON p.tenant_id = old.tenant_id AND p.name = old.project_id
          JOIN worker_templates canonical
            ON canonical.tenant_id = old.tenant_id AND canonical.project_id = p.id::text
                                           AND canonical.name = old.name
         WHERE old.project_id <> p.id::text
    ) THEN
        RAISE EXCEPTION 'project scope migration conflict in worker_templates';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM model_price_catalog old
          JOIN projects p ON p.tenant_id = old.tenant_id AND p.name = old.project_id
          JOIN model_price_catalog canonical
            ON canonical.tenant_id = old.tenant_id AND canonical.project_id = p.id::text
                                           AND canonical.provider = old.provider
                                           AND canonical.model = old.model
                                           AND canonical.currency = old.currency
                                           AND canonical.effective_from = old.effective_from
         WHERE old.project_id <> p.id::text
    ) THEN
        RAISE EXCEPTION 'project scope migration conflict in model_price_catalog';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM quota_reservations old
          JOIN projects p ON p.tenant_id = old.tenant_id AND p.name = old.project_id
          JOIN quota_reservations canonical
            ON canonical.tenant_id = old.tenant_id AND canonical.project_id = p.id::text
                                           AND canonical.acquire_idempotency_key = old.acquire_idempotency_key
         WHERE old.project_id <> p.id::text
    ) THEN
        RAISE EXCEPTION 'project scope migration conflict in quota_reservations';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM model_price_catalog_idempotency old
          JOIN projects p ON p.tenant_id = old.tenant_id AND p.name = old.project_id
          JOIN model_price_catalog_idempotency canonical
            ON canonical.tenant_id = old.tenant_id AND canonical.project_id = p.id::text
                                           AND canonical.idempotency_key = old.idempotency_key
         WHERE old.project_id <> p.id::text
    ) THEN
        RAISE EXCEPTION 'project scope migration conflict in model_price_catalog_idempotency';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM quota_reservation_releases old
          JOIN projects p ON p.tenant_id = old.tenant_id AND p.name = old.project_id
          JOIN quota_reservation_releases canonical
            ON canonical.tenant_id = old.tenant_id AND canonical.project_id = p.id::text
                                           AND canonical.idempotency_key = old.idempotency_key
         WHERE old.project_id <> p.id::text
    ) THEN
        RAISE EXCEPTION 'project scope migration conflict in quota_reservation_releases';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM artifact_retention_project_policies old
          JOIN projects p ON p.tenant_id = old.tenant_id AND p.name = old.project_id
          JOIN artifact_retention_project_policies canonical
            ON canonical.tenant_id = old.tenant_id AND canonical.project_id = p.id::text
         WHERE old.project_id <> p.id::text
    ) THEN
        RAISE EXCEPTION 'project scope migration conflict in artifact_retention_project_policies';
    END IF;
END $$;

UPDATE resource_scopes s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;

UPDATE agent_specs s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;

UPDATE operation_audit_events s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;

UPDATE model_call_audits s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;

UPDATE project_quota_policies s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;

UPDATE model_price_catalog_idempotency s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;
UPDATE model_price_catalog s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;

UPDATE quota_reservations s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;
UPDATE quota_reservation_releases s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;

UPDATE dashboard_alert_events s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;
UPDATE dashboard_alert_retry_requests s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;
UPDATE dashboard_alert_rules s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;

UPDATE usage_budget_evaluations s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;
UPDATE usage_budget_events s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;
UPDATE usage_budget_policies s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;

UPDATE worker_templates s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;

UPDATE skill_bindings s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;
UPDATE memories s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;
UPDATE token_ledger_reservations s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;
UPDATE token_ledger_entries s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;

UPDATE scheduled_tasks s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;
UPDATE webhook_subscriptions s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;
UPDATE matrix_channel_bindings s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;
UPDATE artifact_retention_project_policies s SET project_id = p.id::text
  FROM projects p
 WHERE s.tenant_id = p.tenant_id AND s.project_id = p.name AND s.project_id <> p.id::text;
