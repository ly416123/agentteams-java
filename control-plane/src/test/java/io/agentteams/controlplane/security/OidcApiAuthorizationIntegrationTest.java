package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.sun.net.httpserver.HttpServer;
import io.agentteams.controlplane.api.ApiErrorHandler;
import io.agentteams.controlplane.api.TaskController;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.service.TaskService;
import io.agentteams.domain.task.TaskPhase;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Exercises network JWKS, HTTP authentication, permission and scope together. */
class OidcApiAuthorizationIntegrationTest {
    private static final String AUDIENCE = "agentteams-api";
    private static final String SCOPE_A = "{\"scope\":{\"tenant\":\"tenant-a\",\"project\":\"project-a\",\"team\":\"team-a\"}}";
    private static final String SCOPE_B = "{\"scope\":{\"tenant\":\"tenant-b\",\"project\":\"project-a\",\"team\":\"team-a\"}}";

    private RSAKey signingKey;
    private AtomicReference<RSAKey> publishedKey;
    private HttpServer jwksServer;
    private TaskService tasks;
    private MockMvc mockMvc;
    private String issuer;

    @BeforeEach
    void setUp() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("integration-key-1").generate();
        publishedKey = new AtomicReference<>(signingKey);
        jwksServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        jwksServer.createContext("/jwks", exchange -> {
            byte[] body = new JWKSet(publishedKey.get().toPublicJWK()).toJSONObject().toString()
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        jwksServer.start();
        issuer = "http://localhost:" + jwksServer.getAddress().getPort();

        OidcSecurityProperties properties = new OidcSecurityProperties();
        properties.setEnabled(true);
        properties.setIssuerUri(issuer);
        properties.setJwkSetUri(issuer + "/jwks");
        properties.setAudience(AUDIENCE);
        properties.setTenantClaim("tenant");
        properties.setProjectClaim("project");
        properties.setTeamClaim("team");
        properties.setPermissionsClaim("permissions");

        tasks = mock(TaskService.class);
        TaskRecord created = new TaskRecord(java.util.UUID.randomUUID(), "scoped task", "description",
                TaskPhase.DRAFT, 0, SCOPE_A, "alice", "rest", null, null,
                Instant.now(), Instant.now(), 0);
        when(tasks.create(any(), any())).thenReturn(created);
        mockMvc = MockMvcBuilders.standaloneSetup(new TaskController(tasks))
                .addFilters(new ApiAuthenticationFilter(OidcIdentityTokenValidator.fromProperties(properties)))
                .setControllerAdvice(new ApiErrorHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        if (jwksServer != null) jwksServer.stop(0);
    }

    @Test
    void rejectsMissingBearerToken() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Idempotency-Key", "task-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SCOPE_A))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(tasks);
    }

    @Test
    void acceptsPermissionAndMatchingResourceScope() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + token(signingKey, "task:create", SCOPE_A))
                        .header("Idempotency-Key", "task-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"scoped task\",\"spec\":" + SCOPE_A + "}"))
                .andExpect(status().isCreated());
    }

    @Test
    void rejectsMissingPermissionAndCrossScopeBeforeServiceMutation() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + token(signingKey, "task:read", SCOPE_A))
                        .header("Idempotency-Key", "task-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"scoped task\",\"spec\":" + SCOPE_A + "}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + token(signingKey, "task:create", SCOPE_A))
                        .header("Idempotency-Key", "task-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"scoped task\",\"spec\":" + SCOPE_B + "}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void refreshesNetworkJwksAfterSigningKeyRotation() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + token(signingKey, "task:create", SCOPE_A))
                        .header("Idempotency-Key", "task-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"scoped task\",\"spec\":" + SCOPE_A + "}"))
                .andExpect(status().isCreated());

        RSAKey rotated = new RSAKeyGenerator(2048).keyID("integration-key-2").generate();
        publishedKey.set(rotated);
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + token(rotated, "task:create", SCOPE_A))
                        .header("Idempotency-Key", "task-key-rotated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"scoped task\",\"spec\":" + SCOPE_A + "}"))
                .andExpect(status().isCreated());
        assertThat(publishedKey.get().getKeyID()).isEqualTo("integration-key-2");
    }

    private String token(RSAKey key, String permission, String scopeJson) throws Exception {
        var scope = new com.fasterxml.jackson.databind.ObjectMapper().readTree(scopeJson).get("scope");
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("alice")
                .audience(List.of(AUDIENCE))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("tenant", scope.get("tenant").asText())
                .claim("project", scope.get("project").asText())
                .claim("team", scope.get("team").asText())
                .claim("permissions", List.of(permission))
                .build();
        JWKSource<SecurityContext> source = (selector, context) -> selector.select(new JWKSet(key));
        return new NimbusJwtEncoder(source).encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }
}
