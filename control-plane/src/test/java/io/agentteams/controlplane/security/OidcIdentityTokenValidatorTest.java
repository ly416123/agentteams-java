package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Instant;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class OidcIdentityTokenValidatorTest {
    private static final String ISSUER = "https://issuer.example.test";
    private static final String AUDIENCE = "agentteams-api";

    private RSAKey signingKey;
    private OidcSecurityProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        properties = new OidcSecurityProperties();
        properties.setEnabled(true);
        properties.setIssuerUri(ISSUER);
        properties.setJwkSetUri("https://issuer.example.test/.well-known/jwks.json");
        properties.setAudience(AUDIENCE);
        properties.setTenantClaim("tenant_id");
        properties.setProjectClaim("project_id");
        properties.setTeamClaim("team_id");
        properties.setPermissionsClaim("permissions");
    }

    @Test
    void validatesSignatureAndMapsConfiguredClaims() throws Exception {
        String token = token("alice", ISSUER, AUDIENCE,
                "tenant_id", "tenant-a", "project_id", "project-a", "team_id", "team-a",
                "permissions", List.of("task:read", "task:create"));
        IdentityTokenValidator validator = new OidcIdentityTokenValidator(decoder(), properties);

        Optional<IdentityTokenValidator.IdentityPrincipal> result = validator.validate(token);

        assertThat(result).get().satisfies(principal -> {
            assertThat(principal.subject()).isEqualTo("alice");
            assertThat(principal.scope()).isEqualTo(
                    new AuthorizationService.Scope("tenant-a", "project-a", "team-a"));
            assertThat(principal.permissions()).containsExactlyInAnyOrder("task:read", "task:create");
        });
    }

    @Test
    void rejectsWrongIssuerAudienceAndSignature() throws Exception {
        OidcIdentityTokenValidator validator = new OidcIdentityTokenValidator(decoder(), properties);

        assertThat(validator.validate(token("alice", "https://other.example.test", AUDIENCE,
                "tenant_id", "tenant-a", "project_id", "project-a", "team_id", "team-a",
                "permissions", "task:read"))).isEmpty();
        assertThat(validator.validate(token("alice", ISSUER, "other-audience",
                "tenant_id", "tenant-a", "project_id", "project-a", "team_id", "team-a",
                "permissions", "task:read"))).isEmpty();

        RSAKey otherKey = new RSAKeyGenerator(2048).keyID("other-key").generate();
        NimbusJwtDecoder otherDecoder = NimbusJwtDecoder.withPublicKey(otherKey.toRSAPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        assertThat(new OidcIdentityTokenValidator(otherDecoder, properties).validate(
                token("alice", ISSUER, AUDIENCE,
                        "tenant_id", "tenant-a", "project_id", "project-a", "team_id", "team-a",
                        "permissions", "task:read"))).isEmpty();
    }

    @Test
    void rejectsTokensWithoutCompleteResourceScope() throws Exception {
        OidcIdentityTokenValidator validator = new OidcIdentityTokenValidator(decoder(), properties);

        assertThat(validator.validate(token("alice", ISSUER, AUDIENCE,
                "tenant_id", "tenant-a", "project_id", "project-a",
                "permissions", "task:read"))).isEmpty();
    }

    @Test
    void refreshesJwksWhenTheIdentityProviderPublishesANewKey() throws Exception {
        RSAKey rotatedKey = new RSAKeyGenerator(2048).keyID("rotated-key").generate();
        AtomicReference<RSAKey> currentKey = new AtomicReference<>(signingKey);
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/jwks", exchange -> {
            byte[] body = new JWKSet(currentKey.get().toPublicJWK()).toJSONObject().toString()
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            String issuer = "http://localhost:" + server.getAddress().getPort();
            properties.setIssuerUri(issuer);
            properties.setJwkSetUri(issuer + "/jwks");
            OidcIdentityTokenValidator validator = OidcIdentityTokenValidator.fromProperties(properties);

            assertThat(validator.validate(token(signingKey, "alice", issuer, AUDIENCE,
                    "tenant_id", "tenant-a", "project_id", "project-a", "team_id", "team-a",
                    "permissions", "task:read"))).isPresent();

            currentKey.set(rotatedKey);
            assertThat(validator.validate(token(rotatedKey, "alice", issuer, AUDIENCE,
                    "tenant_id", "tenant-a", "project_id", "project-a", "team_id", "team-a",
                    "permissions", "task:read"))).isPresent();
        } finally {
            server.stop(0);
        }
    }

    private NimbusJwtDecoder decoder() throws Exception {
        return NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
    }

    private String token(String subject, String issuer, String audience, Object... claims) throws Exception {
        return token(signingKey, subject, issuer, audience, claims);
    }

    private String token(RSAKey key, String subject, String issuer, String audience, Object... claims) throws Exception {
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject)
                .audience(List.of(audience))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        for (int index = 0; index < claims.length; index += 2) {
            builder.claim((String) claims[index], claims[index + 1]);
        }
        JWKSource<SecurityContext> source = (selector, context) -> selector.select(new JWKSet(key));
        return new NimbusJwtEncoder(source)
                .encode(JwtEncoderParameters.from(builder.build()))
                .getTokenValue();
    }
}
