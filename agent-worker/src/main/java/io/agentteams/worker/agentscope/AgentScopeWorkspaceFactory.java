package io.agentteams.worker.agentscope;

import io.agentteams.application.api.SandboxHandle;
import io.agentteams.application.api.SandboxProfile;
import io.agentteams.application.api.SandboxRuntimePort;
import io.agentteams.application.api.SandboxStatus;
import io.agentteams.runtime.AgentRuntimeContext;
import io.agentteams.runtime.RuntimeTask;
import io.agentteams.worker.SandboxStateProbePort;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
    private final SandboxStateProbePort sandboxStateProbe;
    private final Clock clock;
    private final Path sandboxRoot;
    private final Path realSandboxRoot;
    private final SandboxWorkspaceOwnershipPort ownership;
    private final boolean testMode;

    public AgentScopeWorkspaceFactory(SandboxRuntimePort sandboxRuntime, Clock clock,
            Path sandboxRoot, SandboxWorkspaceOwnershipPort ownership) {
        this(sandboxRuntime, null, clock, sandboxRoot, ownership, false);
    }

    /** Production constructor using the worker's read-only sandbox state boundary. */
    public AgentScopeWorkspaceFactory(SandboxStateProbePort sandboxStateProbe, Clock clock,
            Path sandboxRoot, SandboxWorkspaceOwnershipPort ownership) {
        this(null, Objects.requireNonNull(sandboxStateProbe, "sandboxStateProbe"), clock,
                sandboxRoot, ownership, false);
    }

    /** Explicit test-only construction; it cannot be selected by production wiring. */
    public static AgentScopeWorkspaceFactory testOnly(SandboxRuntimePort sandboxRuntime, Clock clock,
            Path sandboxRoot) {
        return new AgentScopeWorkspaceFactory(sandboxRuntime, clock, sandboxRoot,
                new InMemorySandboxWorkspaceOwnershipPort(), true);
    }

    public AgentScopeWorkspaceFactory(SandboxRuntimePort sandboxRuntime, Clock clock,
            Path sandboxRoot, SandboxWorkspaceOwnershipPort ownership, boolean testMode) {
        this(sandboxRuntime, null, clock, sandboxRoot, ownership, testMode);
    }

    private AgentScopeWorkspaceFactory(SandboxRuntimePort sandboxRuntime,
            SandboxStateProbePort sandboxStateProbe, Clock clock, Path sandboxRoot,
            SandboxWorkspaceOwnershipPort ownership, boolean testMode) {
        if (sandboxRuntime == null && sandboxStateProbe == null) {
            throw new IllegalArgumentException("sandbox runtime or state probe must be configured");
        }
        this.sandboxRuntime = sandboxRuntime;
        this.sandboxStateProbe = sandboxStateProbe;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        this.testMode = testMode;
        if (!testMode && !ownership.durable()) {
            throw new IllegalArgumentException("production workspace ownership must be durable and shared");
        }
        this.sandboxRoot = normalizeRoot(sandboxRoot);
        this.realSandboxRoot = realRoot(this.sandboxRoot);
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

        UUID ownerAttemptId = attemptOwnerId(scope.attemptId());
        SandboxStatus observedStatus;
        try {
            observedStatus = inspectSandbox(task, handle.providerSandboxId(), ownerAttemptId);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("sandbox state is unavailable", error);
        }
        if (!USABLE_STATUSES.contains(observedStatus)) {
            throw new IllegalArgumentException("sandbox is not usable: " + observedStatus);
        }

        if (handle.taskId() == null || handle.attemptId() == null
                || !handle.taskId().equals(task.id()) || !handle.attemptId().equals(ownerAttemptId)) {
            throw new IllegalArgumentException("sandbox handle owner does not match task attempt");
        }
        Endpoint endpoint = endpoint(handle.endpointRef());
        SandboxWorkspaceOwnershipPort.WorkspaceOwner owner = new SandboxWorkspaceOwnershipPort.WorkspaceOwner(
                task.id(), ownerAttemptId, scope.scopeId());
        claimSandboxAndWorkspace(handle.providerSandboxId(), endpoint.path().orElse(null), owner);

        return new WorkspaceBinding(scope.scopeId(), handle.profile(), endpoint.path(),
                Optional.of(handle.providerSandboxId()), Optional.of(handle.expiresAt()),
                task.id(), ownerAttemptId);
    }

    /** Rechecks the provider, TTL, scope and shared ownership before an event or workspace write. */
    public void validateActive(WorkspaceBinding binding, RuntimeTask task, AgentRuntimeContext context) {
        assertUsable(binding, task, context);
    }

    /** Explicit active-use gate for every event or write operation. */
    public void assertUsable(WorkspaceBinding binding, RuntimeTask task, AgentRuntimeContext context) {
        Objects.requireNonNull(binding, "binding must not be null");
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Scope scope = scope(task, context);
        UUID ownerAttemptId = attemptOwnerId(scope.attemptId());
        if (!binding.scopeId().equals(scope.scopeId())) {
            throw new SandboxWorkspaceException(SandboxWorkspaceException.Reason.OWNER_MISMATCH);
        }
        if (binding.profile() == SandboxProfile.NONE) {
            return;
        }
        if (!task.id().equals(binding.taskId()) || !ownerAttemptId.equals(binding.attemptId())) {
            throw new SandboxWorkspaceException(SandboxWorkspaceException.Reason.OWNER_MISMATCH);
        }
        if (binding.sandboxId().isEmpty() || binding.expiresAt().isEmpty()) {
            throw new SandboxWorkspaceException(SandboxWorkspaceException.Reason.INACTIVE);
        }
        if (!clock.instant().isBefore(binding.expiresAt().get())) {
            throw new SandboxWorkspaceException(SandboxWorkspaceException.Reason.EXPIRED);
        }
        SandboxStatus observed;
        try {
            observed = inspectSandbox(task, binding.sandboxId().get(), ownerAttemptId);
        } catch (RuntimeException error) {
            throw new SandboxWorkspaceException(SandboxWorkspaceException.Reason.UNAVAILABLE);
        }
        if (!USABLE_STATUSES.contains(observed)) {
            throw new SandboxWorkspaceException(activeReason(observed));
        }
        binding.workspacePath().ifPresent(this::validateActiveWorkspacePath);
        SandboxWorkspaceOwnershipPort.WorkspaceOwner expected = new SandboxWorkspaceOwnershipPort.WorkspaceOwner(
                task.id(), ownerAttemptId, binding.scopeId());
        if (ownership.findSandboxOwner(binding.sandboxId().get()).filter(expected::equals).isEmpty()) {
            throw new SandboxWorkspaceException(SandboxWorkspaceException.Reason.OWNER_MISMATCH);
        }
        if (binding.workspacePath().isPresent()
                && ownership.findWorkspaceOwner(binding.workspacePath().get()).filter(expected::equals).isEmpty()) {
            throw new SandboxWorkspaceException(SandboxWorkspaceException.Reason.OWNER_MISMATCH);
        }
    }

    private SandboxStatus inspectSandbox(RuntimeTask task, String providerSandboxId,
            UUID ownerAttemptId) {
        if (sandboxStateProbe == null) {
            return sandboxRuntime.inspect(providerSandboxId);
        }
        UUID sandboxId = parseUuid(required(task, "sandboxId"), "sandboxId");
        SandboxStateProbePort.SandboxExecutionState state = sandboxStateProbe.inspect(
                sandboxId, task.id(), ownerAttemptId);
        if (state == null || !clock.instant().isBefore(state.expiresAt())) {
            throw new IllegalArgumentException("sandbox state is expired or unavailable");
        }
        return state.status();
    }

    /** Creates the runtime gate bound to one resolved attempt workspace. */
    public WorkspaceActiveGuard activeGuard(WorkspaceBinding binding) {
        Objects.requireNonNull(binding, "binding must not be null");
        return (task, context) -> assertUsable(binding, task, context);
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
        return new Scope(scopeId, attemptId);
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

    private void claimSandboxAndWorkspace(String providerSandboxId, Path workspacePath,
            SandboxWorkspaceOwnershipPort.WorkspaceOwner owner) {
        try {
            ownership.claimSandboxAndWorkspace(providerSandboxId, workspacePath, owner);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("sandbox/workspace ownership conflict for scope", error);
        }
    }

    private Endpoint endpoint(String endpointRef) {
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

    private Optional<Path> filePath(URI uri) {
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
        if (!candidate.isAbsolute() || containsTraversal(candidate) || !candidate.normalize().startsWith(sandboxRoot)) {
            throw new IllegalArgumentException("file endpointRef contains an unsafe path");
        }
        candidate = candidate.normalize();
        if (candidate.getFileName() != null && "docker.sock".equals(candidate.getFileName().toString())) {
            throw new IllegalArgumentException("file endpointRef names a forbidden socket");
        }
        validateRealPath(candidate);
        try {
            return Optional.of(Files.exists(candidate) ? candidate.toRealPath() : candidate);
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("workspace path cannot be resolved", error);
        }
    }

    private void validateRealPath(Path candidate) {
        try {
            if (Files.exists(candidate)) {
                if (Files.isSymbolicLink(candidate)
                        || !Files.isDirectory(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException("workspace path must be a real directory");
                }
                if (!candidate.toRealPath().startsWith(realSandboxRoot)) {
                    throw new IllegalArgumentException("workspace path escapes sandbox root");
                }
                return;
            }
            if (!testMode) {
                throw new IllegalArgumentException("workspace path must already exist");
            }
            Path existing = candidate;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            if (existing == null || !existing.toRealPath().startsWith(realSandboxRoot)) {
                throw new IllegalArgumentException("workspace path escapes sandbox root");
            }
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("workspace path cannot be verified", error);
        }
    }

    private void validateActiveWorkspacePath(Path candidate) {
        Path normalized = candidate.normalize();
        if (!normalized.isAbsolute() || containsTraversal(candidate)) {
            throw new SandboxWorkspaceException(SandboxWorkspaceException.Reason.OWNER_MISMATCH);
        }
        try {
            if (Files.isSymbolicLink(normalized)
                    || !Files.isDirectory(normalized, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new SandboxWorkspaceException(SandboxWorkspaceException.Reason.INACTIVE);
            }
            if (!normalized.toRealPath().startsWith(realSandboxRoot)) {
                throw new SandboxWorkspaceException(SandboxWorkspaceException.Reason.OWNER_MISMATCH);
            }
        } catch (java.io.IOException error) {
            throw new SandboxWorkspaceException(SandboxWorkspaceException.Reason.UNAVAILABLE);
        }
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

    private static SandboxWorkspaceException.Reason activeReason(SandboxStatus status) {
        return switch (status) {
            case LOST -> SandboxWorkspaceException.Reason.LOST;
            case EXPIRED -> SandboxWorkspaceException.Reason.EXPIRED;
            default -> SandboxWorkspaceException.Reason.INACTIVE;
        };
    }

    private static UUID attemptOwnerId(String attemptId) {
        try {
            return UUID.fromString(attemptId);
        } catch (IllegalArgumentException error) {
            return UUID.nameUUIDFromBytes(attemptId.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(field + " must be a UUID", error);
        }
    }

    private static Path defaultSandboxRoot() {
        return Paths.get(System.getProperty("java.io.tmpdir"), "agentteams-sandbox").toAbsolutePath().normalize();
    }

    private static Path ensureDefaultSandboxRoot() {
        Path root = defaultSandboxRoot();
        try {
            Files.createDirectories(root);
            return root;
        } catch (java.io.IOException error) {
            throw new IllegalStateException("default sandbox root cannot be created", error);
        }
    }

    private static Path normalizeRoot(Path root) {
        Objects.requireNonNull(root, "sandboxRoot must not be null");
        if (!root.isAbsolute()) {
            throw new IllegalArgumentException("sandboxRoot must be absolute");
        }
        Path normalized = root.normalize();
        if (!Files.exists(normalized) || !Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("sandboxRoot must be an existing directory");
        }
        return normalized;
    }

    private static Path realRoot(Path root) {
        try {
            return root.toRealPath();
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("sandboxRoot cannot be verified", error);
        }
    }

    private record Scope(String scopeId, String attemptId) {
    }

    private record Endpoint(Optional<Path> path) {
        private Endpoint {
            Objects.requireNonNull(path, "path");
        }
    }

    /** Data-only binding for a later Harness factory; it contains no AgentScope API type. */
    public record WorkspaceBinding(String scopeId, SandboxProfile profile, Optional<Path> workspacePath,
            Optional<String> sandboxId, Optional<Instant> expiresAt, UUID taskId, UUID attemptId) {
        public WorkspaceBinding(String scopeId, SandboxProfile profile, Optional<Path> workspacePath,
                Optional<String> sandboxId, Optional<Instant> expiresAt) {
            this(scopeId, profile, workspacePath, sandboxId, expiresAt, null, null);
        }

        public WorkspaceBinding {
            if (scopeId == null || scopeId.isBlank()) {
                throw new IllegalArgumentException("scopeId must be non-blank");
            }
            Objects.requireNonNull(profile, "profile");
            workspacePath = Optional.ofNullable(workspacePath).orElseGet(Optional::empty);
            sandboxId = Optional.ofNullable(sandboxId).orElseGet(Optional::empty);
            expiresAt = Optional.ofNullable(expiresAt).orElseGet(Optional::empty);
            if ((taskId == null) != (attemptId == null)) {
                throw new IllegalArgumentException("binding task and attempt owners must be supplied together");
            }
            if (profile == SandboxProfile.NONE
                    && (!workspacePath.isEmpty() || !sandboxId.isEmpty() || !expiresAt.isEmpty()
                    || taskId != null || attemptId != null)) {
                throw new IllegalArgumentException("NONE binding must not contain sandbox fields");
            }
            if (profile != SandboxProfile.NONE && sandboxId.isEmpty()) {
                throw new IllegalArgumentException("sandbox binding must contain sandboxId");
            }
            if (profile != SandboxProfile.NONE && (taskId == null || attemptId == null)) {
                throw new IllegalArgumentException("sandbox binding must contain owner");
            }
        }
    }
}
