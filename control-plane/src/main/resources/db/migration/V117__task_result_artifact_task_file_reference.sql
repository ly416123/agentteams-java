ALTER TABLE task_result_artifacts
    ADD COLUMN source_task_id UUID,
    ADD COLUMN source_file_id UUID,
    ADD CONSTRAINT task_result_artifacts_source_pair_check
        CHECK ((source_task_id IS NULL) = (source_file_id IS NULL));

CREATE INDEX task_result_artifacts_source_file_idx
    ON task_result_artifacts (source_task_id, source_file_id)
    WHERE source_task_id IS NOT NULL;
