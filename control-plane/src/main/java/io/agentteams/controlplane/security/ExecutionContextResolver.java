package io.agentteams.controlplane.security;

import java.util.Objects;
import java.util.Optional;

/** Resolves the legacy authenticated scope into the platform execution context. */
public final class ExecutionContextResolver {
    private final ScopeDirectory directory;
    private final ProjectScopeResolver projectScopes;

    public ExecutionContextResolver(ScopeDirectory directory, ProjectScopeResolver projectScopes) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.projectScopes = Objects.requireNonNull(projectScopes, "projectScopes");
    }

    public ExecutionContext resolve(Principal principal) {
        Objects.requireNonNull(principal, "principal");
        AuthorizationService.Scope scope = principal.scope();
        ProjectScope projectScope = projectScopes.resolve(principal);
        ExecutionContext context = directory.resolve(scope.tenant(), projectScope.projectIdValue(), scope.team(),
                principal.subject())
                .orElseThrow(() -> new AuthorizationException("authenticated scope has no organization/tenant mapping"));
        if (!principal.subject().equals(context.subjectId())) {
            throw new AuthorizationException("execution context subject does not match authenticated principal");
        }
        return context;
    }

    @FunctionalInterface
    public interface ScopeDirectory {
        Optional<ExecutionContext> resolve(String legacyTenantId, String projectId, String teamId, String subjectId);
    }
}
