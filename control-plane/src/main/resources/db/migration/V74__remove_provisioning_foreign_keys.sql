-- The application validates cross-table references and coordinates them in one
-- transaction. Keep primary, unique, check and index constraints.
DO $$
DECLARE
    foreign_key RECORD;
BEGIN
    FOR foreign_key IN
        SELECT namespace.nspname AS schema_name,
               table_ref.relname AS table_name,
               constraint_ref.conname AS constraint_name
          FROM pg_constraint constraint_ref
          JOIN pg_class table_ref ON table_ref.oid = constraint_ref.conrelid
          JOIN pg_namespace namespace ON namespace.oid = table_ref.relnamespace
         WHERE constraint_ref.contype = 'f'
           AND namespace.nspname = 'public'
    LOOP
        EXECUTE format(
            'ALTER TABLE %I.%I DROP CONSTRAINT %I',
            foreign_key.schema_name,
            foreign_key.table_name,
            foreign_key.constraint_name
        );
    END LOOP;
END
$$;
