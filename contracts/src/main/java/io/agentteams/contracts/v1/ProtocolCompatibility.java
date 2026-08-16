package io.agentteams.contracts.v1;

import java.util.Objects;

/** Defines the protocol version negotiation rule for the AgentChannel contract. */
public final class ProtocolCompatibility {

    private ProtocolCompatibility() {
    }

    /**
     * Returns whether a peer can speak to a local endpoint.
     *
     * <p>Compatibility requires the same major version and a peer minor version
     * that is less than or equal to the local minor version.</p>
     */
    public static boolean isCompatible(ProtocolVersion local, ProtocolVersion peer) {
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(peer, "peer");
        return local.getMajor() == peer.getMajor()
                && peer.getMinor() <= local.getMinor();
    }
}
