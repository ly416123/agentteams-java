ALTER TABLE mcp_servers
    ADD COLUMN outbound_policy JSONB NOT NULL DEFAULT
        '{"allowedSchemes":["http","https"],"allowedDomains":["*"],"maxTimeout":30000,"allowedTools":["*"]}'::jsonb;

ALTER TABLE skill_versions
    ADD COLUMN security_scan_status TEXT NOT NULL DEFAULT 'NOT_SCANNED',
    ADD COLUMN review_status TEXT NOT NULL DEFAULT 'PENDING';

UPDATE skill_versions
   SET security_scan_status = 'PASSED', review_status = 'APPROVED'
 WHERE lifecycle = 'PUBLISHED';

ALTER TABLE skill_versions
    ADD CONSTRAINT skill_versions_security_scan_status_check
        CHECK (security_scan_status IN ('NOT_SCANNED', 'PASSED', 'FAILED')),
    ADD CONSTRAINT skill_versions_review_status_check
        CHECK (review_status IN ('PENDING', 'APPROVED', 'REJECTED'));
