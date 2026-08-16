ALTER TABLE artifacts
    ADD CONSTRAINT artifacts_attempt_name_sha256_key UNIQUE (attempt_id, name, sha256);
