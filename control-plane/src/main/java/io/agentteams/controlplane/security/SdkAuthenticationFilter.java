package io.agentteams.controlplane.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.web.filter.OncePerRequestFilter;

public final class SdkAuthenticationFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL_ATTRIBUTE = SdkAuthenticationFilter.class.getName() + ".principal";

    private final IntegrationCredentialLookup credentialLookup;
    private final ExternalIdentityLookup externalIdentityLookup;
    private final ReplayNonceStore nonceStore;
    private final Clock clock;

    public SdkAuthenticationFilter(IntegrationCredentialLookup credentialLookup,
            ExternalIdentityLookup externalIdentityLookup, ReplayNonceStore nonceStore, Clock clock) {
        this.credentialLookup = java.util.Objects.requireNonNull(credentialLookup, "credentialLookup");
        this.externalIdentityLookup = java.util.Objects.requireNonNull(externalIdentityLookup, "externalIdentityLookup");
        this.nonceStore = java.util.Objects.requireNonNull(nonceStore, "nonceStore");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            AuthResult auth = authenticate(request);
            if (auth.errorCode() != null) {
                unauthorized(response, auth.errorCode());
                return;
            }
            Principal principal = new Principal(auth.externalIdentity().externalUserId(),
                    new AuthorizationService.Scope(auth.credential().organizationId(),
                            auth.credential().integrationId(), "sdk"),
                    Set.of());
            request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
            PrincipalContext.set(principal);
            chain.doFilter(request, response);
        } finally {
            PrincipalContext.clear();
        }
    }

    private AuthResult authenticate(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("AT-HMAC-SHA256 ")) {
            return AuthResult.error("UNAUTHORIZED");
        }
        String accessKeyId = parseCredentialId(authorization).orElse(null);
        if (accessKeyId == null) {
            return AuthResult.error("UNAUTHORIZED");
        }
        IntegrationCredential credential = credentialLookup.findActiveByAccessKeyId(accessKeyId).orElse(null);
        if (credential == null || !credential.active() || credential.isExpired(clock.instant())) {
            return AuthResult.error("UNAUTHORIZED");
        }
        String timestamp = header(request, "X-AT-Timestamp");
        String nonce = header(request, "X-AT-Nonce");
        String organizationId = header(request, "X-AT-Organization-Id");
        String userId = header(request, "X-AT-User-Id");
        String contentSha256 = header(request, "X-AT-Content-SHA256");
        String signature = header(request, "X-AT-Signature");
        if (timestamp == null || nonce == null || organizationId == null || userId == null || contentSha256 == null
                || signature == null) {
            return AuthResult.error("UNAUTHORIZED");
        }
        if (!credential.organizationId().equals(organizationId)) {
            return AuthResult.error("UNAUTHORIZED");
        }
        if (!nonceStore.tryStore(accessKeyId, nonce, clock.instant().plus(Duration.ofMinutes(5)))) {
            return AuthResult.error("REPLAY_NONCE");
        }
        ExternalIdentity identity = externalIdentityLookup.findByIntegrationIdAndExternalOrganizationIdAndExternalUserId(
                credential.integrationId(), organizationId, userId).orElse(null);
        if (identity == null) {
            return AuthResult.error("USER_NOT_PROVISIONED");
        }
        if (identity.status() != ExternalIdentity.Status.ACTIVE) {
            return AuthResult.error("UNAUTHORIZED");
        }
        String expected = sign(credential.accessKeySecret(), canonical(request, organizationId, userId, timestamp, nonce,
                contentSha256));
        if (!expected.equals(signature)) {
            return AuthResult.error("UNAUTHORIZED");
        }
        return AuthResult.ok(credential, identity);
    }

    private static String canonical(HttpServletRequest request, String organizationId, String userId, String timestamp,
            String nonce, String contentSha256) {
        return request.getMethod() + "\n" + request.getRequestURI() + "\n" + organizationId + "\n" + userId + "\n"
                + timestamp + "\n" + nonce + "\n" + contentSha256;
    }

    private static String sign(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("signature calculation failed", e);
        }
    }

    private static Optional<String> parseCredentialId(String authorization) {
        int idx = authorization.indexOf("Credential=");
        if (idx < 0) return Optional.empty();
        int start = idx + "Credential=".length();
        int end = authorization.indexOf(',', start);
        return Optional.of(authorization.substring(start, end < 0 ? authorization.length() : end).trim());
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void unauthorized(HttpServletResponse response, String code) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"authentication failed\"}");
    }

    public interface IntegrationCredentialLookup {
        Optional<IntegrationCredential> findActiveByAccessKeyId(String accessKeyId);
    }

    public interface ExternalIdentityLookup {
        Optional<ExternalIdentity> findByIntegrationIdAndExternalOrganizationIdAndExternalUserId(
                String integrationId, String externalOrganizationId, String externalUserId);
    }

    private record AuthResult(String errorCode, IntegrationCredential credential, ExternalIdentity externalIdentity) {
        static AuthResult error(String code) { return new AuthResult(code, null, null); }
        static AuthResult ok(IntegrationCredential credential, ExternalIdentity externalIdentity) {
            return new AuthResult(null, credential, externalIdentity);
        }
    }
}
