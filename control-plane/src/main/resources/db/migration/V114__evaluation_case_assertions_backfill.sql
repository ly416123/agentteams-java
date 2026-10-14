-- V113 originally copied legacy input_json without extracting its nested
-- assertions. Repair only empty assertion arrays where the legacy source has
-- a valid array, preserving any assertions already edited or populated.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_name = 'evaluation_cases' AND column_name = 'input_json') THEN
        UPDATE evaluation_cases
           SET assertions = input_json -> 'assertions'
         WHERE assertions = '[]'::jsonb
           AND jsonb_typeof(input_json -> 'assertions') = 'array';
    END IF;
END $$;
