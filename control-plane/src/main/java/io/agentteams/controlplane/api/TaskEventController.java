package io.agentteams.controlplane.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.persistence.DomainEventRecord;
import io.agentteams.controlplane.security.SensitiveFieldPolicy;
import io.agentteams.controlplane.service.TaskService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tasks")
public final class TaskEventController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TaskService service;

    public TaskEventController(TaskService service) { this.service = service; }

    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<String> events(@PathVariable UUID taskId,
            @RequestParam(defaultValue = "0") long after,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        if (after < 0) throw new IllegalArgumentException("after cursor must be non-negative");
        long cursor = Math.max(after, parseCursor(lastEventId));
        List<DomainEventRecord> events = service.events(taskId, cursor);
        StringBuilder stream = new StringBuilder();
        for (DomainEventRecord event : events) {
            stream.append("id: ").append(event.aggregateVersion()).append('\n')
                    .append("event: ").append(event.eventType()).append('\n')
                    .append("data: ").append(redact(event.payloadJson())).append("\n\n");
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(stream.toString());
    }

    private static String redact(String payload) {
        try {
            JsonNode root = JSON.readTree(payload);
            JsonNode redacted = redactNode(root);
            return JSON.writeValueAsString(redacted == null ? JSON.createObjectNode() : redacted);
        } catch (Exception ignored) {
            return "{\"redacted\":true}";
        }
    }

    private static JsonNode redactNode(JsonNode node) {
        if (node == null) return null;
        if (node.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode result = JSON.createObjectNode();
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (SensitiveFieldPolicy.isSensitive(entry.getKey())) continue;
                if (entry.getValue().isTextual() && SensitiveFieldPolicy.containsCredential(entry.getValue().textValue())) {
                    continue;
                }
                JsonNode child = redactNode(entry.getValue());
                if (child != null) result.set(entry.getKey(), child);
            }
            return result;
        } else if (node.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode result = JSON.createArrayNode();
            node.forEach(value -> {
                if (!value.isTextual() || !SensitiveFieldPolicy.containsCredential(value.textValue())) {
                    JsonNode child = redactNode(value);
                    if (child != null) result.add(child);
                }
            });
            return result;
        }
        if (node.isTextual() && SensitiveFieldPolicy.containsCredential(node.textValue())) return null;
        return node;
    }

    private static long parseCursor(String value) {
        if (value == null || value.isBlank()) return 0;
        try { long cursor = Long.parseLong(value); if (cursor < 0) throw new NumberFormatException(); return cursor; }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Last-Event-ID must be non-negative"); }
    }
}
