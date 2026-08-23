package io.agentteams.controlplane.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic, local-only manifest scanner. It deliberately reports categories rather than
 * paths or values so scan results can safely be persisted and returned to callers.
 */
@Component
@ConditionalOnProperty(name = "agentteams.skill.security-scanner.deterministic.enabled", havingValue = "true")
public final class DeterministicSkillSecurityScanner implements SkillSecurityScanner {

    public static final String CLEAN = "CLEAN";
    public static final String MALFORMED_MANIFEST = "MALFORMED_MANIFEST";
    public static final String DANGEROUS_EXECUTION = "DANGEROUS_EXECUTION";
    public static final String SECRET_EXPOSED = "SECRET_EXPOSED";
    public static final String PATH_TRAVERSAL = "PATH_TRAVERSAL";
    public static final String EXTERNAL_SCRIPT = "EXTERNAL_SCRIPT";
    public static final String UNTRUSTED_URL = "UNTRUSTED_URL";
    public static final String MALFORMED_ARCHIVE = "MALFORMED_ARCHIVE";
    public static final String ARCHIVE_LIMIT_EXCEEDED = "ARCHIVE_LIMIT_EXCEEDED";

    private static final Set<String> EXECUTION_FIELDS = Set.of(
            "command", "commands", "exec", "execute", "executable", "shell", "run", "runs",
            "runtime", "entrypoint", "preinstall", "postinstall", "installcommand", "uninstall",
            "hook", "hooks");
    private static final Set<String> SECRET_FIELDS = Set.of(
            "secret", "secretkey", "apikey", "accesstoken", "authtoken", "password", "privatekey",
            "clientsecret", "bearertoken", "credential", "credentials", "token");
    private static final Pattern TRAVERSAL = Pattern.compile("(^|[/\\\\])\\.\\.([/\\\\]|$)");
    private static final Pattern URL = Pattern.compile("(?i)\\bhttps?://[^\\s<>\\\"']+");
    private static final Pattern TOKEN = Pattern.compile(
            "(?i)(?:\\b(?:api[_-]?key|secret|password|token)\\s*[:=]\\s*[^\\s,}\\\"']{8,}"
                    + "|\\bsk-[A-Za-z0-9]{10,}|\\bghp_[A-Za-z0-9]{20,}|\\bxox[bap]-[A-Za-z0-9-]{10,}"
                    + "|\\bAKIA[0-9A-Z]{16}\\b|\\bAIza[0-9A-Za-z_-]{20,})");
    private static final Pattern SCRIPT_URL = Pattern.compile(
            "(?i)(?:\\.(?:sh|bash|py|js|mjs|ts|ps1|bat|cmd)(?:[?#].*)?|/install(?:[?#].*)?)$");
    private static final Pattern SCRIPT_FILE = Pattern.compile(
            "(?i).*\\.(?:sh|bash|py|js|mjs|ts|ps1|bat|cmd)$");
    private static final Pattern DANGEROUS_SCRIPT = Pattern.compile(
            "(?is)(?:\\brm\\s+-rf\\b|\\bcurl\\b|\\bwget\\b|invoke-webrequest|\\bpowershell(?:\\.exe)?\\b"
                    + "|\\bchmod\\s+[0-7]*7[0-7]*\\b|\\beval\\s*\\(|\\b(?:nc|netcat)\\b"
                    + "|base64\\s+(-d|--decode))");
    private static final int TAR_BLOCK_BYTES = 512;
    private static final int MAX_ENTRY_BYTES = 4 * 1024 * 1024;
    private static final long MAX_ARCHIVE_BYTES = 50L * 1024 * 1024;

    private final ObjectMapper objectMapper;

    public DeterministicSkillSecurityScanner() {
        this(new ObjectMapper());
    }

