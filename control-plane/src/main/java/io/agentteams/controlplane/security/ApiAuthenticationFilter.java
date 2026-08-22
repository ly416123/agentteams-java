package io.agentteams.controlplane.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Optional HTTP authentication boundary; JWT/OIDC verification is supplied by IdentityTokenValidator. */
public final class ApiAuthenticationFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL_ATTRIBUTE = ApiAuthenticationFilter.class.getName() + ".principal";

    private final IdentityTokenValidator validator;

    public ApiAuthenticationFilter(IdentityTokenValidator validator) {
        this.validator = java.util.Objects.requireNonNull(validator, "validator");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            unauthorized(response);
            return;
        }
        String token = header.substring(7).trim();
        if (token.isEmpty()) {
            unauthorized(response);
            return;
        }
        IdentityTokenValidator.IdentityPrincipal identity;
        try {
            identity = validator.validate(token).orElse(null);
        } catch (RuntimeException ignored) {
            identity = null;
        }
        if (identity == null) {
            unauthorized(response);
            return;
        }

        Principal principal = new Principal(identity.subject(), identity.scope(), identity.permissions());
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        PrincipalContext.set(principal);
        try {
            try {
                ApiAuthorizationPolicy.requiredPermission(request)
                        .ifPresent(permission -> new AuthorizationService().require(
                                principal.subject(), permission, principal.permissions()));
            } catch (AuthorizationException denied) {
                forbidden(response);
                return;
            }
            filterChain.doFilter(request, response);
        } finally {
            PrincipalContext.clear();
        }
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"authentication required\"}");
    }

    private static void forbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"permission denied\"}");
    }
}
