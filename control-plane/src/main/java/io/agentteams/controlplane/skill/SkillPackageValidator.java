package io.agentteams.controlplane.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Validates the metadata that makes a skill package safe to publish. */
@Component
public final class SkillPackageValidator {

    public static final long DEFAULT_MAX_PACKAGE_BYTES = 50L * 1024 * 1024;

    private static final Pattern SEMVER = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");
    private static final Pattern SHA256_DIGEST = Pattern.compile("^sha256:[0-9a-fA-F]{64}$");

    private final ObjectMapper objectMapper;
    private final long maxPackageBytes;

    public SkillPackageValidator() {
        this(new ObjectMapper(), DEFAULT_MAX_PACKAGE_BYTES);
    }

    public SkillPackageValidator(long maxPackageBytes) {
        this(new ObjectMapper(), maxPackageBytes);
    }

    @Autowired
    public SkillPackageValidator(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_MAX_PACKAGE_BYTES);
    }

    public SkillPackageValidator(ObjectMapper objectMapper, long maxPackageBytes) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        if (maxPackageBytes <= 0) {
            throw new IllegalArgumentException("maxPackageBytes must be greater than zero");
        }
        this.maxPackageBytes = maxPackageBytes;
    }

    public void validate(String version, String digest, String manifestJson) {
        validateVersion(version);
        validateDigest(digest);
        JsonNode manifest = parseManifest(manifestJson);
        requireText(manifest, "name");
        requireText(manifest, "description");
        validateEntry(manifest);
        validateSize(manifest);
    }

    public void validateVersion(String version) {
        if (version == null || version.isBlank()) {
            throw invalid("version is required");
        }
        if (!SEMVER.matcher(version.trim()).matches()) {
            throw invalid("version must use semantic version format, for example 1.2.3");
        }
    }

    public void validateDigest(String digest) {
        if (digest == null || digest.isBlank()) {
            throw invalid("digest is required");
        }
        if (!SHA256_DIGEST.matcher(digest.trim()).matches()) {
            throw invalid("digest must use sha256:<64 hexadecimal characters> format");
        }
    }

    private JsonNode parseManifest(String manifestJson) {
        if (manifestJson == null || manifestJson.isBlank()) {
            throw invalid("manifest is required");
        }
        final JsonNode manifest;
        try {
            manifest = objectMapper.readTree(manifestJson);
        } catch (JsonProcessingException error) {
            throw new SkillPackageValidationException("manifest must be valid JSON", error);
        }
        if (manifest == null || !manifest.isObject()) {
            throw invalid("manifest must be a JSON object");
        }
        return manifest;
    }

    private static void requireText(JsonNode manifest, String field) {
        JsonNode value = manifest.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid("manifest." + field + " is required and must be a non-blank string");
        }
    }

    private static void validateEntry(JsonNode manifest) {
        JsonNode value = manifest.get("entry");
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid("manifest.entry is required and must be a safe relative path");
        }
        String entry = value.asText().trim();
        if (entry.length() > 255 || entry.startsWith("/") || entry.startsWith("\\")
                || entry.contains("\\") || entry.contains(":")
                || entry.contains("//")) {
            throw invalid("manifest.entry must be a safe relative path");
        }
        for (String segment : entry.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw invalid("manifest.entry must be a safe relative path");
            }
        }
    }

    private void validateSize(JsonNode manifest) {
        JsonNode value = manifest.get("sizeBytes");
        if (value == null || !value.isIntegralNumber()) {
            throw invalid("manifest.sizeBytes is required and must be a non-negative integer");
        }
        if (!value.canConvertToLong()) {
            throw invalid("manifest.sizeBytes must be a non-negative integer");
        }
        long sizeBytes = value.longValue();
        if (sizeBytes < 0) {
            throw invalid("manifest.sizeBytes must be a non-negative integer");
        }
        if (sizeBytes > maxPackageBytes) {
            throw invalid("manifest.sizeBytes exceeds the maximum package size of " + maxPackageBytes + " bytes");
        }
    }

    private static SkillPackageValidationException invalid(String message) {
        return new SkillPackageValidationException(message);
    }
}
