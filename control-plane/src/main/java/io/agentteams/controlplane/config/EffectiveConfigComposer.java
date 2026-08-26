package io.agentteams.controlplane.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Composes Team configuration without allowing an overlay to weaken security. */
public final class EffectiveConfigComposer {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> RESTRICTIVE_ARRAYS = Set.of("permissions", "capabilities");
    private static final List<String> SECURITY_PROFILES = List.of("NONE", "ISOLATED", "HARDENED");

    public EffectiveConfig compose(EffectiveConfigRequest request) {
        Objects.requireNonNull(request, "request");
        ObjectNode result = object(request.baseManifest(), "baseManifest");
        applyOverlay(result, object(request.teamOverlay(), "teamOverlay"));
        applyOverlay(result, object(request.taskOverlay(), "taskOverlay"));
        String canonical = ConfigManifestCanonicalizer.normalize(result.toString());
        return new EffectiveConfig(canonical, sha256(canonical),
                new ConfigProvenance(request.agentBaseSnapshotId(), request.agentId(), request.teamId(),
                        request.teamRevision(), request.taskId(), request.bindingDigests(), "v1"));
    }

    private static void applyOverlay(ObjectNode target, ObjectNode overlay) {
        overlay.fields().forEachRemaining(entry -> mergeField(target, entry.getKey(), entry.getValue()));
    }

    private static void mergeField(ObjectNode target, String key, JsonNode value) {
        validateSecurityField(key, value);
        JsonNode current = target.get(key);
        if ("sandboxProfile".equals(key) && value.isTextual()) {
            target.put(key, saferProfile(current == null ? "NONE" : current.asText(), value.asText()));
        } else if (RESTRICTIVE_ARRAYS.contains(key) && value.isArray()) {
            target.set(key, restrictiveArray(key, current, value));
        } else if (current != null && current.isObject() && value.isObject()) {
            applyOverlay((ObjectNode) current, (ObjectNode) value);
        } else if (value.isArray()) {
            target.set(key, mergedArray(current, value));
        } else {
            target.set(key, value.deepCopy());
        }
    }

    private static ArrayNode mergedArray(JsonNode current, JsonNode overlay) {
        ArrayNode merged = JsonNodeFactory.instance.arrayNode();
        if (current != null && current.isArray()) current.forEach(merged::add);
        overlay.forEach(merged::add);
        return uniqueSortedArray(merged);
    }

    private static ArrayNode restrictiveArray(String key, JsonNode current, JsonNode overlay) {
        Set<String> previous = canonicalValues(current);
        Set<String> next = canonicalValues(overlay);
        if (!previous.containsAll(next)) {
            throw new EffectiveConfigConflictException("EFFECTIVE_CONFIG_ESCALATION_REJECTED",
                    key + " overlay may only restrict the inherited set");
        }
        return uniqueSortedArray(overlay);
    }

    private static ArrayNode uniqueSortedArray(JsonNode value) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        List<JsonNode> values = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        value.forEach(item -> {
            String canonical = ConfigManifestCanonicalizer.normalize(item.toString());
            if (seen.add(canonical)) values.add(item.deepCopy());
        });
        values.sort(Comparator.comparing(item -> ConfigManifestCanonicalizer.normalize(item.toString())));
        values.forEach(result::add);
        return result;
    }

    private static Set<String> canonicalValues(JsonNode value) {
        Set<String> result = new LinkedHashSet<>();
        if (value != null && value.isArray()) {
            value.forEach(item -> result.add(ConfigManifestCanonicalizer.normalize(item.toString())));
        }
        return result;
    }

    private static String saferProfile(String inherited, String requested) {
        int inheritedLevel = profileLevel(inherited);
        int requestedLevel = profileLevel(requested);
        return SECURITY_PROFILES.get(Math.max(inheritedLevel, requestedLevel));
    }

    private static int profileLevel(String profile) {
        int level = SECURITY_PROFILES.indexOf(profile == null ? "NONE" : profile.trim().toUpperCase());
        if (level < 0) throw new EffectiveConfigConflictException("EFFECTIVE_CONFIG_CONFLICT",
                "unknown sandbox profile");
        return level;
    }

    private static ObjectNode object(String json, String name) {
        try {
            JsonNode value = MAPPER.readTree(json);
            if (value == null || !value.isObject()) throw new IllegalArgumentException(name + " must be a JSON object");
            validateSecurityTypes(value);
            return (ObjectNode) MAPPER.readTree(ConfigManifestCanonicalizer.normalize(value.toString()));
        } catch (EffectiveConfigConflictException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException(name + " must be valid JSON", error);
        }
    }

    private static void validateSecurityTypes(JsonNode node) {
        if (!node.isObject() && !node.isArray()) return;
        if (node.isArray()) {
            node.forEach(EffectiveConfigComposer::validateSecurityTypes);
            return;
        }
        node.fields().forEachRemaining(entry -> {
            validateSecurityField(entry.getKey(), entry.getValue());
            validateSecurityTypes(entry.getValue());
        });
    }

    private static void validateSecurityField(String key, JsonNode value) {
        if (RESTRICTIVE_ARRAYS.contains(key) && !value.isArray()) {
            throw new EffectiveConfigConflictException("EFFECTIVE_CONFIG_INVALID_TYPE",
                    key + " must be an array");
        }
        if ("sandboxProfile".equals(key) && !value.isTextual()) {
            throw new EffectiveConfigConflictException("EFFECTIVE_CONFIG_INVALID_TYPE",
                    "sandboxProfile must be a string");
        }
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
