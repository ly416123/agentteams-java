package io.agentteams.controlplane.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class DeterministicSkillSecurityScannerTest {

    private final DeterministicSkillSecurityScanner scanner = new DeterministicSkillSecurityScanner();
    private static final String MANIFEST =
            "{\"name\":\"review\",\"description\":\"Reviews code\","
                    + "\"entry\":\"SKILL.md\",\"sizeBytes\":128}";

    @Test
    void passesCleanManifest() {
        SkillSecurityScanner.ScanResult result = scanner.scan("""
                {"name":"review","description":"Reviews code","entry":"SKILL.md","sizeBytes":128}
                """);

        assertThat(result.status()).isEqualTo(SkillSecurityScanner.ScanResult.Status.PASS);
        assertThat(result.classification()).isEqualTo(DeterministicSkillSecurityScanner.CLEAN);
        assertThat(result.detail()).isNull();
    }

    @Test
    void failsPlaintextSecretWithoutReturningSecretMaterial() {
        String secret = "sk-live-1234567890abcdef";
        SkillSecurityScanner.ScanResult result = scanner.scan("{" +
                "\"name\":\"review\",\"description\":\"Reviews code\",\"entry\":\"SKILL.md\","
                + "\"sizeBytes\":128,\"api_key\":\"" + secret + "\"}");

        assertThat(result.status()).isEqualTo(SkillSecurityScanner.ScanResult.Status.FAIL);
        assertThat(result.classification()).isEqualTo(DeterministicSkillSecurityScanner.SECRET_EXPOSED);
        assertThat(result.detail()).doesNotContain(secret).doesNotContain("api_key");
    }

    @Test
    void failsPathTraversal() {
        SkillSecurityScanner.ScanResult result = scanner.scan("""
                {"name":"review","description":"Reviews code","entry":"scripts/../run.sh","sizeBytes":128}
                """);

        assertThat(result.status()).isEqualTo(SkillSecurityScanner.ScanResult.Status.FAIL);
        assertThat(result.classification()).isEqualTo(DeterministicSkillSecurityScanner.PATH_TRAVERSAL);
    }

    @Test
    void failsDangerousExecutionField() {
        SkillSecurityScanner.ScanResult result = scanner.scan("""
                {"name":"review","description":"Reviews code","entry":"SKILL.md","sizeBytes":128,
                 "command":"rm -rf /tmp/work"}
                """);

        assertThat(result.status()).isEqualTo(SkillSecurityScanner.ScanResult.Status.FAIL);
        assertThat(result.classification()).isEqualTo(DeterministicSkillSecurityScanner.DANGEROUS_EXECUTION);
    }

    @Test
    void requiresReviewForExternalScriptAndUrl() {
        SkillSecurityScanner.ScanResult script = scanner.scan("""
                {"name":"review","description":"Reviews code","entry":"SKILL.md","sizeBytes":128,
                 "hooks":{"post":"https://example.invalid/install.sh"}}
                """);
        SkillSecurityScanner.ScanResult url = scanner.scan("""
                {"name":"review","description":"Reviews code","entry":"SKILL.md","sizeBytes":128,
                 "homepage":"https://example.invalid/docs"}
                """);

        assertThat(script.status()).isEqualTo(SkillSecurityScanner.ScanResult.Status.REVIEW_REQUIRED);
        assertThat(script.classification()).isEqualTo(DeterministicSkillSecurityScanner.EXTERNAL_SCRIPT);
        assertThat(url.status()).isEqualTo(SkillSecurityScanner.ScanResult.Status.REVIEW_REQUIRED);
        assertThat(url.classification()).isEqualTo(DeterministicSkillSecurityScanner.UNTRUSTED_URL);
    }

    @Test
    void requiresReviewForMalformedInputWithoutParserDetails() {
        SkillSecurityScanner.ScanResult result = scanner.scan("{\"name\":");

        assertThat(result.status()).isEqualTo(SkillSecurityScanner.ScanResult.Status.REVIEW_REQUIRED);
        assertThat(result.classification()).isEqualTo(DeterministicSkillSecurityScanner.MALFORMED_MANIFEST);
        assertThat(result.detail()).doesNotContain("Json").doesNotContain(" at ");
    }

    @Test
    void validationOnlyScannerRemainsCompatible() {
        assertThat(new ValidationOnlySkillSecurityScanner().scan("not-json").status())
                .isEqualTo(SkillSecurityScanner.ScanResult.Status.PASSED);
    }

    @Test
    void rejectsZipPathTraversalWithStableClassification() throws Exception {
        SkillSecurityScanner.ScanResult result = scanner.scanArchive(
                new ByteArrayInputStream(zip(Map.of("../escape.sh", "echo safe"))), MANIFEST);

        assertThat(result.status()).isEqualTo(SkillSecurityScanner.ScanResult.Status.FAIL);
        assertThat(result.classification()).isEqualTo(DeterministicSkillSecurityScanner.PATH_TRAVERSAL);
        assertThat(result.detail()).doesNotContain("escape");
    }

    @Test
    void rejectsDangerousZipScriptWithoutPersistingScriptContent() throws Exception {
        String script = "#!/bin/sh\nrm -rf /tmp/work\n";
        SkillSecurityScanner.ScanResult result = scanner.scanArchive(
                new ByteArrayInputStream(zip(Map.of("scripts/run.sh", script))), MANIFEST);

        assertThat(result.status()).isEqualTo(SkillSecurityScanner.ScanResult.Status.FAIL);
        assertThat(result.classification()).isEqualTo(DeterministicSkillSecurityScanner.DANGEROUS_EXECUTION);
        assertThat(result.detail()).doesNotContain(script).doesNotContain("run.sh");
    }

    @Test
    void rejectsPlaintextCredentialInGzipTar() throws Exception {
        String secret = "API_KEY=sk-live-1234567890abcdef\n";
        SkillSecurityScanner.ScanResult result = scanner.scanArchive(
                new ByteArrayInputStream(gzip(tar(Map.of("config/.env", secret)))), MANIFEST);

        assertThat(result.status()).isEqualTo(SkillSecurityScanner.ScanResult.Status.FAIL);
        assertThat(result.classification()).isEqualTo(DeterministicSkillSecurityScanner.SECRET_EXPOSED);
        assertThat(result.detail()).doesNotContain(secret).doesNotContain("API_KEY");
    }

    @Test
    void requiresReviewForExternalUrlInArchive() throws Exception {
        SkillSecurityScanner.ScanResult result = scanner.scanArchive(
                new ByteArrayInputStream(zip(Map.of("README.md", "See https://example.invalid/docs"))), MANIFEST);

        assertThat(result.status()).isEqualTo(SkillSecurityScanner.ScanResult.Status.REVIEW_REQUIRED);
        assertThat(result.classification()).isEqualTo(DeterministicSkillSecurityScanner.UNTRUSTED_URL);
    }

    @Test
    void validationOnlyScannerDoesNotInspectArchive() {
        SkillSecurityScanner scanner = new ValidationOnlySkillSecurityScanner();

        assertThat(scanner.supportsArchiveScan()).isFalse();
        assertThat(scanner.scanArchive(new ByteArrayInputStream(new byte[] {1, 2, 3}), "not-json").status())
                .isEqualTo(SkillSecurityScanner.ScanResult.Status.PASSED);
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] gzip(byte[] tar) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(tar);
        }
        return output.toByteArray();
    }

    private static byte[] tar(Map<String, String> entries) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            byte[] content = entry.getValue().getBytes(StandardCharsets.UTF_8);
            byte[] header = new byte[512];
            writeField(header, 0, 100, entry.getKey());
            writeField(header, 100, 8, "0000777");
            writeField(header, 108, 8, "0000000");
            writeField(header, 116, 8, "0000000");
            writeField(header, 124, 12, String.format("%011o", content.length));
            writeField(header, 136, 12, "00000000000");
            for (int i = 148; i < 156; i++) {
                header[i] = ' ';
            }
            header[156] = '0';
            writeField(header, 257, 6, "ustar");
            writeField(header, 263, 2, "00");
            int checksum = 0;
            for (byte value : header) {
                checksum += value & 0xff;
            }
            writeField(header, 148, 8, String.format("%06o ", checksum));
            output.writeBytes(header);
            output.writeBytes(content);
            int padding = (512 - (content.length % 512)) % 512;
            output.writeBytes(new byte[padding]);
        }
        output.writeBytes(new byte[1024]);
        return output.toByteArray();
    }

    private static void writeField(byte[] target, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, Math.min(bytes.length, length));
    }
}
