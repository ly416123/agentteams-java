package io.agentteams.controlplane.mcp;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import io.agentteams.controlplane.security.OutboundPolicy;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final McpDiscoveryAggregationService discoveryAggregation;
    private final McpHealthProbeService healthProbe;

    public McpServerController(McpServerService service) {
        this(service, null, null);
    }

    public McpServerController(McpServerService service, McpDiscoveryAggregationService discoveryAggregation) {
        this(service, discoveryAggregation, null);
    }

    @Autowired
    public McpServerController(McpServerService service, McpDiscoveryAggregationService discoveryAggregation,
            McpHealthProbeService healthProbe) {
        this.service = service;
        this.discoveryAggregation = discoveryAggregation;
        this.healthProbe = healthProbe;
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

    @GetMapping("/{id}/discovery")
    public DiscoveryResponse discovery(@PathVariable UUID id) {
        McpServerRecord server = service.get(id);
        if (discoveryAggregation == null) {
            throw new IllegalStateException("MCP discovery aggregation is not configured");
        }
        return DiscoveryResponse.from(discoveryAggregation.aggregate(server.id(), server.version()));
    }

    @PostMapping("/{id}/connection-test")
    public McpHealthProbeResult connectionTest(@PathVariable UUID id) {
        if (healthProbe == null) {
            throw new IllegalStateException("MCP health probe is not configured");
        }
        return healthProbe.probe(id, Duration.ofSeconds(5));
    }

    @PutMapping("/{id}")
    public McpServerResponse update(@PathVariable UUID id, @RequestBody UpdateRequest request) {
        requireRequest(request);
        McpServerRecord updated = request.expectedVersion() == null
                ? service.update(id, request.toServiceInput())
                : service.update(id, request.toServiceInput(), request.expectedVersion());
        return McpServerResponse.from(updated);
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
            Boolean enabled, String healthStatus, Instant lastCheckedAt, OutboundPolicy outboundPolicy) {
        public CreateRequest(String name, String transport, String endpoint, String credentialRef,
                Boolean enabled, String healthStatus, Instant lastCheckedAt) {
            this(name, transport, endpoint, credentialRef, enabled, healthStatus, lastCheckedAt, null);
        }

        McpServerService.CreateInput toServiceInput() {
            return new McpServerService.CreateInput(name, transport, endpoint, credentialRef, enabled,
                    healthStatus, lastCheckedAt, outboundPolicy);
        }
    }

    public record UpdateRequest(String name, String transport, String endpoint, String credentialRef,
            Boolean enabled, String healthStatus, Instant lastCheckedAt, OutboundPolicy outboundPolicy,
            Long expectedVersion) {
        public UpdateRequest(String name, String transport, String endpoint, String credentialRef,
                Boolean enabled, String healthStatus, Instant lastCheckedAt) {
            this(name, transport, endpoint, credentialRef, enabled, healthStatus, lastCheckedAt, null, null);
        }

        McpServerService.UpdateInput toServiceInput() {
            return new McpServerService.UpdateInput(name, transport, endpoint, credentialRef, enabled,
                    healthStatus, lastCheckedAt, outboundPolicy);
        }
    }

    public record HealthRequest(String healthStatus, Instant lastCheckedAt) {

        McpServerService.HealthInput toServiceInput() {
            return new McpServerService.HealthInput(healthStatus, lastCheckedAt);
        }
    }

    public record McpServerResponse(UUID id, String name, McpTransport transport, String endpoint,
            boolean credentialConfigured, boolean enabled, McpHealthStatus healthStatus, Instant lastCheckedAt,
            Instant createdAt, Instant updatedAt, long version, OutboundPolicy outboundPolicy) {

        static McpServerResponse from(McpServerRecord server) {
            return new McpServerResponse(server.id(), server.name(), server.transport(), server.endpoint(),
                    server.credentialRef() != null && !server.credentialRef().isBlank(), server.enabled(),
                    server.healthStatus(), server.lastCheckedAt(), server.createdAt(), server.updatedAt(),
                    server.version(), server.outboundPolicy());
        }
    }

    public record DiscoveryResponse(UUID serverId, long serverRevision, McpDiscoveryStatus status,
            String toolsDigest, int healthyInstances, int freshInstances, Instant latestObservedAt,
            List<String> failureCategories) {

        static DiscoveryResponse from(McpDiscoveryAggregate aggregate) {
            return new DiscoveryResponse(aggregate.serverId(), aggregate.serverRevision(), aggregate.status(),
                    aggregate.toolsDigest(), aggregate.healthyInstances(), aggregate.freshInstances(),
                    aggregate.latestObservedAt(), aggregate.failureCategories());
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
