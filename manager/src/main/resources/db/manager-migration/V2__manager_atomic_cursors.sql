ALTER TABLE manager_messages
    ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED';

ALTER TABLE manager_messages
    DROP CONSTRAINT IF EXISTS manager_messages_status_check;

ALTER TABLE manager_messages
    ADD CONSTRAINT manager_messages_status_check
        CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED'));

CREATE SEQUENCE manager_event_cursor_seq AS BIGINT START WITH 1;

SELECT setval('manager_event_cursor_seq',
              GREATEST(COALESCE((SELECT MAX(cursor) FROM manager_events), 0), 1),
              COALESCE((SELECT MAX(cursor) FROM manager_events), 0) > 0);

ALTER TABLE manager_events
    ALTER COLUMN cursor SET DEFAULT nextval('manager_event_cursor_seq');
