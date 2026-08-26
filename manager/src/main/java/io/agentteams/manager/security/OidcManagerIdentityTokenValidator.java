package io.agentteams.manager.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

public final class OidcManagerIdentityTokenValidator implements ManagerIdentityTokenValidator {
    private final JwtDecoder decoder;
    private final ManagerSecurityProperties properties;

    private OidcManagerIdentityTokenValidator(JwtDecoder decoder, ManagerSecurityProperties properties) {
        this.decoder = decoder;
        this.properties = properties;
    }

    public static OidcManagerIdentityTokenValidator fromProperties(ManagerSecurityProperties properties) {
        properties.validate();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>("aud",
                values -> values != null && values.contains(properties.audience()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuerUri()), audience));
        return new OidcManagerIdentityTokenValidator(decoder, properties);
    }

    @Override
    public Optional<ManagerPrincipal> validate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        try {
            Jwt jwt = decoder.decode(token);
            if (!properties.issuerUri().equals(String.valueOf(jwt.getIssuer()))
                    || !jwt.getAudience().contains(properties.audience())) return Optional.empty();
            Optional<String> subject = stringClaim(jwt, "sub");
            Optional<String> tenant = stringClaim(jwt, properties.tenantClaim());
            Optional<String> project = stringClaim(jwt, properties.projectClaim());
            Optional<String> team = stringClaim(jwt, properties.teamClaim());
            Optional<Set<String>> permissions = permissions(jwt, properties.permissionsClaim());
            if (subject.isEmpty() || tenant.isEmpty() || project.isEmpty() || team.isEmpty()
                    || permissions.isEmpty()) return Optional.empty();
            return Optional.of(new ManagerPrincipal(subject.get(), tenant.get(), project.get(), team.get(),
                    permissions.get()));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<String> stringClaim(Jwt jwt, String name) {
        Object value = jwt.getClaims().get(name);
        return value instanceof String text && !text.isBlank() ? Optional.of(text) : Optional.empty();
    }

    private static Optional<Set<String>> permissions(Jwt jwt, String name) {
        Object value = jwt.getClaims().get(name);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (value instanceof String text) {
            for (String permission : text.split("\\s+")) if (!permission.isBlank()) result.add(permission);
        } else if (value instanceof Collection<?> values) {
            for (Object permission : values) {
                if (!(permission instanceof String text) || text.isBlank()) return Optional.empty();
                result.add(text);
            }
        } else return Optional.empty();
        return result.isEmpty() ? Optional.empty() : Optional.of(Set.copyOf(result));
    }
}
