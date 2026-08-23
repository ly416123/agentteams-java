package io.agentteams.controlplane.skill;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * Explicit opt-in bridge from the local Skill scanner to the external sandbox SPI.
 *
 * <p>The local scanner is always the first gate. This preserves the existing deterministic
 * behavior and avoids an external request when the local scanner already rejects or requires
 * review. This bridge is not a Spring component; callers must deliberately construct it.</p>
 */
public final class ExternalSkillSandboxSecurityScanner implements SkillSecurityScanner {

    public static final int MAX_ARCHIVE_BYTES = 50 * 1024 * 1024;
    public static final String SANDBOX_REJECTED = "SANDBOX_REJECTED";
    public static final String SANDBOX_REVIEW_REQUIRED = "SANDBOX_REVIEW_REQUIRED";
    public static final String SANDBOX_UNAVAILABLE = "SANDBOX_UNAVAILABLE";
    public static final String SANDBOX_FAILED = ExternalSkillSandboxScanner.SANDBOX_FAILED;
    public static final String SANDBOX_INVALID_RESPONSE = "SANDBOX_INVALID_RESPONSE";
    public static final String EXTERNAL_SCAN_INVALID_RESULT = SANDBOX_INVALID_RESPONSE;
    public static final String SANDBOX_TIMEOUT = "SANDBOX_TIMEOUT";
    public static final String SANDBOX_INPUT_TOO_LARGE = ExternalSkillSandboxScanner.SANDBOX_INPUT_TOO_LARGE;
    public static final String SANDBOX_ARCHIVE_READ_FAILED = ExternalSkillSandboxScanner.SANDBOX_ARCHIVE_READ_FAILED;

    private final SkillSecurityScanner localScanner;
    private final ExternalSkillSandboxScanner externalScanner;

    public ExternalSkillSandboxSecurityScanner(SkillSecurityScanner localScanner,
            ExternalSkillSandboxScanner externalScanner) {
        this.localScanner = Objects.requireNonNull(localScanner, "localScanner");
        this.externalScanner = Objects.requireNonNull(externalScanner, "externalScanner");
    }

    public ExternalSkillSandboxSecurityScanner(SkillSecurityScanner localScanner,
            SkillSandboxScannerClient client, Duration timeout) {
        this(localScanner, new ConfiguredExternalSkillSandboxScanner(client, timeout));
    }

    ExternalSkillSandboxSecurityScanner(SkillSecurityScanner localScanner,
            SkillSandboxScannerClient client, Duration timeout, ExecutorService executor) {
        this(localScanner, new ConfiguredExternalSkillSandboxScanner(client, timeout, executor));
    }

    /** Selects the local deterministic scanner unless an external implementation is explicit. */
    public static SkillSecurityScanner defaultScanner(Optional<ExternalSkillSandboxScanner> external) {
        Objects.requireNonNull(external, "external");
        return external.map(scanner -> (SkillSecurityScanner) new ExternalSkillSandboxSecurityScanner(
                new DeterministicSkillSecurityScanner(), scanner))
                .orElseGet(() -> new DeterministicSkillSecurityScanner());
    }

    public static SkillSecurityScanner defaultScanner(Optional<SkillSandboxScannerClient> client,
            Duration timeout) {
        Objects.requireNonNull(client, "client");
        return client.map(value -> (SkillSecurityScanner) new ExternalSkillSandboxSecurityScanner(
                new DeterministicSkillSecurityScanner(), value, timeout))
                .orElseGet(() -> new DeterministicSkillSecurityScanner());
    }

    @Override
    public ScanResult scan(String manifestJson) {
        ScanResult local = localScanner.scan(manifestJson);
        if (!isPassed(local.status())) {
            return local;
        }
        return invokeExternal(new ExternalSkillSandboxScanner.SandboxScanRequest(manifestJson, null));
    }

    @Override
    public boolean supportsArchiveScan() {
        return externalScanner.supportsArchiveScan();
    }

    @Override
    public ScanResult scanArchive(InputStream archive, String manifestJson) {
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(manifestJson, "manifestJson");
        if (!supportsArchiveScan()) {
            return localScanner.scan(manifestJson);
        }

        byte[] bytes;
        try {
            bytes = readBounded(archive);
        } catch (ArchiveTooLargeException error) {
            return review(ExternalSkillSandboxScanner.SANDBOX_INPUT_TOO_LARGE,
                    "skill package exceeds the external scan limit");
        } catch (IOException error) {
            return review(ExternalSkillSandboxScanner.SANDBOX_ARCHIVE_READ_FAILED,
                    "skill package could not be read for external scanning");
        }

        ScanResult local = localScanner.supportsArchiveScan()
                ? localScanner.scanArchive(new ByteArrayInputStream(bytes), manifestJson)
                : localScanner.scan(manifestJson);
        if (!isPassed(local.status())) {
            return local;
        }
        return invokeExternal(new ExternalSkillSandboxScanner.SandboxScanRequest(manifestJson, bytes));
    }

    private ScanResult invokeExternal(ExternalSkillSandboxScanner.SandboxScanRequest request) {
        try {
            return map(externalScanner.scan(request));
        } catch (RuntimeException error) {
            return review(ExternalSkillSandboxScanner.SANDBOX_UNAVAILABLE,
                    "external sandbox is unavailable");
        }
    }

    private static ScanResult map(ExternalSkillSandboxScanner.SandboxScanResult result) {
        if (result == null || result.decision() == null || result.classification() == null
                || result.classification().isBlank()) {
            return review(SANDBOX_INVALID_RESPONSE, "external sandbox returned an invalid result");
        }
        return switch (result.decision()) {
            case CLEAN -> new ScanResult(ScanResult.Status.PASS, "CLEAN", null);
            case REJECTED -> new ScanResult(ScanResult.Status.FAIL, SANDBOX_REJECTED,
                    "external sandbox rejected the Skill");
            case REVIEW_REQUIRED -> review(reviewClassification(result.classification()),
                    "external sandbox requires manual review");
        };
    }

    private static String reviewClassification(String value) {
        if (ExternalSkillSandboxScanner.SANDBOX_TIMEOUT.equals(value)) return SANDBOX_TIMEOUT;
        if (ExternalSkillSandboxScanner.SANDBOX_UNAVAILABLE.equals(value)) return SANDBOX_UNAVAILABLE;
        if (ExternalSkillSandboxScanner.SANDBOX_INVALID_RESULT.equals(value)) return SANDBOX_INVALID_RESPONSE;
        return SANDBOX_REVIEW_REQUIRED;
    }

    private static ScanResult review(String classification, String detail) {
        return new ScanResult(ScanResult.Status.REVIEW_REQUIRED, safeClassification(classification), detail);
    }

    private static String safeClassification(String value) {
        String normalized = value.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    private static boolean isPassed(ScanResult.Status status) {
        return status == ScanResult.Status.PASS || status == ScanResult.Status.PASSED;
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() > MAX_ARCHIVE_BYTES - read) {
                throw new ArchiveTooLargeException();
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static final class ArchiveTooLargeException extends IOException {
    }
}
