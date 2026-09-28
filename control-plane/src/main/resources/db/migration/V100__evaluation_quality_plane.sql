CREATE TABLE evaluation_suites (
    id UUID PRIMARY KEY,
    organization_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    suite_key TEXT NOT NULL,
    version TEXT NOT NULL,
    evaluator_version TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, project_id, suite_key, version)
);

CREATE TABLE evaluation_cases (
    id UUID PRIMARY KEY,
    suite_id UUID NOT NULL REFERENCES evaluation_suites(id) ON DELETE CASCADE,
    case_key TEXT NOT NULL,
    name TEXT NOT NULL,
    task_spec JSONB NOT NULL CHECK (jsonb_typeof(task_spec) = 'object'),
    assertions JSONB NOT NULL CHECK (jsonb_typeof(assertions) = 'array'),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (suite_id, case_key)
);

CREATE TABLE evaluation_runs (
    id UUID PRIMARY KEY,
    suite_id UUID NOT NULL REFERENCES evaluation_suites(id),
    case_id UUID NOT NULL REFERENCES evaluation_cases(id),
    organization_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    team_id TEXT NOT NULL,
    task_id UUID NOT NULL,
    execution_run_id UUID NOT NULL,
    evaluator_version TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'PASSED', 'FAILED', 'INCONCLUSIVE')),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (suite_id, case_id, execution_run_id, evaluator_version)
);

CREATE TABLE evaluation_results (
    id UUID PRIMARY KEY,
    evaluation_run_id UUID NOT NULL REFERENCES evaluation_runs(id) ON DELETE CASCADE,
    metric TEXT NOT NULL,
    score NUMERIC(8, 5),
    threshold NUMERIC(8, 5),
    status TEXT NOT NULL CHECK (status IN ('PASSED', 'FAILED', 'INCONCLUSIVE')),
    evidence_refs JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(evidence_refs) = 'array'),
    finding_summary TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (evaluation_run_id, metric)
);

CREATE TABLE evaluation_reports (
    id UUID PRIMARY KEY,
    evaluation_run_id UUID NOT NULL UNIQUE REFERENCES evaluation_runs(id) ON DELETE CASCADE,
    aggregate_score NUMERIC(8, 5),
    dimension_scores JSONB NOT NULL CHECK (jsonb_typeof(dimension_scores) = 'object'),
    evaluation_status TEXT NOT NULL CHECK (evaluation_status IN ('QUEUED', 'RUNNING', 'PASSED', 'FAILED', 'INCONCLUSIVE')),
    task_success BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX evaluation_runs_scope_idx ON evaluation_runs (organization_id, tenant_id, project_id, team_id);
CREATE INDEX evaluation_runs_task_idx ON evaluation_runs (tenant_id, task_id, execution_run_id);
CREATE INDEX evaluation_runs_status_idx ON evaluation_runs (tenant_id, status, created_at);
