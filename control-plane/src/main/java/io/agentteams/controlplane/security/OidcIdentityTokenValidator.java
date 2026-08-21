package io.agentteams.controlplane.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** OIDC JWT verifier and claim mapper used by the HTTP authentication boundary. */
public final class OidcIdentityTokenValidator implements IdentityTokenValidator {
    private final JwtDecoder decoder;
    private final OidcSecurityProperties properties;

    public OidcIdentityTokenValidator(JwtDecoder decoder, OidcSecurityProperties properties) {
        this.decoder = java.util.Objects.requireNonNull(decoder, "decoder");
        this.properties = java.util.Objects.requireNonNull(properties, "properties");
    }

    public static OidcIdentityTokenValidator fromProperties(OidcSecurityProperties properties) {
        properties.validate();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(properties.issuerUri());
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                "aud", audiences -> audiences != null && audiences.contains(properties.audience()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerValidator, audienceValidator));
        return new OidcIdentityTokenValidator(decoder, properties);
    }

    @Override
    public Optional<IdentityPrincipal> validate(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return Optional.empty();
        }
        try {
            Jwt jwt = decoder.decode(bearerToken);
            if (jwt.getIssuer() == null || !properties.issuerUri().equals(jwt.getIssuer().toString())
                    || !jwt.getAudience().contains(properties.audience())) {
                return Optional.empty();
            }
            Optional<String> tenant = requiredStringClaim(jwt, properties.tenantClaim());
            Optional<String> project = requiredStringClaim(jwt, properties.projectClaim());
            Optional<String> team = requiredStringClaim(jwt, properties.teamClaim());
            Optional<Set<String>> permissions = permissionsClaim(jwt, properties.permissionsClaim());
            if (tenant.isEmpty() || project.isEmpty() || team.isEmpty() || permissions.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new IdentityPrincipal(jwt.getSubject(),
                    new AuthorizationService.Scope(tenant.get(), project.get(), team.get()), permissions.get()));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<String> requiredStringClaim(Jwt jwt, String claimName) {
        Object value = jwt.getClaims().get(claimName);
        return value instanceof String string && !string.isBlank() ? Optional.of(string) : Optional.empty();
    }

    private static Optional<Set<String>> permissionsClaim(Jwt jwt, String claimName) {
        Object value = jwt.getClaims().get(claimName);
        LinkedHashSet<String> permissions = new LinkedHashSet<>();
        if (value instanceof String string) {
            for (String permission : string.split("\\s+")) {
                if (!permission.isBlank()) permissions.add(permission);
            }
        } else if (value instanceof Collection<?> collection) {
            for (Object permission : collection) {
                if (!(permission instanceof String string) || string.isBlank()) return Optional.empty();
                permissions.add(string);
            }
        } else {
            return Optional.empty();
        }
        return permissions.isEmpty() ? Optional.empty() : Optional.of(Set.copyOf(permissions));
    }
}
