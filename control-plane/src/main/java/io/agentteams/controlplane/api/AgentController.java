package io.agentteams.controlplane.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.service.AgentService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agents")
public final class AgentController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final AgentService service;

    public AgentController(AgentService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<AgentResponse> create(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody CreateAgentRequest request) {
        requireRequest(request);
        requireIdempotencyKey(idempotencyKey);
        AgentRecord agent = service.create(idempotencyKey, request.toServiceInput());
        return ResponseEntity.status(201).body(AgentResponse.from(agent));
    }

    @GetMapping("/{id}")
    public AgentResponse get(@PathVariable UUID id) {
        return AgentResponse.from(service.get(id));
    }

    public record CreateAgentRequest(String name, String runtime, JsonNode capabilities, JsonNode metadata) {

        AgentService.AgentInput toServiceInput() {
            return new AgentService.AgentInput(name, runtime, json(capabilities), json(metadata));
        }

        private static String json(JsonNode value) {
            if (value == null || value.isNull()) {
                return "{}";
            }
            if (!value.isObject()) {
                throw new IllegalArgumentException("JSON object is required");
            }
            return value.toString();
        }
    }

    public record AgentResponse(UUID id, String name, String phase, String runtime,
            Instant createdAt, Instant updatedAt, long version) {

        static AgentResponse from(AgentRecord agent) {
            return new AgentResponse(agent.id(), agent.name(), agent.phase().name(), agent.runtime(),
                    agent.createdAt(), agent.updatedAt(), agent.version());
        }
    }

    private static void requireRequest(CreateAgentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
    }

    private static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        if (idempotencyKey.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must be at most 255 characters");
        }
    }
}
