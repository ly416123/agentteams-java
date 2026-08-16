package io.agentteams.gateway;

import io.agentteams.contracts.v1.ProtocolCompatibility;
import io.agentteams.contracts.v1.ProtocolVersion;

/** Negotiation seam around the contract's compatibility rule. */
@FunctionalInterface
public interface ProtocolNegotiationPort {

    ProtocolVersion negotiate(ProtocolVersion local, ProtocolVersion peer);

    static ProtocolNegotiationPort compatiblePeerVersion() {
        return (local, peer) -> {
            if (!ProtocolCompatibility.isCompatible(local, peer)) {
                throw new ProtocolNegotiationException("unsupported protocol version");
            }
            return peer;
        };
    }
}
