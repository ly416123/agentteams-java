package io.agentteams.controlplane.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TeamCrdParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_VERSION = "agentteams.io/v1alpha1";

    public TeamCrdSnapshot parse(GenericKubernetesResource resource) {
        if (resource == null || resource.getMetadata() == null) {
            throw new IllegalArgumentException("Team metadata is required");
        }
        if (!API_VERSION.equals(resource.getApiVersion()) || !"Team".equals(resource.getKind())) {
            throw new IllegalArgumentException("unsupported Team apiVersion or kind");
        }
        String namespace = text(resource.getMetadata().getNamespace(), "metadata.namespace");
        String name = text(resource.getMetadata().getName(), "metadata.name");
        String resourceVersion = text(resource.getMetadata().getResourceVersion(), "metadata.resourceVersion");
        JsonNode spec = MAPPER.valueToTree(resource.getAdditionalProperties()).path("spec");
        if (!spec.isObject()) {
            throw new IllegalArgumentException("spec must be an object");
        }

        UUID leaderId = uuid(spec.path("leaderRef"), "spec.leaderRef");
        JsonNode members = spec.path("members");
        if (!members.isArray()) {
            throw new IllegalArgumentException("spec.members must be an array");
        }
        List<TeamCrdSnapshot.Member> parsedMembers = new ArrayList<>();
        LinkedHashSet<UUID> memberIds = new LinkedHashSet<>();
        for (JsonNode member : members) {
            UUID agentId = uuid(member.path("agentRef"), "spec.members[].agentRef");
            if (!memberIds.add(agentId)) {
                throw new IllegalArgumentException("duplicate Team member agentRef: " + agentId);
            }
            String role = text(member.path("role"), "spec.members[].role");
            parsedMembers.add(new TeamCrdSnapshot.Member(agentId, role,
                    strings(member.path("capabilities"), "spec.members[].capabilities")));
        }

        JsonNode policy = spec.path("policy");
        if (!policy.isObject()) {
            throw new IllegalArgumentException("spec.policy must be an object");
        }
        int maxConcurrent = requiredInt(policy, "maxConcurrentTasks");
        boolean requireApproval = requiredBoolean(policy, "requireApproval");
        List<String> allowedRuntimes = optionalStrings(policy.path("allowedRuntimes"),
                "spec.policy.allowedRuntimes");
        List<String> requiredCapabilities = optionalStrings(policy.path("requiredCapabilities"),
                "spec.policy.requiredCapabilities");
        return new TeamCrdSnapshot(stableId(namespace, name), namespace, name, namespace + "/" + name,
                resourceVersion, leaderId, parsedMembers,
                new TeamCrdSnapshot.Policy(maxConcurrent, requireApproval, allowedRuntimes, requiredCapabilities));
    }

    public static UUID stableId(String namespace, String name) {
        return UUID.nameUUIDFromBytes((API_VERSION + "/" + namespace + "/" + name)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> strings(JsonNode node, String field) {
        if (!node.isArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return optionalStrings(node, field);
    }

    private static List<String> optionalStrings(JsonNode node, String field) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode value : node) {
            String text = text(value, field + "[]");
            values.add(text);
        }
        return List.copyOf(values);
    }

    private static int requiredInt(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isIntegralNumber() || value.asInt() < 1) {
            throw new IllegalArgumentException("spec.policy." + field + " must be positive");
        }
        return value.asInt();
    }

    private static boolean requiredBoolean(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isBoolean()) {
            throw new IllegalArgumentException("spec.policy." + field + " must be boolean");
        }
        return value.asBoolean();
    }

    private static UUID uuid(JsonNode value, String field) {
        String raw = text(value, field);
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(field + " must be a UUID", error);
        }
    }

    private static String text(JsonNode value, String field) {
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.asText().trim();
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
