ALTER TABLE task_source_evidence_items
    ADD COLUMN snippet TEXT NOT NULL DEFAULT '';

ALTER TABLE task_source_evidence_items
    ADD CONSTRAINT task_source_evidence_items_snippet_length
        CHECK (length(snippet) <= 2000);
