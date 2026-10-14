package io.agentteams.controlplane.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;

/** Optional HTTP authentication boundary; JWT/OIDC verification is supplied by IdentityTokenValidator. */
public final class ApiAuthenticationFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL_ATTRIBUTE = ApiAuthenticationFilter.class.getName() + ".principal";

    private final IdentityTokenValidator validator;
    private final ProjectScopeResolver projectScopes;
    private final String taskMcpToken;

    public ApiAuthenticationFilter(IdentityTokenValidator validator) {
        this(validator, null, "");
    }

    public ApiAuthenticationFilter(IdentityTokenValidator validator, ProjectScopeResolver projectScopes) {
        this(validator, projectScopes, "");
    }

    /**
     * Creates the API boundary with an optional narrowly-scoped token for the task MCP service.
     * The token is deliberately not treated as a general API credential: it can only pass the
     * subtask routes and OUTPUT upload route used by the internal task MCP server.
     */
    public ApiAuthenticationFilter(IdentityTokenValidator validator, ProjectScopeResolver projectScopes,
            String taskMcpToken) {
        this.validator = java.util.Objects.requireNonNull(validator, "validator");
        this.projectScopes = projectScopes;
        this.taskMcpToken = taskMcpToken == null ? "" : taskMcpToken.trim();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (isTaskMcpRequest(request, header)) {
            filterChain.doFilter(request, response);
            return;
        }
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
        try {
            String requestedProject = request.getParameter("projectId");
            if (projectScopes != null && ((requestedProject != null && !requestedProject.isBlank())
                    || requiresCanonicalProjectScope(request.getRequestURI()))) {
                principal = projectScopes.canonicalize(principal, requestedProject);
            }
        } catch (AuthorizationException denied) {
            forbidden(response);
            return;
        }
        final Principal authenticatedPrincipal = principal;
        request.setAttribute(PRINCIPAL_ATTRIBUTE, authenticatedPrincipal);
        PrincipalContext.set(authenticatedPrincipal);
        try {
            try {
                ApiAuthorizationPolicy.requiredPermission(request)
                        .ifPresent(permission -> new AuthorizationService().require(
                                authenticatedPrincipal.subject(), permission, authenticatedPrincipal.permissions()));
            } catch (AuthorizationException denied) {
                forbidden(response);
                return;
            }
            filterChain.doFilter(request, response);
        } finally {
            PrincipalContext.clear();
        }
    }

    private static boolean requiresCanonicalProjectScope(String path) {
        if (path == null) return false;
        if (path.startsWith("/api/v1/config/")) return true;
        String[] segments = path.split("/", -1);
        return segments.length == 7
                && "api".equals(segments[1]) && "v1".equals(segments[2])
                && "agent-specs".equals(segments[3]) && isUuid(segments[4])
                && "deployments".equals(segments[5]) && isUuid(segments[6]);
    }

    private boolean isTaskMcpRequest(HttpServletRequest request, String header) {
        if (taskMcpToken.isBlank() || !isTaskMcpPath(request)
                || header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        byte[] expected = taskMcpToken.getBytes(StandardCharsets.UTF_8);
        byte[] provided = header.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, provided);
    }

    private static boolean isTaskMcpPath(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        return isTaskSubtaskPath(path)
                || "POST".equals(method) && isTaskSourceEvidencePath(path)
                || "POST".equals(method) && isTaskOutputUploadPath(path);
    }

    private static boolean isTaskSourceEvidencePath(String path) {
        if (path == null) return false;
        String[] segments = path.split("/", -1);
        return segments.length == 6
                && "api".equals(segments[1]) && "v1".equals(segments[2])
                && "tasks".equals(segments[3]) && isUuid(segments[4])
                && "source-evidence".equals(segments[5]);
    }

    private static boolean isTaskOutputUploadPath(String path) {
        if (path == null) {
            return false;
        }
        String[] segments = path.split("/", -1);
        return segments.length == 6
                && "api".equals(segments[1]) && "v1".equals(segments[2])
                && "tasks".equals(segments[3]) && isUuid(segments[4])
                && "files".equals(segments[5]);
    }

    private static boolean isTaskSubtaskPath(String path) {
        if (path == null) {
            return false;
        }
        String[] segments = path.split("/", -1);
        if (segments.length != 6 && segments.length != 8
                || !"api".equals(segments[1]) || !"v1".equals(segments[2])
                || !"tasks".equals(segments[3]) || !"subtasks".equals(segments[5])) {
            return false;
        }
        if (!isUuid(segments[4])) {
            return false;
        }
        return segments.length == 6
                || "status".equals(segments[7]) && isUuid(segments[6]);
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
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
