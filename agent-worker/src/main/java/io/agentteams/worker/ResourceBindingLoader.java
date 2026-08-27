package io.agentteams.worker;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Loads the resource bindings embedded in a configuration manifest.
 *
 * <p>The control plane owns the resource registries. The worker therefore
 * treats a binding as the immutable, resolved reference it is given and
 * validates the identity tuple before the normal configuration snapshot is
 * staged. This keeps the existing ConfigChanged/ConfigApplied protocol intact
 * while making malformed bindings a deterministic configuration failure.</p>
 */
public final class ResourceBindingLoader {
    /** Stable error code carried by the existing ConfigApplied.error_message field. */
    public static final String INVALID_BINDING_ERROR_CODE = "RESOURCE_BINDING_INVALID";
    private static final List<String> SUPPORTED_TYPES = List.of("MCP", "MODEL", "SKILL");

    private ResourceBindingLoader() {
    }

    /**
     * Parses and validates all bindings in a manifest. An absent field is the
     * compatibility form used by older configuration manifests.
     */
    public static LoadResult load(JsonNode manifest) {
        Objects.requireNonNull(manifest, "manifest");
        if (!manifest.isObject()) {
            throw new IllegalArgumentException("configuration manifest must be a JSON object");
        }
        JsonNode bindingsNode = manifest.get("resourceBindings");
        if (bindingsNode == null || bindingsNode.isNull()) {
            return new LoadResult(List.of(), List.of());
        }
        if (!bindingsNode.isArray()) {
            return new LoadResult(List.of(), List.of(
                    BindingAck.failed("manifest", List.of("INVALID_COLLECTION"))));
        }

        List<ResourceBinding> bindings = new ArrayList<>();
        List<BindingAck> acknowledgements = new ArrayList<>();
        for (int index = 0; index < bindingsNode.size(); index++) {
            JsonNode node = bindingsNode.get(index);
            if (!node.isObject()) {
                acknowledgements.add(BindingAck.failed(index, List.of("INVALID_BINDING")));
                continue;
            }

            List<String> failures = new ArrayList<>();
            String type = text(node, "type", failures);
            String reference = text(node, "reference", failures);
            String revision = text(node, "revision", failures);
            String digest = text(node, "digest", failures);
            String artifactRef = optionalText(node, "artifactRef", failures);
            long sizeBytes = optionalSize(node, artifactRef, failures);
            if (type != null) {
                type = type.toUpperCase(Locale.ROOT);
                if (!SUPPORTED_TYPES.contains(type)) {
                    failures.add("INVALID_TYPE");
                }
            }
            if (failures.isEmpty()) {
                ResourceBinding binding = new ResourceBinding(type, reference, revision, digest, artifactRef, sizeBytes);
                bindings.add(binding);
                acknowledgements.add(BindingAck.success(binding));
            } else {
                acknowledgements.add(BindingAck.failed(index, type, reference, revision, digest, failures));
            }
        }

        // JSON arrays are ordered. Preserve that order so an operator can
        // correlate each ACK with the binding index in the manifest.
        return new LoadResult(List.copyOf(bindings), List.copyOf(acknowledgements));
    }

