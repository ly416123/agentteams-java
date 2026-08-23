package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OutboundPolicyTest {

    @Test
    void roundTripsDurationAsMilliseconds() {
        OutboundPolicy policy = new OutboundPolicy(Set.of("https"), Set.of("*.example.test"),
                Duration.ofSeconds(7), Set.of("search"));

        assertThat(policy.toJson()).contains("\"maxTimeout\":7000");
        assertThat(OutboundPolicy.fromJson(policy.toJson())).isEqualTo(policy);
    }

    @Test
    void rejectsEndpointsOutsideAllowlist() {
        OutboundPolicy policy = new OutboundPolicy(Set.of("https"), Set.of("api.example.test"),
                Duration.ofSeconds(5), Set.of("search"));
        OutboundPolicyValidator validator = new OutboundPolicyValidator();

        assertThat(validator.validateEndpoint("https://api.example.test/mcp", policy))
                .isEqualTo(URI.create("https://api.example.test/mcp"));
        assertThatThrownBy(() -> validator.validateEndpoint("http://api.example.test/mcp", policy))
                .isInstanceOf(OutboundPolicyViolationException.class);
        assertThatThrownBy(() -> validator.validateEndpoint("https://other.example.test/mcp", policy))
                .isInstanceOf(OutboundPolicyViolationException.class);
    }

    @Test
    void rejectsTimeoutAndToolOutsidePolicy() {
        OutboundPolicy policy = new OutboundPolicy(Set.of("https"), Set.of("*"),
                Duration.ofSeconds(5), Set.of("search"));
        OutboundPolicyValidator validator = new OutboundPolicyValidator();

        assertThatThrownBy(() -> validator.validateTimeout(Duration.ofSeconds(6), policy))
                .isInstanceOf(OutboundPolicyViolationException.class);
        assertThatThrownBy(() -> validator.validateTool("delete", policy))
                .isInstanceOf(OutboundPolicyViolationException.class);
    }
}
