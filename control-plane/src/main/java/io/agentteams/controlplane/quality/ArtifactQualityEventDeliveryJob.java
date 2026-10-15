package io.agentteams.controlplane.quality;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retries durable quality-event deliveries without affecting task or artifact outcomes. */
@Component
public final class ArtifactQualityEventDeliveryJob {
    private static final Logger LOG = Logger.getLogger(ArtifactQualityEventDeliveryJob.class.getName());
    private static final int BATCH_SIZE = 50;
    private static final Duration LEASE = Duration.ofSeconds(30);

    private final ArtifactQualityEventOutboxRepository outbox;
    private final ArtifactQualityLedgerService ledger;
    private final Clock clock;

    public ArtifactQualityEventDeliveryJob(ArtifactQualityEventOutboxRepository outbox,
            ArtifactQualityLedgerService ledger, Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Scheduled(fixedDelayString = "${agentteams.artifact-quality.outbox-poll-interval-ms:5000}")
    public void deliverDue() {
        var pending = outbox.claimDue(clock.instant(), LEASE, BATCH_SIZE);
        for (ArtifactQualityEventOutboxRepository.PendingEvent item : pending) {
            try {
                ledger.deliverQueued(item);
            } catch (RuntimeException failure) {
                InstantRetry.schedule(outbox, item, clock, failure);
                ledger.captureOutboxRetry(item.event());
                LOG.log(Level.WARNING, "Artifact quality outbox retry failed type={0}",
                        failure.getClass().getSimpleName());
            }
        }
    }

    private static final class InstantRetry {
        private InstantRetry() { }

        private static void schedule(ArtifactQualityEventOutboxRepository outbox,
                ArtifactQualityEventOutboxRepository.PendingEvent item, Clock clock, RuntimeException failure) {
            int exponent = Math.min(Math.max(item.attempts() - 1, 0), 8);
            long delaySeconds = Math.min(300L, 1L << exponent);
            try {
                outbox.retry(item.event().id(), clock.instant().plusSeconds(delaySeconds),
                        failure.getClass().getSimpleName());
            } catch (RuntimeException retryFailure) {
                // The lease expires automatically if the retry-state update is also unavailable.
                LOG.log(Level.WARNING, "Artifact quality outbox state update failed type={0}",
                        retryFailure.getClass().getSimpleName());
            }
        }
    }
}
