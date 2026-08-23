package io.agentteams.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.controlplane.security.SecretResolver;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ValidationOnlyModelProviderConnectionProbeTest {

    @Test
    void usesResolverStatusAndNeverFallsBackToPlaintext() {
        ValidationOnlyModelProviderConnectionProbe probe = new ValidationOnlyModelProviderConnectionProbe(
                credentialRef -> new SecretResolver.Resolution(SecretResolver.Status.INVALID_REFERENCE));

        ModelProviderConnectionProbe.ProbeResult result = probe.probe(request("sk-plain-token"));

        assertThat(result.status()).isEqualTo(ModelProviderConnectionProbe.ProbeResult.Status.REJECTED);
        assertThat(result.classification()).isEqualTo("CREDENTIAL_REFERENCE_INVALID");
        assertThat(result.networkCallAttempted()).isFalse();
        assertThat(result.checks()).extracting(ModelProviderConnectionProbe.ProbeResult.Check::status)
                .containsExactly("INVALID");
    }

    @Test
    void defaultResolverReportsValidationOnlyState() {
        ModelProviderConnectionProbe.ProbeResult result =
                new ValidationOnlyModelProviderConnectionProbe().probe(request("secret/deepseek"));

        assertThat(result.status()).isEqualTo(ModelProviderConnectionProbe.ProbeResult.Status.NOT_ATTEMPTED);
        assertThat(result.checks()).filteredOn(check -> check.name().equals("CREDENTIAL_REFERENCE"))
                .extracting(ModelProviderConnectionProbe.ProbeResult.Check::status)
                .containsExactly("VALIDATION_ONLY");
        assertThat(result.networkCallAttempted()).isFalse();
    }

    private static ModelProviderConnectionProbe.ProbeRequest request(String credentialRef) {
        return new ModelProviderConnectionProbe.ProbeRequest(UUID.randomUUID(), "openai-compatible",
                "https://api.deepseek.com/v1", credentialRef, Duration.ofSeconds(5));
    }
}
