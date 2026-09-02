ALTER TABLE integration_credentials
    ADD COLUMN label TEXT NOT NULL DEFAULT 'default';

ALTER TABLE integration_credentials
    ADD CONSTRAINT integration_credentials_label_not_blank CHECK (length(btrim(label)) > 0);

CREATE INDEX integration_credentials_label_idx
    ON integration_credentials (integration_id, label);
