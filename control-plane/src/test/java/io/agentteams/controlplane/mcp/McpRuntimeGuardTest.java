package io.agentteams.controlplane.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class McpRuntimeGuardTest {
    private static final Instant START = Instant.parse("2026-08-23T00:00:00Z");

    @Test
    void rateLimitIsPerServerAndLeaseReleaseRestoresCapacity() {
        MutableClock clock = new MutableClock(START);
        McpRuntimeGuard guard = new McpRuntimeGuard(clock, 1, 3, Duration.ofSeconds(10));
        UUID firstServer = UUID.randomUUID();
        UUID secondServer = UUID.randomUUID();

        McpRuntimeGuard.Lease first = guard.tryAcquire(firstServer);
        assertThat(first.granted()).isTrue();
        assertThat(guard.tryAcquire(firstServer).rejection()).isEqualTo(McpRuntimeGuard.RATE_LIMITED);
        McpRuntimeGuard.Lease second = guard.tryAcquire(secondServer);
        assertThat(second.granted()).isTrue();

        first.success();
        second.success();
        assertThat(guard.inFlight(firstServer)).isZero();
        McpRuntimeGuard.Lease availableAgain = guard.tryAcquire(firstServer);
        assertThat(availableAgain.granted()).isTrue();
        availableAgain.success();
    }

    @Test
    void failuresOpenCircuitAndHalfOpenProbeCanRecover() {
        MutableClock clock = new MutableClock(START);
        McpRuntimeGuard guard = new McpRuntimeGuard(clock, 2, 2, Duration.ofSeconds(10));
        UUID serverId = UUID.randomUUID();

        guard.tryAcquire(serverId).failure();
        guard.tryAcquire(serverId).failure();
        assertThat(guard.tryAcquire(serverId).rejection()).isEqualTo(McpRuntimeGuard.CIRCUIT_OPEN);

        clock.advance(Duration.ofSeconds(10));
        McpRuntimeGuard.Lease probe = guard.tryAcquire(serverId);
        assertThat(probe.granted()).isTrue();
        probe.success();

        McpRuntimeGuard.Lease afterRecovery = guard.tryAcquire(serverId);
        assertThat(afterRecovery.granted()).isTrue();
        afterRecovery.success();
    }

    @Test
    void failedHalfOpenProbeReopensCircuitAndCloseIsIdempotent() {
        MutableClock clock = new MutableClock(START);
        McpRuntimeGuard guard = new McpRuntimeGuard(clock, 1, 1, Duration.ofSeconds(5));
        UUID serverId = UUID.randomUUID();

        guard.tryAcquire(serverId).failure();
        clock.advance(Duration.ofSeconds(5));
        McpRuntimeGuard.Lease probe = guard.tryAcquire(serverId);
        probe.close();
        probe.close();

        assertThat(guard.tryAcquire(serverId).rejection()).isEqualTo(McpRuntimeGuard.CIRCUIT_OPEN);
        assertThat(guard.inFlight(serverId)).isZero();
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
