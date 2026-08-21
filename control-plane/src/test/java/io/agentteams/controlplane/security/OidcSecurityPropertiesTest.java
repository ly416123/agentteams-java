package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OidcSecurityPropertiesTest {
    @Test
    void refusesApiAuthenticationWithoutOidcConfiguration() {
        OidcSecurityProperties properties = new OidcSecurityProperties();

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agentteams.security.oidc.enabled");
    }

    @Test
    void refusesEnabledOidcWithMissingIssuerJwksOrAudience() {
        OidcSecurityProperties properties = new OidcSecurityProperties();
        properties.setEnabled(true);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuer-uri");

        properties.setIssuerUri("https://issuer.example.test");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwk-set-uri");

        properties.setJwkSetUri("https://issuer.example.test/.well-known/jwks.json");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audience");
    }

    @Test
    void buildsVerifierFromExplicitJwksConfigurationWithoutClientSecret() {
        OidcSecurityProperties properties = new OidcSecurityProperties();
        properties.setEnabled(true);
        properties.setIssuerUri("https://issuer.example.test");
        properties.setJwkSetUri("https://issuer.example.test/.well-known/jwks.json");
        properties.setAudience("agentteams-api");

        assertThatCode(() -> OidcIdentityTokenValidator.fromProperties(properties))
                .doesNotThrowAnyException();
    }
}
