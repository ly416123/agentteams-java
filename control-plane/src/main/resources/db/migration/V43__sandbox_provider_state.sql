ALTER TABLE task_sandboxes
    ADD COLUMN provider TEXT NOT NULL DEFAULT 'fake',
    ADD COLUMN provider_resource_id TEXT,
    ADD COLUMN provider_resource_uid TEXT,
    ADD COLUMN observed_generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN workload_uid TEXT,
    ADD COLUMN desired_state TEXT NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN operation_owner TEXT,
    ADD COLUMN operation_expires_at TIMESTAMPTZ,
    ADD COLUMN operation_kind TEXT,
    ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN last_dispatched_at TIMESTAMPTZ,
    ADD COLUMN dispatch_event_id UUID,
    ADD COLUMN details JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE task_sandboxes
    ADD CONSTRAINT task_sandboxes_observed_generation_non_negative
        CHECK (observed_generation >= 0),
    ADD CONSTRAINT task_sandboxes_desired_state_valid
        CHECK (desired_state IN ('ACTIVE', 'TERMINATED')),
    ADD CONSTRAINT task_sandboxes_retry_count_non_negative
        CHECK (retry_count >= 0);

CREATE UNIQUE INDEX task_sandboxes_provider_resource_idx
    ON task_sandboxes (provider, provider_resource_id)
    WHERE provider_resource_id IS NOT NULL;
CREATE UNIQUE INDEX task_sandboxes_dispatch_event_idx
    ON task_sandboxes (dispatch_event_id)
    WHERE dispatch_event_id IS NOT NULL;
CREATE INDEX task_sandboxes_next_attempt_idx
    ON task_sandboxes (status, next_attempt_at);
CREATE INDEX task_sandboxes_operation_expiry_idx
    ON task_sandboxes (operation_expires_at)
    WHERE operation_owner IS NOT NULL;
