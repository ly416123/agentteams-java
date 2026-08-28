ALTER TABLE model_call_audits
    ADD COLUMN cost_status TEXT;

UPDATE model_call_audits
   SET cost_status = CASE
       WHEN cost_usd <> 0 THEN 'ESTIMATED'
       WHEN outcome = 'FAILURE' THEN 'NOT_APPLICABLE'
       ELSE 'UNPRICED'
   END
 WHERE cost_status IS NULL;

ALTER TABLE model_call_audits
    ALTER COLUMN cost_status SET NOT NULL,
    ADD CONSTRAINT model_call_audits_cost_status_check
        CHECK (cost_status IN ('ESTIMATED', 'UNPRICED', 'NOT_APPLICABLE')),
    ADD CONSTRAINT model_call_audits_cost_status_amount_check
        CHECK ((cost_status = 'ESTIMATED') OR cost_usd = 0);

CREATE INDEX model_call_audits_cost_status_idx
    ON model_call_audits (tenant_id, project_id, occurred_at DESC, cost_status);
