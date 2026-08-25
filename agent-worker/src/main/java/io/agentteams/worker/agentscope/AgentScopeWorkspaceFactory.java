package io.agentteams.worker.agentscope;

import io.agentteams.application.api.SandboxHandle;
import io.agentteams.application.api.SandboxProfile;
import io.agentteams.application.api.SandboxRuntimePort;
import io.agentteams.application.api.SandboxStatus;
import io.agentteams.runtime.AgentRuntimeContext;
import io.agentteams.runtime.RuntimeTask;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the platform sandbox reference into a provider-neutral AgentScope workspace binding.
 *
 * <p>This adapter deliberately does not import or call AgentScope workspace APIs. A later
 * {@code AgentScopeHarnessFactory} may consume {@link WorkspaceBinding#workspacePath()} when it
 * is present, while provider-backed bindings remain opaque to this module.</p>
 */
public final class AgentScopeWorkspaceFactory {
    private static final Set<SandboxStatus> USABLE_STATUSES = Set.of(
            SandboxStatus.READY, SandboxStatus.RUNNING);
    private final SandboxRuntimePort sandboxRuntime;
    private final Clock clock;
    private final Map<String, String> sandboxOwners = new java.util.HashMap<>();
    private final Map<String, String> attemptSandboxes = new java.util.HashMap<>();

    public AgentScopeWorkspaceFactory(SandboxRuntimePort sandboxRuntime, Clock clock) {
        this.sandboxRuntime = Objects.requireNonNull(sandboxRuntime, "sandboxRuntime");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Resolves a task attempt. Passing an empty handle is the explicit non-sandbox path.
     *
     * @param task runtime task whose metadata carries the control-plane scope
     * @param context runtime context; matching scope fields, when present, are checked
     * @param sandboxHandle the handle assigned to this attempt, or empty for {@code NONE}
     */
    public synchronized WorkspaceBinding resolve(RuntimeTask task, AgentRuntimeContext context,
            Optional<SandboxHandle> sandboxHandle) {
        SandboxProfile requestedProfile = sandboxHandle.map(SandboxHandle::profile).orElse(SandboxProfile.NONE);
        return resolve(task, context, requestedProfile, sandboxHandle);
    }

    /** Resolves a task attempt when the requested sandbox profile is known separately. */
    public synchronized WorkspaceBinding resolve(RuntimeTask task, AgentRuntimeContext context,
            SandboxProfile requestedProfile, Optional<SandboxHandle> sandboxHandle) {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(requestedProfile, "requestedProfile must not be null");
        Objects.requireNonNull(sandboxHandle, "sandboxHandle must not be null");

        Scope scope = scope(task, context);
        if (requestedProfile == SandboxProfile.NONE) {
            if (sandboxHandle.isPresent()) {
                throw new IllegalArgumentException("SandboxProfile.NONE must use an explicit non-sandbox binding");
            }
            return new WorkspaceBinding(scope.scopeId(), SandboxProfile.NONE,
                    Optional.empty(), Optional.empty(), Optional.empty());
        }

        if (sandboxHandle.isEmpty()) {
            throw new IllegalArgumentException("SandboxHandle is required for " + requestedProfile);
        }
        SandboxHandle handle = sandboxHandle.get();
        if (handle.profile() != requestedProfile) {
            throw new IllegalArgumentException("sandbox handle profile does not match requested profile");
        }
        if (!USABLE_STATUSES.contains(handle.status())) {
            throw new IllegalArgumentException("sandbox handle status must be READY or RUNNING");
        }
        Instant now = clock.instant();
        if (!now.isBefore(handle.expiresAt())) {
            throw new IllegalArgumentException("sandbox handle is expired");
        }

        SandboxStatus observedStatus;
        try {
            observedStatus = sandboxRuntime.inspect(handle.providerSandboxId());
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("sandbox state is unavailable", error);
        }
        if (!USABLE_STATUSES.contains(observedStatus)) {
            throw new IllegalArgumentException("sandbox is not usable: " + observedStatus);
        }

        Endpoint endpoint = endpoint(handle.endpointRef());
        String previousSandbox = attemptSandboxes.get(scope.scopeId());
        if (previousSandbox != null && !previousSandbox.equals(handle.providerSandboxId())) {
            throw new IllegalArgumentException("attempt is fenced to another sandbox");
        }
        String previousOwner = sandboxOwners.get(handle.providerSandboxId());
        if (previousOwner != null && !previousOwner.equals(scope.scopeId())) {
            throw new IllegalArgumentException("sandbox scope is fenced to another attempt");
        }
        sandboxOwners.putIfAbsent(handle.providerSandboxId(), scope.scopeId());
        attemptSandboxes.putIfAbsent(scope.scopeId(), handle.providerSandboxId());

        return new WorkspaceBinding(scope.scopeId(), handle.profile(), endpoint.path(),
                Optional.of(handle.providerSandboxId()), Optional.of(handle.expiresAt()));
    }

    private Scope scope(RuntimeTask task, AgentRuntimeContext context) {
        String tenantId = required(task, "tenantId");
        String projectId = required(task, "projectId");
        String teamId = required(task, "teamId");
        String agentId = required(task, "agentId");
        String attemptId = required(task, "attemptId");
        String taskId = task.id().toString();

        checkContextScope(context, "tenantId", tenantId);
        checkContextScope(context, "projectId", projectId);
        checkContextScope(context, "teamId", teamId);
        checkContextScope(context, "agentId", agentId);
        checkContextScope(context, "taskId", taskId);
        checkContextScope(context, "attemptId", attemptId);
        String metadataTaskId = task.metadata().get("taskId");
        if (metadataTaskId != null && !taskId.equals(metadataTaskId.trim())) {
            throw new IllegalArgumentException("taskId scope metadata does not match RuntimeTask");
        }

        String scopeId = stableScopeId(tenantId, projectId, teamId, agentId, taskId, attemptId);
        return new Scope(scopeId);
    }

    private static void checkContextScope(AgentRuntimeContext context, String field, String expected) {
        String supplied = context.configuration().get(field);
        if (supplied != null && !supplied.isBlank() && !expected.equals(supplied.trim())) {
            throw new IllegalArgumentException(field + " scope metadata does not match RuntimeTask");
        }
    }

    private static String required(RuntimeTask task, String field) {
        String value = task.metadata().get(field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("task metadata must contain " + field);
        }
        return value.trim();
    }

    private static String stableScopeId(String tenantId, String projectId, String teamId,
            String agentId, String taskId, String attemptId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : new String[] {tenantId, projectId, teamId, agentId, taskId, attemptId}) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) 0);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return "agentscope-scope-v1-" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static Endpoint endpoint(String endpointRef) {
        if (endpointRef == null || endpointRef.isBlank()) {
            throw new IllegalArgumentException("sandbox endpointRef must be present");
        }
        final URI uri;
        try {
            uri = new URI(endpointRef);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("sandbox endpointRef is not a URI", error);
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("sandbox endpointRef must use a controlled URI scheme");
        }
        if ("file".equalsIgnoreCase(scheme)) {
            return new Endpoint(filePath(uri));
        }
        if ("sandbox".equalsIgnoreCase(scheme)) {
            validateSandboxUri(uri);
            return new Endpoint(Optional.empty());
        }
        throw new IllegalArgumentException("sandbox endpointRef scheme is not allowed");
    }

    private static Optional<Path> filePath(URI uri) {
        if (uri.getRawAuthority() != null && !uri.getRawAuthority().isEmpty()
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("file endpointRef must not name a host");
        }
        String rawPath = uri.getRawPath();
        String path = uri.getPath();
        if (rawPath == null || path == null || path.isBlank() || !path.startsWith("/")) {
            throw new IllegalArgumentException("file endpointRef must contain an absolute path");
        }
        Path candidate;
        try {
            candidate = Paths.get(uri);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("file endpointRef is not a local absolute path", error);
        }
        if (!candidate.isAbsolute() || containsTraversal(candidate) || !candidate.normalize().equals(candidate)) {
            throw new IllegalArgumentException("file endpointRef contains an unsafe path");
        }
        return Optional.of(candidate);
    }

    private static boolean containsTraversal(Path path) {
        for (Path part : path) {
            if ("..".equals(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static void validateSandboxUri(URI uri) {
        if (uri.isOpaque() || uri.getRawAuthority() == null || uri.getRawAuthority().isBlank()
                || uri.getRawUserInfo() != null || uri.getPort() != -1
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getRawPath() == null || uri.getRawPath().isBlank()
                || containsTraversal(Paths.get(uri.getPath()))) {
            throw new IllegalArgumentException("sandbox endpointRef is not a controlled provider-neutral URI");
        }
    }

    private record Scope(String scopeId) {
    }

    private record Endpoint(Optional<Path> path) {
        private Endpoint {
            Objects.requireNonNull(path, "path");
        }
    }

    /** Data-only binding for a later Harness factory; it contains no AgentScope API type. */
    public record WorkspaceBinding(String scopeId, SandboxProfile profile, Optional<Path> workspacePath,
            Optional<String> sandboxId, Optional<Instant> expiresAt) {
        public WorkspaceBinding {
            if (scopeId == null || scopeId.isBlank()) {
                throw new IllegalArgumentException("scopeId must be non-blank");
            }
            Objects.requireNonNull(profile, "profile");
            workspacePath = Optional.ofNullable(workspacePath).orElseGet(Optional::empty);
            sandboxId = Optional.ofNullable(sandboxId).orElseGet(Optional::empty);
            expiresAt = Optional.ofNullable(expiresAt).orElseGet(Optional::empty);
            if (profile == SandboxProfile.NONE
                    && (!workspacePath.isEmpty() || !sandboxId.isEmpty() || !expiresAt.isEmpty())) {
                throw new IllegalArgumentException("NONE binding must not contain sandbox fields");
            }
            if (profile != SandboxProfile.NONE && sandboxId.isEmpty()) {
                throw new IllegalArgumentException("sandbox binding must contain sandboxId");
            }
        }
    }
}