    private static String text(JsonNode node, String field, List<String> failures) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            failures.add("INVALID_" + field.toUpperCase(Locale.ROOT));
            return null;
        }
        return value.asText().trim();
    }

    private static String optionalText(JsonNode node, String field, List<String> failures) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.asText().isBlank()) {
            failures.add("INVALID_" + field.toUpperCase(Locale.ROOT));
            return null;
        }
        return value.asText().trim();
    }

    private static long optionalSize(JsonNode node, String artifactRef, List<String> failures) {
        JsonNode value = node.get("sizeBytes");
        if (artifactRef == null && (value == null || value.isNull())) return -1;
        if (value == null || !value.isIntegralNumber() || value.asLong() < 0) {
            failures.add("INVALID_SIZE_BYTES");
            return -1;
        }
        return value.asLong();
    }

    public record ResourceBinding(String type, String reference, String revision, String digest,
            String artifactRef, long sizeBytes) {
        public ResourceBinding(String type, String reference, String revision, String digest) {
            this(type, reference, revision, digest, null, -1);
        }

        public ResourceBinding {
            requireText(type, "type");
            requireText(reference, "reference");
            requireText(revision, "revision");
            requireText(digest, "digest");
            type = type.trim().toUpperCase(Locale.ROOT);
            reference = reference.trim();
            revision = revision.trim();
            digest = digest.trim();
            artifactRef = artifactRef == null || artifactRef.isBlank() ? null : artifactRef.trim();
            if (artifactRef != null && sizeBytes < 0) throw new IllegalArgumentException("sizeBytes must be non-negative");
            if (sizeBytes < -1) throw new IllegalArgumentException("sizeBytes must be -1 or non-negative");
        }

        public String key() {
            return type + "|" + reference + "|" + revision + "|" + digest;
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
        }
    }

    public record BindingAck(String key, String type, String resourceId, String revision, String digest,
            String artifactRef, long sizeBytes, AckStatus status, List<String> failureCodes) {
        public BindingAck {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("binding ACK key must not be blank");
            type = valueOrUnknown(type);
            resourceId = valueOrUnknown(resourceId);
            revision = valueOrUnknown(revision);
            digest = valueOrUnknown(digest);
            artifactRef = artifactRef == null || artifactRef.isBlank() ? null : artifactRef.trim();
            if (sizeBytes < -1) throw new IllegalArgumentException("sizeBytes must be -1 or non-negative");
            Objects.requireNonNull(status, "status");
            failureCodes = List.copyOf(Objects.requireNonNull(failureCodes, "failureCodes"));
            if (status == AckStatus.SUCCESS && !failureCodes.isEmpty()) {
                throw new IllegalArgumentException("successful binding ACK cannot contain failure codes");
            }
            if (status == AckStatus.FAILED && failureCodes.isEmpty()) {
                throw new IllegalArgumentException("failed binding ACK must contain failure codes");
            }
        }

        static BindingAck success(ResourceBinding binding) {
            return new BindingAck(binding.key(), binding.type(), binding.reference(), binding.revision(),
                    binding.digest(), binding.artifactRef(), binding.sizeBytes(), AckStatus.SUCCESS, List.of());
        }

        static BindingAck failed(int index, List<String> codes) {
            return failed("index:" + index, null, null, null, null, codes);
        }

        static BindingAck failed(int index, String type, String resourceId, String revision, String digest,
                List<String> codes) {
            return failed("index:" + index, type, resourceId, revision, digest, codes);
        }

        static BindingAck failed(String key, List<String> codes) {
            return failed(key, null, null, null, null, codes);
        }

        static BindingAck failed(String key, String type, String resourceId, String revision, String digest,
                List<String> codes) {
            return new BindingAck(key, type, resourceId, revision, digest, null, -1, AckStatus.FAILED, codes);
        }

        private static String valueOrUnknown(String value) {
            return value == null || value.isBlank() ? "unknown" : value.trim();
        }
    }

    public enum AckStatus {
        SUCCESS,
        FAILED
    }

    public record LoadResult(List<ResourceBinding> bindings, List<BindingAck> acknowledgements) {
        public LoadResult {
            bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
            acknowledgements = List.copyOf(Objects.requireNonNull(acknowledgements, "acknowledgements"));
        }

        public boolean successful() {
            return acknowledgements.stream().allMatch(ack -> ack.status() == AckStatus.SUCCESS);
        }

        /** Stable, bounded text for the existing ConfigApplied error_message field. */
        public String failureMessage() {
            return acknowledgements.stream()
                    .filter(ack -> ack.status() == AckStatus.FAILED)
                    .map(ack -> ack.key() + "=" + String.join(",", ack.failureCodes()))
                    .map(value -> INVALID_BINDING_ERROR_CODE + ": " + value)
                    .reduce((left, right) -> left + "; " + right)
                    .orElse("");
        }
    }
}
