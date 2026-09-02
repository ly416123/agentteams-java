ALTER TABLE model_call_audits
    ADD COLUMN actor_subject TEXT;

UPDATE model_call_audits audits
   SET actor_subject = tasks.actor
  FROM tasks
 WHERE tasks.id::text = BTRIM(audits.task_id)
   AND NULLIF(BTRIM(audits.actor_subject), '') IS NULL;

CREATE INDEX model_call_audits_actor_subject_usage_idx
    ON model_call_audits (actor_subject, occurred_at DESC);
