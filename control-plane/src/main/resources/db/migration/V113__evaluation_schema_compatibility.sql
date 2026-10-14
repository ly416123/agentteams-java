-- Repair databases where an older evaluation schema was recorded as already
-- migrated. Keep legacy columns and rows, while adding the fields used by the
-- current EvaluationRepository contract.
ALTER TABLE evaluation_suites
    ADD COLUMN IF NOT EXISTS suite_key TEXT,
    ADD COLUMN IF NOT EXISTS evaluator_version TEXT DEFAULT 'deterministic-v1';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_name = 'evaluation_suites' AND column_name = 'name') THEN
        EXECUTE 'UPDATE evaluation_suites SET suite_key = COALESCE(suite_key, name)';
        EXECUTE 'ALTER TABLE evaluation_suites ALTER COLUMN name DROP NOT NULL';
    END IF;
    EXECUTE 'UPDATE evaluation_suites SET suite_key = COALESCE(suite_key, id::text)';
    EXECUTE 'UPDATE evaluation_suites SET evaluator_version = COALESCE(evaluator_version, ''deterministic-v1'')';
    EXECUTE 'ALTER TABLE evaluation_suites ALTER COLUMN suite_key SET NOT NULL';
    EXECUTE 'ALTER TABLE evaluation_suites ALTER COLUMN evaluator_version SET NOT NULL';
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_name = 'evaluation_suites' AND column_name = 'team_id') THEN
        EXECUTE 'ALTER TABLE evaluation_suites ALTER COLUMN team_id DROP NOT NULL';
    END IF;
END $$;

ALTER TABLE evaluation_cases
    ADD COLUMN IF NOT EXISTS task_spec JSONB,
    ADD COLUMN IF NOT EXISTS assertions JSONB DEFAULT '[]'::jsonb;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_name = 'evaluation_cases' AND column_name = 'input_json') THEN
        EXECUTE 'UPDATE evaluation_cases SET task_spec = COALESCE(task_spec, input_json, ''{}''::jsonb)';
        EXECUTE 'ALTER TABLE evaluation_cases ALTER COLUMN input_json DROP NOT NULL';
    ELSE
        EXECUTE 'UPDATE evaluation_cases SET task_spec = COALESCE(task_spec, ''{}''::jsonb)';
    END IF;
    EXECUTE 'UPDATE evaluation_cases SET assertions = COALESCE(assertions, ''[]''::jsonb)';
    EXECUTE 'ALTER TABLE evaluation_cases ALTER COLUMN task_spec SET NOT NULL';
    EXECUTE 'ALTER TABLE evaluation_cases ALTER COLUMN task_spec SET DEFAULT ''{}''::jsonb';
    EXECUTE 'ALTER TABLE evaluation_cases ALTER COLUMN assertions SET NOT NULL';
    EXECUTE 'ALTER TABLE evaluation_cases ALTER COLUMN assertions SET DEFAULT ''[]''::jsonb';
END $$;

ALTER TABLE evaluation_runs
    ADD COLUMN IF NOT EXISTS organization_id TEXT,
    ADD COLUMN IF NOT EXISTS tenant_id TEXT,
    ADD COLUMN IF NOT EXISTS project_id TEXT,
    ADD COLUMN IF NOT EXISTS team_id TEXT;

UPDATE evaluation_runs run
   SET organization_id = COALESCE(run.organization_id, suite.organization_id),
       tenant_id = COALESCE(run.tenant_id, suite.tenant_id),
       project_id = COALESCE(run.project_id, suite.project_id)
  FROM evaluation_suites suite
 WHERE suite.id = run.suite_id;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_name = 'evaluation_suites' AND column_name = 'team_id') THEN
        EXECUTE 'UPDATE evaluation_runs run SET team_id = COALESCE(run.team_id, suite.team_id) '
             || 'FROM evaluation_suites suite WHERE suite.id = run.suite_id';
    END IF;
    EXECUTE 'UPDATE evaluation_runs SET team_id = COALESCE(team_id, ''legacy-team'')';
    EXECUTE 'ALTER TABLE evaluation_runs ALTER COLUMN organization_id SET NOT NULL';
    EXECUTE 'ALTER TABLE evaluation_runs ALTER COLUMN tenant_id SET NOT NULL';
    EXECUTE 'ALTER TABLE evaluation_runs ALTER COLUMN project_id SET NOT NULL';
    EXECUTE 'ALTER TABLE evaluation_runs ALTER COLUMN team_id SET NOT NULL';
END $$;

ALTER TABLE evaluation_reports
    ADD COLUMN IF NOT EXISTS aggregate_score NUMERIC(8, 5),
    ADD COLUMN IF NOT EXISTS evaluation_status TEXT,
    ADD COLUMN IF NOT EXISTS task_success BOOLEAN,
    ADD COLUMN IF NOT EXISTS execution_gate_status VARCHAR(24) DEFAULT 'INCONCLUSIVE',
    ADD COLUMN IF NOT EXISTS result_gate_status VARCHAR(24) DEFAULT 'INCONCLUSIVE';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_name = 'evaluation_reports' AND column_name = 'status') THEN
        EXECUTE 'UPDATE evaluation_reports SET evaluation_status = COALESCE(evaluation_status, status)';
        EXECUTE 'ALTER TABLE evaluation_reports ALTER COLUMN status DROP NOT NULL';
    END IF;
    EXECUTE 'UPDATE evaluation_reports report '
         || 'SET evaluation_status = COALESCE(report.evaluation_status, run.status), '
         || 'task_success = COALESCE(report.task_success, run.status = ''PASSED''), '
         || 'execution_gate_status = COALESCE(report.execution_gate_status, ''INCONCLUSIVE''), '
         || 'result_gate_status = COALESCE(report.result_gate_status, ''INCONCLUSIVE'') '
         || 'FROM evaluation_runs run WHERE run.id = report.evaluation_run_id';
    EXECUTE 'ALTER TABLE evaluation_reports ALTER COLUMN evaluation_status SET NOT NULL';
    EXECUTE 'ALTER TABLE evaluation_reports ALTER COLUMN task_success SET NOT NULL';
    EXECUTE 'ALTER TABLE evaluation_reports ALTER COLUMN execution_gate_status SET NOT NULL';
    EXECUTE 'ALTER TABLE evaluation_reports ALTER COLUMN result_gate_status SET NOT NULL';
END $$;

CREATE INDEX IF NOT EXISTS evaluation_runs_scope_idx
    ON evaluation_runs (organization_id, tenant_id, project_id, team_id);
CREATE INDEX IF NOT EXISTS evaluation_runs_task_idx
    ON evaluation_runs (tenant_id, task_id, execution_run_id);
CREATE INDEX IF NOT EXISTS evaluation_runs_status_idx
    ON evaluation_runs (tenant_id, status, created_at);
