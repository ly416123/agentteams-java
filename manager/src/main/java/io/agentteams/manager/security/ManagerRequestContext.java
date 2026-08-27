package io.agentteams.manager.security;

/** Request-scoped verified identity bridge used by Manager controllers and tests. */
public final class ManagerRequestContext {
    private static final ThreadLocal<ManagerPrincipal> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<String> BEARER_TOKEN = new ThreadLocal<>();

    private ManagerRequestContext() { }

    public static ManagerPrincipal require() {
        ManagerPrincipal principal = CURRENT.get();
        if (principal == null) throw new ManagerAuthenticationException("authentication is required");
        return principal;
    }

    public static void set(ManagerPrincipal principal) {
        CURRENT.set(java.util.Objects.requireNonNull(principal, "principal"));
        BEARER_TOKEN.remove();
    }

    public static void set(ManagerPrincipal principal, String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new IllegalArgumentException("bearerToken is required");
        }
        CURRENT.set(java.util.Objects.requireNonNull(principal, "principal"));
        BEARER_TOKEN.set(bearerToken);
    }

    public static String requireBearerToken() {
        require();
        String token = BEARER_TOKEN.get();
        if (token == null || token.isBlank()) {
            throw new ManagerAuthenticationException("authenticated bearer token is required");
        }
        return token;
    }

    public static void clear() {
        CURRENT.remove();
        BEARER_TOKEN.remove();
    }
}
