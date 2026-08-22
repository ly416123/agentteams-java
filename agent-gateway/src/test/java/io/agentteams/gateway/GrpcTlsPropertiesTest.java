package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GrpcTlsPropertiesTest {
    @Test
    void isDisabledByDefault() {
        GrpcTlsProperties properties = new GrpcTlsProperties();

        assertThat(properties.isEnabled()).isFalse();
    }

    @Test
    void requiresServerCertificatePrivateKeyAndTrustCaWhenEnabled() {
        GrpcTlsProperties properties = new GrpcTlsProperties();
        properties.setEnabled(true);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("certificate-chain");
    }
}
