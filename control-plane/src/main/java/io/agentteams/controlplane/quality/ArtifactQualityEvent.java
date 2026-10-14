package io.agentteams.controlplane.quality;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable, bounded quality fact. Scope fields are resolved by trusted server-side context. */
public record ArtifactQualityEvent(UUID id, String organizationId, String tenantId, UUID projectId, UUID taskId,
        UUID runId, long sequence, String eventType, String artifactName, UUID fileId, String manifestRef,
        String sha256, String categoryCode, String checkItem, String actionSource, String status, Double score, Double scoreDelta,
        String summary, String sourceType, String sourceRef, String confidence, String idempotencyKey,
        Instant occurredAt) {
    private static final Pattern CODE = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,63}");
    private static final Pattern SHA256 = Pattern.compile("[a-fA-F0-9]{64}");
    private static final Pattern EVENT_TYPE = Pattern.compile(
            "GENERATE_STARTED|GENERATE_SUCCEEDED|GENERATE_FAILED|QUALITY_CHECK|OPTIMIZATION_ACTION|RECHECK|FINAL_STATUS");
    private static final Pattern SOURCE_REF = Pattern.compile(
            "(?:runtime:(?:task-process|task-result|evaluation):[A-Za-z0-9-]{1,64}|history:sha256:[a-f0-9]{64}#(?:case:[A-Za-z0-9_.:-]{1,100}(?::assertion:[0-9]{1,8}|:final-status)?|plan-line:[0-9]{1,8}))");
    private static final Pattern SAFE_ARTIFACT_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._ -]{0,127}");
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(bearer\\s+[a-z0-9._~+/=-]+|(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret)\\s*[:=]|https?://\\S+|-----BEGIN [A-Z ]+PRIVATE KEY-----)");

    public ArtifactQualityEvent {
        Objects.requireNonNull(id, "id");
        organizationId = bounded(organizationId, "organizationId", 128);
        tenantId = bounded(tenantId, "tenantId", 128);
        Objects.requireNonNull(projectId, "projectId");
        if ((taskId == null) != (runId == null)) throw new IllegalArgumentException("taskId and runId must be paired");
        if (taskId == null && !"HISTORICAL".equalsIgnoreCase(sourceType)) {
            throw new IllegalArgumentException("weak task/run associations are allowed only for historical records");
        }
        if (sequence < 0) throw new IllegalArgumentException("sequence must not be negative");
        eventType = code(eventType, "eventType").toUpperCase(Locale.ROOT);
        if (!EVENT_TYPE.matcher(eventType).matches()) throw new IllegalArgumentException("eventType is unsupported");
        if (artifactName != null) {
            artifactName = bounded(artifactName, "artifactName", 128);
            if (!SAFE_ARTIFACT_NAME.matcher(artifactName).matches()) throw new IllegalArgumentException("artifactName is unsafe");
        }
        if (manifestRef != null) {
            manifestRef = bounded(manifestRef, "manifestRef", 256);
            if (!manifestRef.matches("result-manifest:[A-Za-z0-9-]{1,64}")) throw new IllegalArgumentException("manifestRef is unsafe");
        }
        if (sha256 != null && !SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must be a hexadecimal SHA-256 digest");
        }
        if (categoryCode != null) categoryCode = code(categoryCode, "categoryCode");
        if (checkItem != null) checkItem = bounded(checkItem, "checkItem", 100);
        if (actionSource != null) actionSource = code(actionSource, "actionSource").toUpperCase(Locale.ROOT);
        status = code(status, "status").toUpperCase(Locale.ROOT);
        if (score != null && !Double.isFinite(score)) throw new IllegalArgumentException("score must be finite");
        if (scoreDelta != null && !Double.isFinite(scoreDelta)) throw new IllegalArgumentException("scoreDelta must be finite");
        summary = bounded(summary, "summary", 500);
        if (CREDENTIAL.matcher(summary).find()) throw new IllegalArgumentException("summary contains sensitive material");
        sourceType = code(sourceType, "sourceType").toUpperCase(Locale.ROOT);
        if (!sourceType.equals("LIVE") && !sourceType.equals("HISTORICAL")) throw new IllegalArgumentException("sourceType is invalid");
        sourceRef = bounded(sourceRef, "sourceRef", 500);
        if (!SOURCE_REF.matcher(sourceRef).matches()) throw new IllegalArgumentException("sourceRef is not a safe durable reference");
        confidence = code(confidence, "confidence").toUpperCase(Locale.ROOT);
        if (!confidence.matches("HIGH|MEDIUM|LOW|UNKNOWN")) throw new IllegalArgumentException("confidence is invalid");
        idempotencyKey = bounded(idempotencyKey, "idempotencyKey", 200);
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    private static String code(String value, String field) {
        if (value == null || !CODE.matcher(value).matches()) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }

    private static String bounded(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " must contain 1 to " + max + " characters");
        }
        return value.trim();
    }
}
