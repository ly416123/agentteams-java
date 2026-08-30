ALTER TABLE project_create_idempotency
    DROP CONSTRAINT project_create_idempotency_project_fk;

ALTER TABLE project_create_idempotency
    ADD CONSTRAINT project_create_idempotency_project_fk
    FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id)
    DEFERRABLE INITIALLY DEFERRED;