    @Autowired
    public DeterministicSkillSecurityScanner(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public ScanResult scan(String manifestJson) {
        if (manifestJson == null || manifestJson.isBlank()) {
            return review(MALFORMED_MANIFEST, "manifest could not be parsed");
        }

        final JsonNode manifest;
        try {
            manifest = objectMapper.readTree(manifestJson);
        } catch (JsonProcessingException error) {
            return review(MALFORMED_MANIFEST, "manifest could not be parsed");
        }
        if (manifest == null || !manifest.isObject()) {
            return review(MALFORMED_MANIFEST, "manifest could not be parsed");
        }

        Findings findings = new Findings();
        inspect(manifest, "", findings);
        if (findings.secret) {
            return fail(SECRET_EXPOSED, "manifest contains secret-like material");
        }
        if (findings.pathTraversal) {
            return fail(PATH_TRAVERSAL, "manifest contains an unsafe path");
        }
        if (findings.dangerousExecution) {
            return fail(DANGEROUS_EXECUTION, "manifest contains an execution directive");
        }
        if (findings.externalScript) {
            return review(EXTERNAL_SCRIPT, "manifest references an external script");
        }
        if (findings.untrustedUrl) {
            return review(UNTRUSTED_URL, "manifest references an external URL");
        }
        return new ScanResult(ScanResult.Status.PASS, CLEAN, null);
    }

    @Override
    public boolean supportsArchiveScan() {
        return true;
    }

    @Override
    public ScanResult scanArchive(InputStream archive, String manifestJson) {
        ScanResult manifestResult = scan(manifestJson);
        if (!isPassed(manifestResult.status())) {
            return manifestResult;
        }
        Objects.requireNonNull(archive, "archive");
        try {
            java.io.BufferedInputStream buffered = archive instanceof java.io.BufferedInputStream
                    ? (java.io.BufferedInputStream) archive : new java.io.BufferedInputStream(archive);
            buffered.mark(4);
            byte[] signature = buffered.readNBytes(4);
            buffered.reset();
            Findings findings;
            if (isZip(signature)) {
                findings = inspectZip(buffered);
            } else if (isGzip(signature)) {
                findings = inspectTar(new GZIPInputStream(buffered));
            } else {
                findings = inspectTar(buffered);
            }
            return result(findings);
        } catch (ArchiveLimitException error) {
            return fail(ARCHIVE_LIMIT_EXCEEDED, "skill package exceeds the scan limit");
        } catch (IOException | RuntimeException error) {
            return review(MALFORMED_ARCHIVE, "skill package archive could not be inspected");
        }
    }

    private Findings inspectZip(InputStream input) throws IOException {
        Findings findings = new Findings();
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            long total = 0;
            while ((entry = zip.getNextEntry()) != null) {
                inspectPath(entry.getName(), entry.isDirectory(), findings);
                if (!entry.isDirectory()) {
                    byte[] content = readEntry(zip, MAX_ENTRY_BYTES);
                    total += content.length;
                    if (total > MAX_ARCHIVE_BYTES) {
                        throw new ArchiveLimitException();
                    }
                    inspectContent(entry.getName(), content, findings);
                }
                zip.closeEntry();
            }
        }
        return findings;
    }

    private Findings inspectTar(InputStream input) throws IOException {
        Findings findings = new Findings();
        byte[] header = new byte[TAR_BLOCK_BYTES];
        long total = 0;
        while (true) {
            java.util.Arrays.fill(header, (byte) 0);
            int read = readAtMost(input, header);
            if (read == 0 || isZeroBlock(header)) {
                return findings;
            }
            if (read != TAR_BLOCK_BYTES) {
                throw new EOFException("incomplete tar header");
            }
            String name = tarName(header);
            boolean directory = isDirectory(header);
            inspectPath(name, directory, findings);
            long size = tarSize(header);
            if (size < 0 || size > MAX_ENTRY_BYTES) {
                throw new ArchiveLimitException();
            }
            byte[] content = readExact(input, size);
            total += size;
            if (total > MAX_ARCHIVE_BYTES) {
                throw new ArchiveLimitException();
            }
            if (!directory) {
                inspectContent(name, content, findings);
            }
            long padding = (TAR_BLOCK_BYTES - (size % TAR_BLOCK_BYTES)) % TAR_BLOCK_BYTES;
            skipExact(input, padding);
        }
    }

    private void inspectContent(String name, byte[] content, Findings findings) {
        String text = new String(content, java.nio.charset.StandardCharsets.UTF_8);
        if (TOKEN.matcher(text).find()) {
            findings.secret = true;
        }
        if (URL.matcher(text).find()) {
            findings.untrustedUrl = true;
            if (SCRIPT_FILE.matcher(name).matches() || SCRIPT_URL.matcher(text).find()) {
                findings.externalScript = true;
            }
        }
        if (SCRIPT_FILE.matcher(name).matches() && DANGEROUS_SCRIPT.matcher(text).find()) {
            findings.dangerousExecution = true;
        }
    }

