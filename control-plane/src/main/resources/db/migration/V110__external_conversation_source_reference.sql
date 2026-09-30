-- L5 environments may already be beyond the initial conversation migration;
-- repeat this idempotently so the cross-service reference contract is applied
-- to those databases too.
ALTER TABLE tasks
    DROP CONSTRAINT IF EXISTS tasks_source_conversation_id_fkey;
