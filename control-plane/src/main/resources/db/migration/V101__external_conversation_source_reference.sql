-- A task may originate from the Manager-owned conversation store as well as
-- the Control Plane conversation store. The identifier is therefore a
-- cross-service reference; source-specific authorization is enforced by the
-- application boundary instead of a local-table foreign key.
ALTER TABLE tasks
    DROP CONSTRAINT IF EXISTS tasks_source_conversation_id_fkey;
