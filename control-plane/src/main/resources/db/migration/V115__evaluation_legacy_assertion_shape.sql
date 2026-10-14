-- Normalize the legacy evaluator shape {field, equals} to the current
-- deterministic assertion contract {type, expected}. Only the legacy keys
-- are rewritten; all other assertion fields and array order are preserved.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_name = 'evaluation_cases' AND column_name = 'assertions') THEN
        UPDATE evaluation_cases c
           SET assertions = (
               SELECT jsonb_agg(
                   CASE WHEN jsonb_typeof(item.assertion) = 'object'
                              AND item.assertion ? 'field'
                              AND item.assertion ? 'equals'
                              AND NOT (item.assertion ? 'type')
                        THEN (item.assertion - 'field' - 'equals')
                             || jsonb_build_object('type', item.assertion -> 'field',
                                                   'expected', item.assertion -> 'equals')
                        ELSE item.assertion END
                   ORDER BY item.ordinality)
                 FROM jsonb_array_elements(c.assertions)
                      WITH ORDINALITY AS item(assertion, ordinality)
           )
         WHERE EXISTS (
               SELECT 1
                 FROM jsonb_array_elements(c.assertions) AS item(assertion)
                WHERE jsonb_typeof(item.assertion) = 'object'
                  AND item.assertion ? 'field'
                  AND item.assertion ? 'equals'
                  AND NOT (item.assertion ? 'type')
         );
    END IF;
END $$;
