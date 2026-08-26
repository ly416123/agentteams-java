package io.agentteams.manager.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public final class ManagerAuthenticationFilter extends OncePerRequestFilter {
    private final ManagerIdentityTokenValidator validator;

    public ManagerAuthenticationFilter(ManagerIdentityTokenValidator validator) {
        this.validator = java.util.Objects.requireNonNull(validator, "validator");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/manager/");
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
        ManagerRequestContext.set(principal);
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
