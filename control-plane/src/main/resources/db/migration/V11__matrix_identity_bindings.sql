ALTER TABLE platform_identities
    ADD COLUMN project TEXT NOT NULL DEFAULT 'default',
    ADD COLUMN team TEXT NOT NULL DEFAULT 'default',
    ADD COLUMN matrix_user_id TEXT;

CREATE UNIQUE INDEX platform_identities_matrix_user_idx
    ON platform_identities(matrix_user_id)
    WHERE matrix_user_id IS NOT NULL;
