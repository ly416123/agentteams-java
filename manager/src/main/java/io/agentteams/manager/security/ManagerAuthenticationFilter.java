package io.agentteams.manager.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public final class ManagerAuthenticationFilter extends OncePerRequestFilter {
    private final ManagerIdentityTokenValidator validator;
    private final ManagerProjectScopeResolver projectScopes;

    public ManagerAuthenticationFilter(ManagerIdentityTokenValidator validator) {
        this(validator, null);
    }

    public ManagerAuthenticationFilter(ManagerIdentityTokenValidator validator,
            ManagerProjectScopeResolver projectScopes) {
        this.validator = java.util.Objects.requireNonNull(validator, "validator");
        this.projectScopes = projectScopes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/manager/") && !path.startsWith("/api/v1/conversations");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            unauthorized(response);
            return;
        }
        String token = header.substring(7).trim();
        ManagerPrincipal principal;
        try {
            principal = validator.validate(token).orElse(null);
        } catch (RuntimeException ignored) {
            principal = null;
        }
        if (principal == null) {
            unauthorized(response);
            return;
        }
        try {
            if (projectScopes != null) {
                principal = projectScopes.canonicalize(principal, request.getParameter("projectId"));
            }
        } catch (ManagerAuthorizationException denied) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"project access denied\"}");
            return;
        }
        ManagerRequestContext.set(principal, token);
        try {
            chain.doFilter(request, response);
        } finally {
            ManagerRequestContext.clear();
        }
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"authentication required\"}");
    }
}
