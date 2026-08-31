package io.agentteams.controlplane.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.service.SchedulerLeaseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ArtifactRetentionCleanupJobTest {
    @Test
    void doesNotRunCleanupWhenAnotherReplicaOwnsTheLease() {
        ArtifactRetentionService retention = mock(ArtifactRetentionService.class);
        SchedulerLeaseService lease = mock(SchedulerLeaseService.class);
        when(lease.run(any(), any(), any(), any(), any(Supplier.class)))
                .thenReturn(new SchedulerLeaseService.Result<>(false, null));

        ArtifactRetentionCleanupJob job = new ArtifactRetentionCleanupJob(retention, lease,
                Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC), "pod-a", Duration.ofSeconds(30),
                new ArtifactRetentionPolicy(Duration.ofDays(30), Duration.ofDays(90), Duration.ofHours(2), false), 10);

        assertThat(job.runOnce()).isEqualTo(new ArtifactRetentionCleanupJob.RunResult(false, 0, 0, 0, 0));
    }
}
