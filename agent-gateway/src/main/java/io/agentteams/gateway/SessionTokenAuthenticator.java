package io.agentteams.gateway;

import io.agentteams.contracts.v1.AgentHello;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Authenticates the transport identity as a short-lived session token without logging the secret. */
public final class SessionTokenAuthenticator implements AgentAuthenticator {
    private final AgentSessionStore sessions;
    private final Clock clock;

    public SessionTokenAuthenticator(AgentSessionStore sessions, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AuthenticationDecision authenticate(AgentConnection connection, AgentHello hello) {
        if (connection == null || hello == null || !hello.hasMetadata()) {
            return AuthenticationDecision.rejected("session credentials are required");
        }
        String token = connection.transportIdentity();
        if (token == null || token.isBlank()) return AuthenticationDecision.rejected("session credentials are required");
        Optional<AgentSession> found = sessions.findByTokenSha256(sha256(token));
        if (found.isEmpty() || !found.get().activeAt(clock.instant())) {
            return AuthenticationDecision.rejected("session is invalid or expired");
        }
        UUID claimed;
        try {
            claimed = UUID.fromString(hello.getMetadata().getAgentId());
        } catch (IllegalArgumentException error) {
            return AuthenticationDecision.rejected("agent identity is invalid");
        }
        return found.get().agentId().equals(claimed)
                ? AuthenticationDecision.allow()
                : AuthenticationDecision.rejected("session agent does not match Hello agent");
    }

    public static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
