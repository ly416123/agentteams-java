ALTER TABLE agents
    ADD COLUMN worker_type TEXT NOT NULL DEFAULT 'EXECUTOR';

ALTER TABLE agents
    ADD CONSTRAINT agents_worker_type_valid
    CHECK (worker_type IN ('LEADER', 'EXECUTOR'));

CREATE INDEX agents_worker_type_phase_idx ON agents (worker_type, phase, updated_at);

ALTER TABLE worker_templates
    ADD COLUMN worker_type TEXT NOT NULL DEFAULT 'EXECUTOR';

ALTER TABLE worker_templates
    ADD CONSTRAINT worker_templates_worker_type_valid
    CHECK (worker_type IN ('LEADER', 'EXECUTOR'));

ALTER TABLE worker_template_revisions
    ADD COLUMN worker_type TEXT NOT NULL DEFAULT 'EXECUTOR';

ALTER TABLE worker_template_revisions
    ADD CONSTRAINT worker_template_revisions_worker_type_valid
    CHECK (worker_type IN ('LEADER', 'EXECUTOR'));
