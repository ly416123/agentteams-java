package io.agentteams.controlplane.skill;

/** Safe default until an external malware scanner is configured. */
public final class ValidationOnlySkillSecurityScanner implements SkillSecurityScanner {
    @Override
    public ScanResult scan(String manifestJson) {
        return new ScanResult(ScanResult.Status.PASSED, "VALIDATION_ONLY", null);
    }
}
