package io.agentteams.controlplane.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Parses the small, provider-neutral plan contract emitted by a Team Leader. */
public final class TeamPlanParser {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TITLE_LENGTH = 256;

    private TeamPlanParser() {
    }

    public static List<SubtaskService.SubtaskSpec> parse(UUID parentTaskId, String resultJson) {
        if (parentTaskId == null) throw new IllegalArgumentException("parentTaskId must not be null");
        JsonNode root = parseObject(resultJson);
        JsonNode plan = root.get("plan");
        if (plan == null || !plan.isArray() || plan.size() != 2) {
            throw new IllegalArgumentException("Leader plan must contain exactly two subtasks");
        }
        Map<Integer, UUID> ids = new HashMap<>();
        List<Integer> sequences = new ArrayList<>();
        for (JsonNode item : plan) {
            int sequence = item.path("sequence").asInt(0);
            if (sequence <= 0 || ids.put(sequence, id(parentTaskId, sequence)) != null) {
                throw new IllegalArgumentException("Leader plan sequences must be unique positive integers");
            }
            sequences.add(sequence);
        }
        List<SubtaskService.SubtaskSpec> specs = new ArrayList<>();
        for (JsonNode item : plan) {
            int sequence = item.path("sequence").asInt();
            String title = item.path("title").asText("").trim();
            if (title.isBlank() || title.length() > MAX_TITLE_LENGTH) {
                throw new IllegalArgumentException("Leader plan titles must be non-empty and at most 256 characters");
            }
            List<String> capabilities = new ArrayList<>();
            JsonNode capabilityNode = item.path("requiredCapabilities");
            if (!capabilityNode.isMissingNode()) {
                if (!capabilityNode.isArray()) throw new IllegalArgumentException("requiredCapabilities must be an array");
                capabilityNode.forEach(value -> {
                    if (!value.isTextual() || value.asText().isBlank()) {
                        throw new IllegalArgumentException("requiredCapabilities must contain non-empty strings");
                    }
                    capabilities.add(value.asText().trim());
                });
            }
            List<String> mcpTools = null;
            JsonNode mcpToolsNode = item.get("requiredMcpTools");
            if (mcpToolsNode != null) {
                if (!mcpToolsNode.isArray()) {
                    throw new IllegalArgumentException("requiredMcpTools must be an array");
                }
                List<String> parsedTools = new ArrayList<>();
                mcpToolsNode.forEach(value -> {
                    if (!value.isTextual() || value.asText().isBlank()) {
                        throw new IllegalArgumentException("requiredMcpTools must contain non-empty strings");
                    }
                    String tool = value.asText().trim();
                    if (parsedTools.contains(tool)) {
                        throw new IllegalArgumentException("requiredMcpTools must not contain duplicates");
                    }
                    parsedTools.add(tool);
                });
                mcpTools = parsedTools;
            }
            List<UUID> dependencies = new ArrayList<>();
            JsonNode dependencyNode = item.path("dependsOn");
            if (!dependencyNode.isMissingNode()) {
                if (!dependencyNode.isArray()) throw new IllegalArgumentException("dependsOn must be an array");
                dependencyNode.forEach(value -> {
                    int dependency = value.asInt(0);
                    UUID dependencyId = ids.get(dependency);
                    if (dependencyId == null || dependency == sequence) {
                        throw new IllegalArgumentException("dependsOn must reference another plan sequence");
                    }
                    dependencies.add(dependencyId);
                });
            }
            specs.add(new SubtaskService.SubtaskSpec(ids.get(sequence), title, sequence,
                    List.copyOf(new HashSet<>(dependencies)), List.copyOf(capabilities),
                    mcpTools == null ? null : List.copyOf(mcpTools)));
        }
        return List.copyOf(specs);
    }

    private static UUID id(UUID parentTaskId, int sequence) {
        return UUID.nameUUIDFromBytes(("team-plan:" + parentTaskId + ":" + sequence)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode parseObject(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Leader plan is empty");
        try {
            String normalized = extractJsonPayload(value);
            JsonNode root = JSON.readTree(normalized);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("Leader plan must be a JSON object");
            return root;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Leader plan must be valid JSON", error);
        }
    }

    /**
     * Models commonly wrap a valid plan in a short explanation and a fenced JSON
     * block. Keep the persisted plan contract JSON-only while accepting that
     * presentation form at the boundary.
     */
    private static String extractJsonPayload(String value) {
        String normalized = value.trim();
        int fenceStart = normalized.indexOf("```");
        if (fenceStart >= 0) {
            int contentStart = normalized.indexOf('\n', fenceStart);
            int fenceEnd = contentStart < 0 ? -1 : normalized.indexOf("```", contentStart + 1);
            if (fenceEnd > contentStart) {
                return normalized.substring(contentStart + 1, fenceEnd).trim();
            }
        }
        int objectStart = normalized.indexOf('{');
        int objectEnd = normalized.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            return normalized.substring(objectStart, objectEnd + 1).trim();
        }
        return normalized;
    }
}
