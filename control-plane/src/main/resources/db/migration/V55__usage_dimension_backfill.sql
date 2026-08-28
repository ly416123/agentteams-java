-- Backfill only dimensions that can be derived from durable task facts.
-- Every statement is idempotent: existing values are never replaced and
-- ambiguous relationships are intentionally left unresolved.

WITH task_scopes AS (
    SELECT a.id,
           NULLIF(BTRIM(t.spec #>> '{scope,tenant}'), '') AS tenant_id,
           NULLIF(BTRIM(t.spec #>> '{scope,project}'), '') AS project_id
      FROM model_call_audits a
      JOIN tasks t ON t.id::text = BTRIM(a.task_id)
     WHERE a.task_id IS NOT NULL
), eligible AS (
    SELECT id, tenant_id, project_id
      FROM task_scopes
     WHERE tenant_id IS NOT NULL
       AND project_id IS NOT NULL
)
UPDATE model_call_audits a
   SET tenant_id = CASE WHEN NULLIF(BTRIM(a.tenant_id), '') IS NULL
                        THEN e.tenant_id ELSE a.tenant_id END,
       project_id = CASE WHEN NULLIF(BTRIM(a.project_id), '') IS NULL
                          THEN e.project_id ELSE a.project_id END
  FROM eligible e
 WHERE a.id = e.id
   AND (NULLIF(BTRIM(a.tenant_id), '') IS NULL OR BTRIM(a.tenant_id) = e.tenant_id)
   AND (NULLIF(BTRIM(a.project_id), '') IS NULL OR BTRIM(a.project_id) = e.project_id);

WITH task_teams AS (
    SELECT a.id,
           NULLIF(BTRIM(t.spec ->> 'teamId'), '') AS team_id
      FROM model_call_audits a
      JOIN tasks t ON t.id::text = BTRIM(a.task_id)
     WHERE a.task_id IS NOT NULL
), eligible AS (
    SELECT id, team_id
      FROM task_teams
     WHERE team_id IS NOT NULL
)
UPDATE model_call_audits a
   SET team_id = e.team_id
  FROM eligible e
 WHERE a.id = e.id
   AND NULLIF(BTRIM(a.team_id), '') IS NULL;

WITH unique_team_links AS (
    SELECT a.id, MIN(tt.team_id::text) AS team_id
      FROM model_call_audits a
      JOIN team_tasks tt ON tt.task_id::text = BTRIM(a.task_id)
     WHERE a.task_id IS NOT NULL
       AND NULLIF(BTRIM(a.team_id), '') IS NULL
     GROUP BY a.id
    HAVING COUNT(*) = 1
)
UPDATE model_call_audits a
   SET team_id = e.team_id
  FROM unique_team_links e
 WHERE a.id = e.id
   AND NULLIF(BTRIM(a.team_id), '') IS NULL;

WITH assignment_candidates AS (
    SELECT a.id,
           ta.agent_id::text AS worker_id,
           COUNT(*) OVER (PARTITION BY a.id) AS candidate_count
      FROM model_call_audits a
      JOIN task_assignments ta ON ta.task_id::text = BTRIM(a.task_id)
     WHERE a.task_id IS NOT NULL
       AND NULLIF(BTRIM(a.worker_id), '') IS NULL
       AND a.occurred_at >= ta.assigned_at
       AND (ta.released_at IS NULL OR a.occurred_at <= ta.released_at)
), unique_assignments AS (
    SELECT id, worker_id
      FROM assignment_candidates
     WHERE candidate_count = 1
)
UPDATE model_call_audits a
   SET worker_id = e.worker_id
  FROM unique_assignments e
 WHERE a.id = e.id
   AND NULLIF(BTRIM(a.worker_id), '') IS NULL;
