package io.agentteams.controlplane.outbox;

@FunctionalInterface
public interface JetStreamTransport {

    void publish(String subject, byte[] payload, String messageId) throws Exception;
}
