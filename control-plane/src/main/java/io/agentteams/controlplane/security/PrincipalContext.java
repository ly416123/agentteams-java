package io.agentteams.controlplane.security;

import java.util.Optional;

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
