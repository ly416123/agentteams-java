package io.agentteams.controlplane.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class McpToolDiscoveryCacheTest {
    private static final Instant START = Instant.parse("2026-08-23T00:00:00Z");

    @Test
    void expiresEntriesUsingInjectedClock() {
        MutableClock clock = new MutableClock(START);
        McpToolDiscoveryCache cache = new McpToolDiscoveryCache(clock, Duration.ofSeconds(10), 4);
        UUID id = UUID.randomUUID();
        List<McpToolDescriptor> tools = List.of(new McpToolDescriptor("search", "", "{}"));

        cache.put(id, tools);
        assertThat(cache.get(id)).isEqualTo(tools);
        clock.advance(Duration.ofSeconds(10));

        assertThat(cache.get(id)).isNull();
        assertThat(cache.size()).isZero();
    }

    @Test
    void evictsLeastRecentlyUsedEntryAtCapacity() {
        MutableClock clock = new MutableClock(START);
        McpToolDiscoveryCache cache = new McpToolDiscoveryCache(clock, Duration.ofMinutes(1), 2);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        List<McpToolDescriptor> tools = List.of(new McpToolDescriptor("search", "", "{}"));

        cache.put(first, tools);
        cache.put(second, tools);
        assertThat(cache.get(first)).isEqualTo(tools);
        cache.put(third, tools);

        assertThat(cache.get(first)).isEqualTo(tools);
        assertThat(cache.get(second)).isNull();
        assertThat(cache.get(third)).isEqualTo(tools);
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void serverVersionIsPartOfTheCacheKey() {
        MutableClock clock = new MutableClock(START);
        McpToolDiscoveryCache cache = new McpToolDiscoveryCache(clock, Duration.ofMinutes(1), 4);
        UUID id = UUID.randomUUID();
        List<McpToolDescriptor> first = List.of(new McpToolDescriptor("first", "", "{}"));
        List<McpToolDescriptor> second = List.of(new McpToolDescriptor("second", "", "{}"));

        cache.put(id, 7, first);

        assertThat(cache.get(id, 7)).isEqualTo(first);
        assertThat(cache.get(id, 8)).isNull();
        cache.put(id, 8, second);
        assertThat(cache.get(id, 7)).isEqualTo(first);
        assertThat(cache.get(id, 8)).isEqualTo(second);
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
