ALTER TABLE conversation_messages
    ADD COLUMN IF NOT EXISTS message_status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED';

ALTER TABLE conversation_messages
    DROP CONSTRAINT IF EXISTS conversation_messages_status_check;

ALTER TABLE conversation_messages
    ADD CONSTRAINT conversation_messages_status_check
        CHECK (message_status IN ('RESERVED', 'COMPLETED', 'FAILED', 'RECOVERY_REQUIRED'));

UPDATE conversation_messages
   SET message_status = 'RECOVERY_REQUIRED'
 WHERE end_cursor IS NULL;
