package io.agentteams.controlplane.matrix;

import org.springframework.scheduling.annotation.Scheduled;

/** Polls the durable Matrix outbox only when outbound delivery has been explicitly enabled. */
public final class MatrixDeliveryScheduler {
    private final MatrixDeliveryService delivery;
    private final MatrixDeliveryService.MatrixTransport transport;
    private final int batchSize;

    MatrixDeliveryScheduler(MatrixDeliveryService delivery, MatrixDeliveryService.MatrixTransport transport,
            int batchSize) {
        this.delivery = delivery;
        this.transport = transport;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${agentteams.matrix.delivery.poll-interval:5s}")
    public void deliverDueMessages() {
        delivery.deliver(batchSize, transport);
    }
}
