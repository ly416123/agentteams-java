package io.agentteams.manager.security;

/** Request-scoped verified identity bridge used by Manager controllers and tests. */
public final class ManagerRequestContext {
    private static final ThreadLocal<ManagerPrincipal> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<ManagerPrincipal> AUTHENTICATED = new ThreadLocal<>();
    private static final ThreadLocal<String> BEARER_TOKEN = new ThreadLocal<>();

    private ManagerRequestContext() { }

    public static ManagerPrincipal require() {
        ManagerPrincipal principal = CURRENT.get();
        if (principal == null) throw new ManagerAuthenticationException("authentication is required");
        return principal;
    }

    public static java.util.Optional<ManagerPrincipal> current() {
        return java.util.Optional.ofNullable(CURRENT.get());
    }

    public static void requireScope(String tenantId, String projectId) {
        ManagerPrincipal principal = require();
        if (!principal.tenantId().equals(tenantId) || !principal.projectId().equals(projectId)) {
            throw new ManagerAuthorizationException("resource is outside the caller scope");
        }
    }

    public static void set(ManagerPrincipal principal) {
        CURRENT.set(java.util.Objects.requireNonNull(principal, "principal"));
        AUTHENTICATED.set(principal);
        BEARER_TOKEN.remove();
    }

    public static void set(ManagerPrincipal principal, String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new IllegalArgumentException("bearerToken is required");
        }
        CURRENT.set(java.util.Objects.requireNonNull(principal, "principal"));
        AUTHENTICATED.set(principal);
        BEARER_TOKEN.set(bearerToken);
    }

    /** Stores the effective resource scope and the original OIDC scope separately. */
    public static void set(ManagerPrincipal effective, String bearerToken, ManagerPrincipal authenticated) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new IllegalArgumentException("bearerToken is required");
        }
        CURRENT.set(java.util.Objects.requireNonNull(effective, "effective"));
        AUTHENTICATED.set(java.util.Objects.requireNonNull(authenticated, "authenticated"));
        BEARER_TOKEN.set(bearerToken);
    }

    /** Scope form carried by the bearer token to downstream APIs. */
    public static ManagerPrincipal requireAuthenticated() {
        ManagerPrincipal principal = AUTHENTICATED.get();
        if (principal == null) throw new ManagerAuthenticationException("authentication is required");
        return principal;
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
        AUTHENTICATED.remove();
        BEARER_TOKEN.remove();
    }
}
