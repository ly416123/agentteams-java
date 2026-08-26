package io.agentteams.manager.security;

/** Request-scoped verified identity bridge used by Manager controllers and tests. */
public final class ManagerRequestContext {
    private static final ThreadLocal<ManagerPrincipal> CURRENT = new ThreadLocal<>();

    private ManagerRequestContext() { }

    public static ManagerPrincipal require() {
        ManagerPrincipal principal = CURRENT.get();
        if (principal == null) throw new ManagerAuthenticationException("authentication is required");
        return principal;
    }

    public static void set(ManagerPrincipal principal) {
        CURRENT.set(java.util.Objects.requireNonNull(principal, "principal"));
    }

    public static void clear() { CURRENT.remove(); }
}
