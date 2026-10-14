ALTER TABLE evaluation_reports
    ADD COLUMN execution_gate_status VARCHAR(24) NOT NULL DEFAULT 'INCONCLUSIVE'
        CHECK (execution_gate_status IN ('PASSED', 'FAILED', 'INCONCLUSIVE')),
    ADD COLUMN result_gate_status VARCHAR(24) NOT NULL DEFAULT 'INCONCLUSIVE'
        CHECK (result_gate_status IN ('PASSED', 'FAILED', 'INCONCLUSIVE'));
