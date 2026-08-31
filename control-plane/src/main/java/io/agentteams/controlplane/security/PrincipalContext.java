package io.agentteams.controlplane.security;

import java.util.Optional;
import java.util.Objects;

/** Request-scoped identity bridge shared by HTTP adapters and application services. */
public final class PrincipalContext {
    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private PrincipalContext() { }

    public static void set(Principal principal) {
        CURRENT.set(java.util.Objects.requireNonNull(principal, "principal"));
    }

    public static Optional<Principal> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static String actorOr(String fallback) {
        return CURRENT.get() == null ? fallback : CURRENT.get().subject();
    }

    /** Resolves the authenticated legacy scope into the unified execution context. */
    public static Optional<ExecutionContext> executionContext(ExecutionContextResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        Principal principal = CURRENT.get();
        return principal == null ? Optional.empty() : Optional.of(resolver.resolve(principal));
    }

    /** No-op for internal/unauthenticated development calls; strict for OIDC requests. */
    public static void requireScope(String resourceJson) {
        Principal principal = CURRENT.get();
        if (principal != null) {
            new AuthorizationService().requireScope(principal, resourceJson);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }
}
