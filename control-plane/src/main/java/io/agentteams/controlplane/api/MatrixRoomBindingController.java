package io.agentteams.controlplane.api;

import io.agentteams.controlplane.matrix.MatrixRoomBinding;
import io.agentteams.controlplane.matrix.MatrixRoomBindingService;
import io.agentteams.controlplane.matrix.MatrixRoomContextType;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.security.ExecutionContextResolver;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceAction;
import io.agentteams.controlplane.security.ResourceAuthorizationService;
import io.agentteams.controlplane.security.ResourceRef;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Management API for the authoritative Matrix room-to-context binding lifecycle. */
@RestController
@RequestMapping("/api/v1/matrix/rooms/bindings")
public final class MatrixRoomBindingController {
    private final MatrixRoomBindingService service;
    private final ExecutionContextResolver contexts;
    private final ResourceAuthorizationService authorization;

    public MatrixRoomBindingController(MatrixRoomBindingService service, ExecutionContextResolver contexts,
            ResourceAuthorizationService authorization) {
        this.service = Objects.requireNonNull(service, "service");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
    }

    @PostMapping
    public ResponseEntity<BindingResponse> create(@RequestBody BindingRequest request) {
        Objects.requireNonNull(request, "request");
        requirePrincipal();
        authorize(request.projectId(), request.teamId());
        ExecutionContext context = context();
        MatrixRoomBinding binding = service.bind(context.organizationId(), context.tenantId(), request.roomId(),
                request.contextType(), request.contextId(), request.projectId(), request.teamId(), actor());
        return ResponseEntity.status(201).body(BindingResponse.from(binding));
    }

    @GetMapping
    public List<BindingResponse> list(@RequestParam(required = false) MatrixRoomContextType contextType,
            @RequestParam(required = false) UUID contextId) {
        requirePrincipal();
        authorizeCurrentScope();
        return service.list(context().tenantId(), contextType, contextId).stream().map(BindingResponse::from).toList();
    }

    @PostMapping("/{id}/migrate")
    public BindingResponse migrate(@PathVariable UUID id, @RequestBody BindingRequest request) {
        Objects.requireNonNull(request, "request");
        requirePrincipal();
        authorize(request.projectId(), request.teamId());
        MatrixRoomBinding binding = service.migrate(context().tenantId(), id, request.contextType(),
                request.contextId(), request.projectId(), request.teamId(), request.expectedVersionOrZero(), actor());
        return BindingResponse.from(binding);
    }

    @PostMapping("/{id}/revoke")
    public BindingResponse revoke(@PathVariable UUID id, @RequestBody VersionRequest request) {
        requirePrincipal();
        authorizeCurrentScope();
        MatrixRoomBinding binding = service.revoke(context().tenantId(), id, request == null ? null
                : request.expectedVersion(), actor());
        return BindingResponse.from(binding);
    }

    private void authorize(UUID projectId, UUID teamId) {
        if (projectId == null) throw new IllegalArgumentException("projectId is required");
        authorization.require(ResourceAction.TEAM_WRITE, teamId == null
                ? ResourceRef.project(context().tenantId(), projectId)
                : ResourceRef.team(context().tenantId(), projectId, teamId.toString()));
    }

    private void authorizeCurrentScope() {
        authorization.require(ResourceAction.TEAM_WRITE, PrincipalContext.current()
                .orElseThrow(() -> new AuthorizationException("authentication required")).scope());
    }

    private ExecutionContext context() {
        Principal principal = requirePrincipal();
        return contexts.resolve(principal);
    }

    private static Principal requirePrincipal() {
        return PrincipalContext.current()
                .orElseThrow(() -> new AuthorizationException("authentication required"));
    }

    private static String actor() {
        return PrincipalContext.actorOr("api");
    }

    public record BindingRequest(String roomId, MatrixRoomContextType contextType, UUID contextId, UUID projectId,
            UUID teamId, Long expectedVersion) {
        public BindingRequest {
            if (roomId == null || roomId.isBlank()) throw new IllegalArgumentException("roomId is required");
            if (contextType == null) throw new IllegalArgumentException("contextType is required");
            if (contextId == null) throw new IllegalArgumentException("contextId is required");
            if (projectId == null) throw new IllegalArgumentException("projectId is required");
            if (expectedVersion != null && expectedVersion < 0) {
                throw new IllegalArgumentException("expectedVersion must not be negative");
            }
        }

        public long expectedVersionOrZero() {
            return expectedVersion == null ? 0 : expectedVersion;
        }
    }

    public record VersionRequest(Long expectedVersion) {
        public VersionRequest {
            if (expectedVersion == null || expectedVersion < 0) {
                throw new IllegalArgumentException("expectedVersion is required");
            }
        }
    }

    public record BindingResponse(UUID id, String organizationId, String tenantId, String roomId,
            MatrixRoomContextType primaryContextType, UUID primaryContextId, UUID projectId, UUID teamId,
            String status, String createdBy, Instant createdAt, Instant updatedAt, long version) {
        static BindingResponse from(MatrixRoomBinding binding) {
            return new BindingResponse(binding.id(), binding.organizationId(), binding.tenantId(), binding.roomId(),
                    binding.primaryContextType(), binding.primaryContextId(), binding.projectId(), binding.teamId(),
                    binding.status().name(), binding.createdBy(), binding.createdAt(), binding.updatedAt(),
                    binding.version());
        }
    }
}
