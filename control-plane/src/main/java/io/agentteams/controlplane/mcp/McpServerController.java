package io.agentteams.controlplane.mcp;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/mcp-servers")
public final class McpServerController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private final McpServerService service;

    public McpServerController(McpServerService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<McpServerResponse> create(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody CreateRequest request) {
        requireRequest(request);
        requireIdempotencyKey(idempotencyKey);
        return ResponseEntity.status(201).body(McpServerResponse.from(
                service.create(idempotencyKey, request.toServiceInput())));
    }

    @GetMapping
    public List<McpServerResponse> list() {
        return service.list().stream().map(McpServerResponse::from).toList();
    }

    @GetMapping("/{id}")
    public McpServerResponse get(@PathVariable UUID id) {
        return McpServerResponse.from(service.get(id));
    }

    @PutMapping("/{id}")
    public McpServerResponse update(@PathVariable UUID id, @RequestBody UpdateRequest request) {
        requireRequest(request);
        return McpServerResponse.from(service.update(id, request.toServiceInput()));
    }

    @PatchMapping("/{id}/health")
    public McpServerResponse updateHealth(@PathVariable UUID id, @RequestBody HealthRequest request) {
        requireRequest(request);
        return McpServerResponse.from(service.updateHealth(id, request.toServiceInput()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    public record CreateRequest(String name, String transport, String endpoint, String credentialRef,
            Boolean enabled, String healthStatus, Instant lastCheckedAt) {

        McpServerService.CreateInput toServiceInput() {
            return new McpServerService.CreateInput(name, transport, endpoint, credentialRef, enabled,
                    healthStatus, lastCheckedAt);
        }
    }

    public record UpdateRequest(String name, String transport, String endpoint, String credentialRef,
            Boolean enabled, String healthStatus, Instant lastCheckedAt) {

        McpServerService.UpdateInput toServiceInput() {
            return new McpServerService.UpdateInput(name, transport, endpoint, credentialRef, enabled,
                    healthStatus, lastCheckedAt);
        }
    }

    public record HealthRequest(String healthStatus, Instant lastCheckedAt) {

        McpServerService.HealthInput toServiceInput() {
            return new McpServerService.HealthInput(healthStatus, lastCheckedAt);
        }
    }

    public record McpServerResponse(UUID id, String name, McpTransport transport, String endpoint,
            boolean credentialConfigured, boolean enabled, McpHealthStatus healthStatus, Instant lastCheckedAt,
            Instant createdAt, Instant updatedAt, long version) {

        static McpServerResponse from(McpServerRecord server) {
            return new McpServerResponse(server.id(), server.name(), server.transport(), server.endpoint(),
                    server.credentialRef() != null && !server.credentialRef().isBlank(), server.enabled(),
                    server.healthStatus(), server.lastCheckedAt(), server.createdAt(), server.updatedAt(),
                    server.version());
        }
    }

    private static void requireRequest(Object request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
    }

    private static void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        if (key.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must be at most 255 characters");
        }
    }
}
