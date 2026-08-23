package io.agentteams.controlplane.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Immutable outbound controls shared by MCP and Skill policy checks. */
public record OutboundPolicy(
        Set<String> allowedSchemes,
        Set<String> allowedDomains,
        Duration maxTimeout,
        Set<String> allowedTools) {

    public static final Duration ABSOLUTE_MAX_TIMEOUT = Duration.ofSeconds(30);

    public OutboundPolicy {
        allowedSchemes = normalize(allowedSchemes, "allowedSchemes", true);
        allowedDomains = normalize(allowedDomains, "allowedDomains", false);
        allowedTools = normalize(allowedTools, "allowedTools", false);
        if (maxTimeout == null || maxTimeout.isZero() || maxTimeout.isNegative()) {
            throw new IllegalArgumentException("maxTimeout must be positive");
        }
    }

    public static OutboundPolicy legacyCompatible() {
        return new OutboundPolicy(Set.of("http", "https"), Set.of("*"), ABSOLUTE_MAX_TIMEOUT, Set.of("*"));
    }

    public String toJson() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.set("allowedSchemes", array(mapper, allowedSchemes));
        root.set("allowedDomains", array(mapper, allowedDomains));
        root.put("maxTimeout", maxTimeout.toMillis());
        root.set("allowedTools", array(mapper, allowedTools));
        return root.toString();
    }

    public static OutboundPolicy fromJson(String value) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(value);
            if (root == null || !root.isObject() || !root.path("maxTimeout").canConvertToLong()) {
                throw new IllegalArgumentException("maxTimeout must be an integer number of milliseconds");
            }
            return new OutboundPolicy(strings(root, "allowedSchemes"), strings(root, "allowedDomains"),
                    Duration.ofMillis(root.path("maxTimeout").longValue()), strings(root, "allowedTools"));
        } catch (IOException | RuntimeException error) {
            throw new IllegalArgumentException("stored outbound policy is invalid", error);
        }
    }

    private static ArrayNode array(ObjectMapper mapper, Set<String> values) {
        ArrayNode array = mapper.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    private static Set<String> strings(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        value.forEach(item -> {
            if (!item.isTextual()) throw new IllegalArgumentException(field + " must contain strings");
            result.add(item.asText());
        });
        return result;
    }

    private static Set<String> normalize(Set<String> values, String field, boolean lowerCase) {
        if (values == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not contain blank values");
            }
            String item = value.trim();
            normalized.add(lowerCase ? item.toLowerCase(Locale.ROOT) : item);
        }
        return Set.copyOf(normalized);
    }
}
