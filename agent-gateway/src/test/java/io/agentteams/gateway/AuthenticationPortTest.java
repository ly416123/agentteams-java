package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthenticationPortTest {

    @Test
    void acceptedDecisionUsesFactoryWithoutShadowingTheBooleanAccessor() {
        AuthenticationPort.AuthenticationDecision decision = AuthenticationPort.AuthenticationDecision.allow();

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.rejectionReason()).isEmpty();
    }
}
