package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SdkAuthenticationFilterTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void rejectsRequestsWithoutSdkAuthorizationHeader() throws Exception {
        SdkAuthenticationFilter filter = newFilter();
        MockHttpServletRequest request = baseRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
        assertThat(PrincipalContext.current()).isEmpty();
    }

    @Test
    void rejectsReplayedNonce() throws Exception {
        ReplayNonceStore nonceStore = new ReplayNonceStore.InMemory(TEST_CLOCK);
        SdkAuthenticationFilter filter = newFilter(nonceStore);
        MockHttpServletRequest request = signedRequest("nonce-1");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();

        filter.doFilter(request, firstResponse, new MockFilterChain());
        filter.doFilter(signedRequest("nonce-1"), secondResponse, new MockFilterChain());

        assertThat(firstResponse.getStatus()).isEqualTo(200);
        assertThat(secondResponse.getStatus()).isEqualTo(401);
        assertThat(secondResponse.getContentAsString()).contains("REPLAY_NONCE");
        assertThat(PrincipalContext.current()).isEmpty();
    }

    @Test
    void rejectsUnknownExternalUsersWithStructuredProvisioningError() throws Exception {
        SdkAuthenticationFilter filter = newFilter(new ReplayNonceStore.InMemory(),
                (integrationId, externalOrganizationId, externalUserId) -> Optional.empty());
        MockHttpServletRequest request = signedRequest("nonce-2");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("USER_NOT_PROVISIONED");
        assertThat(PrincipalContext.current()).isEmpty();
    }

    @Test
    void clearsPrincipalContextAfterRejectedRequest() throws Exception {
        SdkAuthenticationFilter filter = newFilter(new ReplayNonceStore.InMemory(),
                (integrationId, externalOrganizationId, externalUserId) -> Optional.empty());
        MockHttpServletRequest request = signedRequest("nonce-3");
        MockHttpServletResponse response = new MockHttpServletResponse();

        PrincipalContext.set(new Principal("leaked", new AuthorizationService.Scope("tenant", "project", "team"),
                java.util.Set.of("task:read")));
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(PrincipalContext.current()).isEmpty();
    }

    private static SdkAuthenticationFilter newFilter() {
        return newFilter(new ReplayNonceStore.InMemory(TEST_CLOCK));
    }

    private static SdkAuthenticationFilter newFilter(ReplayNonceStore nonceStore) {
        return newFilter(nonceStore, (integrationId, externalOrganizationId, externalUserId) -> Optional.of(new ExternalIdentity(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                ExternalIdentity.Status.ACTIVE,
                "acme-corp",
                "ding-user-001")));
    }

    private static SdkAuthenticationFilter newFilter(ReplayNonceStore nonceStore,
            SdkAuthenticationFilter.ExternalIdentityLookup externalIdentityLookup) {
        SdkAuthenticationFilter.IntegrationCredentialLookup credentialLookup = accessKeyId -> Optional.of(
                new IntegrationCredential(accessKeyId, "secret", SignatureAlgorithm.HMAC_SHA256, true,
                        Instant.parse("2099-01-01T00:00:00Z"), "integration-1", "acme-corp"));
        return new SdkAuthenticationFilter(credentialLookup, externalIdentityLookup, nonceStore, TEST_CLOCK);
    }

    private static MockHttpServletRequest baseRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        request.addHeader("X-AT-Timestamp", "1780000000");
        request.addHeader("X-AT-Nonce", "nonce-0");
        request.addHeader("X-AT-Organization-Id", "acme-corp");
        request.addHeader("X-AT-User-Id", "ding-user-001");
        request.addHeader("X-AT-Content-SHA256", "deadbeef");
        return request;
    }

    private static MockHttpServletRequest signedRequest(String nonce) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "AT-HMAC-SHA256 Credential=atk_acme_crm_prod,SignedHeaders=host");
        request.addHeader("X-AT-Timestamp", "1780000000");
        request.addHeader("X-AT-Nonce", nonce);
        request.addHeader("X-AT-Organization-Id", "acme-corp");
        request.addHeader("X-AT-User-Id", "ding-user-001");
        request.addHeader("X-AT-Content-SHA256", "deadbeef");
        request.addHeader("X-AT-Signature", signature("secret", "POST", "/api/v1/tasks", "acme-corp",
                "ding-user-001", "1780000000", nonce, "deadbeef"));
        return request;
    }

    private static String signature(String secret, String method, String path, String organizationId, String userId,
            String timestamp, String nonce, String contentSha256) {
        try {
            String canonical = method + "\n" + path + "\n" + organizationId + "\n" + userId + "\n" + timestamp + "\n"
                    + nonce + "\n" + contentSha256;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
