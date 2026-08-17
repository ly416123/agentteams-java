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

    public static void clear() {
        CURRENT.remove();
    }
}
