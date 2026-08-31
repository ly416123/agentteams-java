package io.agentteams.worker;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentteams.application.api.SandboxPolicy;
import io.agentteams.application.api.SandboxProfile;
import io.agentteams.application.api.SkillCapabilityPolicy;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Parses the sanitized Skill capability summary carried by a Worker manifest. */
final class SkillCapabilityPolicyLoader {
    private static final Set<String> FIELDS = Set.of("profile", "cpuMillicores", "memoryMiB",
            "ephemeralStorageMiB", "ttlSeconds", "networkPolicy", "allowedMcp", "allowedDomains",
            "allowSecretReferences");

    private SkillCapabilityPolicyLoader() {
    }

    static SkillCapabilityPolicy load(JsonNode node) {
        Objects.requireNonNull(node, "skillCapabilities");
        if (!node.isObject()) throw invalid("skillCapabilities must be an object");
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!FIELDS.contains(field)) throw invalid("unknown skillCapabilities field: " + field);
        }
        SandboxPolicy defaults = SandboxPolicy.defaults();
        return new SkillCapabilityPolicy(
                enumValue(node, "profile", SandboxProfile.class, defaults.profile()),
                positiveInt(node, "cpuMillicores", defaults.cpuMillicores()),
                positiveInt(node, "memoryMiB", defaults.memoryMiB()),
                positiveInt(node, "ephemeralStorageMiB", defaults.ephemeralStorageMiB()),
                Duration.ofSeconds(positiveInt(node, "ttlSeconds", (int) defaults.ttl().toSeconds())),
                stringSet(node, "allowedMcp"), stringSet(node, "allowedDomains"),
                booleanValue(node, "allowSecretReferences", false),
                enumValue(node, "networkPolicy", SandboxPolicy.NetworkPolicy.class, defaults.networkPolicy()));
    }

    private static int positiveInt(JsonNode parent, String field, int defaultValue) {
        JsonNode value = parent.get(field);
        if (value == null) return defaultValue;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
            throw invalid("skillCapabilities." + field + " must be a positive integer");
        }
        return value.intValue();
    }

    private static boolean booleanValue(JsonNode parent, String field, boolean defaultValue) {
        JsonNode value = parent.get(field);
        if (value == null) return defaultValue;
        if (!value.isBoolean()) throw invalid("skillCapabilities." + field + " must be boolean");
        return value.booleanValue();
    }

    private static <E extends Enum<E>> E enumValue(JsonNode parent, String field, Class<E> type, E defaultValue) {
        JsonNode value = parent.get(field);
        if (value == null) return defaultValue;
        if (!value.isTextual()) throw invalid("skillCapabilities." + field + " must be a string");
        try {
            return Enum.valueOf(type, value.asText().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw invalid("skillCapabilities." + field + " is not supported: " + value.asText());
        }
    }

    private static Set<String> stringSet(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null) return Set.of();
        if (!value.isArray()) throw invalid("skillCapabilities." + field + " must be an array of strings");
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw invalid("skillCapabilities." + field + " must contain non-blank strings");
            }
            values.add(item.asText().trim());
        }
        return Set.copyOf(values);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
