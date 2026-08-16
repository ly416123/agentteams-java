package io.agentteams.controlplane.health;

/**
 * Adapter seam for the NATS client. A NATS integration supplies this probe;
 * until then readiness is DOWN rather than reporting a false positive.
 */
@FunctionalInterface
public interface NatsConnectionProbe {

    boolean isConnected();
}
