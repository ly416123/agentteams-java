package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.contracts.v1.AgentHello;
import io.agentteams.contracts.v1.EventMetadata;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionTokenAuthenticatorTest {
    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
    private static final UUID AGENT = UUID.randomUUID();

    @Test
    void acceptsOnlyActiveTokenBoundToHelloAgent() {
        String token = "secret-token";
        AgentSession session = new AgentSession(AGENT, SessionTokenAuthenticator.sha256(token), NOW.plusSeconds(60), false);
        String expectedHash = session.tokenSha256();
        SessionTokenAuthenticator authenticator = new SessionTokenAuthenticator(hash ->
                hash.equals(expectedHash) ? Optional.of(session) : Optional.empty(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        AgentConnection connection = connection("secret-token");
        AgentHello hello = hello(AGENT);
        assertThat(authenticator.authenticate(connection, hello).accepted()).isTrue();
        assertThat(authenticator.authenticate(connection("wrong"), hello).accepted()).isFalse();
    }

    @Test
    void rejectsExpiredRevokedAndMismatchedSessions() {
        String token = "secret-token";
        SessionTokenAuthenticator expired = new SessionTokenAuthenticator(hash -> Optional.of(
                new AgentSession(AGENT, SessionTokenAuthenticator.sha256(token), NOW, false)), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(expired.authenticate(connection(token), hello(AGENT)).accepted()).isFalse();
        SessionTokenAuthenticator revoked = new SessionTokenAuthenticator(hash -> Optional.of(
                new AgentSession(AGENT, SessionTokenAuthenticator.sha256(token), NOW.plusSeconds(60), true)), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(revoked.authenticate(connection(token), hello(AGENT)).accepted()).isFalse();
        assertThat(new SessionTokenAuthenticator(hash -> Optional.empty(), Clock.fixed(NOW, ZoneOffset.UTC))
                .authenticate(connection(token), hello(UUID.randomUUID())).accepted()).isFalse();
    }

    private static AgentHello hello(UUID agentId) {
        return AgentHello.newBuilder().setMetadata(EventMetadata.newBuilder().setAgentId(agentId.toString()).build()).build();
    }

    private static AgentConnection connection(String token) {
        return new AgentConnection(UUID.randomUUID(), new GatewayTestFixtures.RecordingObserver(), token, NOW);
    }
}