    private static void inspectPath(String name, boolean directory, Findings findings) {
        if (name == null || name.isBlank()) {
            findings.pathTraversal = true;
            return;
        }
        String normalized = name.replace('\\', '/');
        if (directory && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.startsWith("/") || normalized.startsWith("~/")
                || normalized.matches("^[A-Za-z]:/.*") || normalized.contains("//")) {
            findings.pathTraversal = true;
        }
        for (String segment : normalized.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                findings.pathTraversal = true;
            }
        }
    }

    private static byte[] readEntry(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > limit) {
                throw new ArchiveLimitException();
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static byte[] readExact(InputStream input, long size) throws IOException {
        if (size > Integer.MAX_VALUE) {
            throw new ArchiveLimitException();
        }
        byte[] content = new byte[(int) size];
        int offset = 0;
        while (offset < content.length) {
            int read = input.read(content, offset, content.length - offset);
            if (read < 0) {
                throw new EOFException("incomplete tar entry");
            }
            offset += read;
        }
        return content;
    }

    private static void skipExact(InputStream input, long size) throws IOException {
        long remaining = size;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) {
                if (input.read() < 0) {
                    throw new EOFException("incomplete tar padding");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static int readAtMost(InputStream input, byte[] target) throws IOException {
        int total = 0;
        while (total < target.length) {
            int read = input.read(target, total, target.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    private static String tarName(byte[] header) {
        String name = field(header, 0, 100);
        String prefix = field(header, 345, 155);
        return prefix.isBlank() ? name : prefix + "/" + name;
    }

    private static long tarSize(byte[] header) {
        String value = field(header, 124, 12).trim();
        if (value.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(value.replace("\u0000", "").trim(), 8);
        } catch (NumberFormatException error) {
            throw new ArchiveLimitException();
        }
    }

    private static String field(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, java.nio.charset.StandardCharsets.UTF_8).trim();
    }

    private static boolean isDirectory(byte[] header) {
        return header[156] == '5';
    }

    private static boolean isZeroBlock(byte[] value) {
        for (byte current : value) {
            if (current != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isZip(byte[] signature) {
        return signature.length >= 4 && signature[0] == 'P' && signature[1] == 'K'
                && (signature[2] == 3 || signature[2] == 5 || signature[2] == 7)
                && (signature[3] == 4 || signature[3] == 6 || signature[3] == 8);
    }

    private static boolean isGzip(byte[] signature) {
        return signature.length >= 2 && (signature[0] & 0xff) == 0x1f && (signature[1] & 0xff) == 0x8b;
    }

    private static ScanResult result(Findings findings) {
        if (findings.secret) {
            return fail(SECRET_EXPOSED, "skill package contains secret-like material");
        }
        if (findings.pathTraversal) {
            return fail(PATH_TRAVERSAL, "skill package contains an unsafe path");
        }
        if (findings.dangerousExecution) {
            return fail(DANGEROUS_EXECUTION, "skill package contains a dangerous script");
        }
        if (findings.externalScript) {
            return review(EXTERNAL_SCRIPT, "skill package references an external script");
        }
        if (findings.untrustedUrl) {
            return review(UNTRUSTED_URL, "skill package references an external URL");
        }
        return new ScanResult(ScanResult.Status.PASS, CLEAN, null);
    }

    private static boolean isPassed(ScanResult.Status status) {
        return status == ScanResult.Status.PASS || status == ScanResult.Status.PASSED;
    }

    private static void inspect(JsonNode node, String field, Findings findings) {
        if (node.isTextual()) {
            String value = node.asText();
            if (TRAVERSAL.matcher(value).find()) {
                findings.pathTraversal = true;
            }
            if (TOKEN.matcher(value).find()) {
                findings.secret = true;
            }
            if (URL.matcher(value).find()) {
                findings.untrustedUrl = true;
                if (field.contains("script") || field.contains("hook") || SCRIPT_URL.matcher(value).find()) {
                    findings.externalScript = true;
                }
            }
            return;
        }
        if (!node.isContainerNode()) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String normalizedField = normalize(entry.getKey());
                JsonNode value = entry.getValue();
                if (EXECUTION_FIELDS.contains(normalizedField) && isMeaningful(value)) {
                    if (containsUrl(value)) {
                        findings.untrustedUrl = true;
                        findings.externalScript = true;
                    } else {
                        findings.dangerousExecution = true;
                    }
                }
                if (SECRET_FIELDS.contains(normalizedField) && !isSecretReference(value)) {
                    findings.secret = true;
                }
                inspect(value, normalizedField, findings);
            }
        } else {
            for (JsonNode child : node) {
                inspect(child, field, findings);
            }
        }
    }

    private static boolean isMeaningful(JsonNode value) {
        return value != null && !value.isNull() && !(value.isBoolean() && !value.booleanValue())
                && !(value.isTextual() && value.asText().isBlank());
    }

    private static boolean isSecretReference(JsonNode value) {
        if (value.isObject()) {
            return value.has("ref") || value.has("secretRef") || value.has("name") || value.has("env");
        }
        if (!value.isTextual()) {
            return false;
        }
        String text = value.asText().trim().toLowerCase(Locale.ROOT);
        return text.isEmpty() || text.equals("***") || text.equals("[redacted]")
                || text.startsWith("${") || text.startsWith("$env:") || text.startsWith("env:")
                || text.startsWith("secret://") || text.startsWith("vault://");
    }

    private static boolean containsUrl(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isTextual()) {
            return URL.matcher(node.asText()).find();
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                if (containsUrl(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalize(String field) {
        return field == null ? "" : field.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static ScanResult fail(String classification, String detail) {
        return new ScanResult(ScanResult.Status.FAIL, classification, detail);
    }

    private static ScanResult review(String classification, String detail) {
        return new ScanResult(ScanResult.Status.REVIEW_REQUIRED, classification, detail);
    }

    private static final class Findings {
        private boolean dangerousExecution;
        private boolean secret;
        private boolean pathTraversal;
        private boolean externalScript;
        private boolean untrustedUrl;
    }

    private static final class ArchiveLimitException extends RuntimeException {
    }
}
