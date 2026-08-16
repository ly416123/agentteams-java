package io.agentteams.gateway;

import io.agentteams.contracts.v1.AgentHello;

/** Authentication seam for mTLS, signed tokens, or another transport credential. */
public interface AuthenticationPort {

    AuthenticationDecision authenticate(AgentConnection connection, AgentHello hello);

    record AuthenticationDecision(boolean accepted, String rejectionReason) {
        public AuthenticationDecision {
            if (!accepted && (rejectionReason == null || rejectionReason.isBlank())) {
                throw new IllegalArgumentException("rejectionReason is required for rejected authentication");
            }
        }

        public static AuthenticationDecision allow() {
            return new AuthenticationDecision(true, "");
        }

        public static AuthenticationDecision rejected(String reason) {
            return new AuthenticationDecision(false, reason);
        }
    }
}
