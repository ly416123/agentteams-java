package io.agentteams.controlplane.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.persistence.DomainEventRecord;
import io.agentteams.controlplane.service.TaskService;
import java.util.List;
import java.util.Set;
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
    private static final Set<String> SENSITIVE = Set.of("token", "accesstoken", "refreshtoken", "secret",
            "secretgeneration", "requestedsecretgeneration", "password", "authorization", "credential",
            "apikey", "containerlog", "previousstablespec", "log", "logs");
    private final TaskService service;

    public TaskEventController(TaskService service) { this.service = service; }

    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<String> events(@PathVariable UUID taskId,
            @RequestParam(defaultValue = "0") long after,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
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
            redactNode(root);
            return JSON.writeValueAsString(root);
        } catch (Exception ignored) {
            return "{\"redacted\":true}";
        }
    }

    private static void redactNode(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (SENSITIVE.contains(normalize(entry.getKey()))) {
                    entry.setValue(JSON.getNodeFactory().textNode("[REDACTED]"));
                } else redactNode(entry.getValue());
            }
        } else if (node.isArray()) node.forEach(TaskEventController::redactNode);
    }

    private static String normalize(String key) {
        return key.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static long parseCursor(String value) {
        if (value == null || value.isBlank()) return 0;
        try { long cursor = Long.parseLong(value); if (cursor < 0) throw new NumberFormatException(); return cursor; }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Last-Event-ID must be non-negative"); }
    }
}
