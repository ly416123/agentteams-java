package io.agentteams.controlplane.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.application.api.SandboxPolicy;
import io.agentteams.application.api.SandboxProfile;
import io.agentteams.application.api.SkillCapabilityPolicy;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;

/** Parses the non-secret capability declaration embedded in an immutable Skill manifest. */
public final class SkillCapabilityPolicyParser {
    private static final SetOfFields CAPABILITY_FIELDS = new SetOfFields(
            "profile", "cpuMillicores", "memoryMiB", "ephemeralStorageMiB", "ttlSeconds",
            "networkPolicy", "allowedMcp", "allowedDomains", "allowSecretReferences");

    private final ObjectMapper objectMapper;

    public SkillCapabilityPolicyParser() {
        this(new ObjectMapper());
    }

    public SkillCapabilityPolicyParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Returns a deny-by-default policy when the manifest has no capabilities object.
     * Unknown fields are rejected so a misspelled limit cannot silently widen execution.
     */
    public SkillCapabilityPolicy parse(String manifestJson) {
        if (manifestJson == null || manifestJson.isBlank()) {
            throw invalid("skill manifest must be a JSON object");
        }
        final JsonNode root;
        try {
            root = objectMapper.readTree(manifestJson);
        } catch (Exception error) {
            throw new IllegalArgumentException("skill manifest must be valid JSON", error);
        }
        if (root == null || !root.isObject()) {
            throw invalid("skill manifest must be a JSON object");
        }
        JsonNode capabilities = root.get("capabilities");
        if (capabilities == null || capabilities.isNull()) {
            return defaults();
        }
        if (!capabilities.isObject()) {
            throw invalid("manifest.capabilities must be an object");
        }
        rejectUnknownFields(capabilities);
        SandboxPolicy defaults = SandboxPolicy.defaults();
        return new SkillCapabilityPolicy(
                enumValue(capabilities, "profile", SandboxProfile.class, defaults.profile()),
                positiveInt(capabilities, "cpuMillicores", defaults.cpuMillicores()),
                positiveInt(capabilities, "memoryMiB", defaults.memoryMiB()),
                positiveInt(capabilities, "ephemeralStorageMiB", defaults.ephemeralStorageMiB()),
                Duration.ofSeconds(positiveInt(capabilities, "ttlSeconds", (int) defaults.ttl().toSeconds())),
                stringSet(capabilities, "allowedMcp"),
                stringSet(capabilities, "allowedDomains"),
                booleanValue(capabilities, "allowSecretReferences", false),
                enumValue(capabilities, "networkPolicy", SandboxPolicy.NetworkPolicy.class,
                        defaults.networkPolicy()));
    }

    private static SkillCapabilityPolicy defaults() {
        SandboxPolicy policy = SandboxPolicy.defaults();
        return new SkillCapabilityPolicy(policy.profile(), policy.cpuMillicores(), policy.memoryMiB(),
                policy.ephemeralStorageMiB(), policy.ttl(), policy.allowedMcp(), policy.allowedDomains(),
                policy.allowSecretReferences(), policy.networkPolicy());
    }

    private static void rejectUnknownFields(JsonNode capabilities) {
        Iterator<String> names = capabilities.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!CAPABILITY_FIELDS.contains(name)) {
                throw invalid("manifest.capabilities contains unknown field: " + name);
            }
        }
    }

    private static int positiveInt(JsonNode parent, String field, int defaultValue) {
        JsonNode value = parent.get(field);
        if (value == null) return defaultValue;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
            throw invalid("manifest.capabilities." + field + " must be a positive integer");
        }
        return value.intValue();
    }

    private static boolean booleanValue(JsonNode parent, String field, boolean defaultValue) {
        JsonNode value = parent.get(field);
        if (value == null) return defaultValue;
        if (!value.isBoolean()) throw invalid("manifest.capabilities." + field + " must be boolean");
        return value.booleanValue();
    }

    private static <E extends Enum<E>> E enumValue(JsonNode parent, String field, Class<E> type, E defaultValue) {
        JsonNode value = parent.get(field);
        if (value == null) return defaultValue;
        if (!value.isTextual()) throw invalid("manifest.capabilities." + field + " must be a string");
        try {
            return Enum.valueOf(type, value.asText().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw invalid("manifest.capabilities." + field + " is not supported: " + value.asText());
        }
    }

    private static java.util.Set<String> stringSet(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null) return java.util.Set.of();
        if (!value.isArray()) throw invalid("manifest.capabilities." + field + " must be an array of strings");
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw invalid("manifest.capabilities." + field + " must contain non-blank strings");
            }
            values.add(item.asText().trim());
        }
        return java.util.Set.copyOf(values);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private record SetOfFields(java.util.Set<String> values) {
        SetOfFields(String... values) {
            this(java.util.Set.of(values));
        }

        boolean contains(String value) {
            return values.contains(value);
        }
    }
}
