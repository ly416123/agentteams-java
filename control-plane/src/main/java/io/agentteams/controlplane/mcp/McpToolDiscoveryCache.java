package io.agentteams.controlplane.mcp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Small, process-local, access-ordered cache for successful MCP tools/list responses.
 *
 * <p>The cache is deliberately independent of connector and credential state. Entries are keyed
 * by server identity plus optimistic-lock version, are immutable, expire according to the
 * injected wall clock, and are bounded by an LRU capacity.</p>
 */
public final class McpToolDiscoveryCache {
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    public static final int DEFAULT_CAPACITY = 256;

    private final Clock clock;
    private final Duration ttl;
    private final int capacity;
    private final McpObservability observability;
    private final LinkedHashMap<CacheKey, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    public McpToolDiscoveryCache() {
        this(Clock.systemUTC(), DEFAULT_TTL, DEFAULT_CAPACITY);
    }

    public McpToolDiscoveryCache(Clock clock, Duration ttl, int capacity) {
        this(clock, ttl, capacity, new McpObservability());
    }

    McpToolDiscoveryCache(Clock clock, Duration ttl, int capacity, McpObservability observability) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = requirePositive(ttl, "ttl");
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.observability = Objects.requireNonNull(observability, "observability");
    }

    public synchronized List<McpToolDescriptor> get(UUID serverId) {
        return get(serverId, 0);
    }

    /** Returns tools only for the exact server version that populated the entry. */
    public synchronized List<McpToolDescriptor> get(UUID serverId, long serverVersion) {
        CacheKey key = key(serverId, serverVersion);
        Entry entry = entries.get(key);
        if (entry == null) {
            observability.cacheMiss();
            return null;
        }
        if (!clock.instant().isBefore(entry.expiresAt())) {
            entries.remove(key);
            observability.cacheExpired();
            return null;
        }
        observability.cacheHit();
        return entry.tools();
    }

    public synchronized void put(UUID serverId, List<McpToolDescriptor> tools) {
        put(serverId, 0, tools);
    }

    /** Stores tools under the server identity and its optimistic-lock version. */
    public synchronized void put(UUID serverId, long serverVersion, List<McpToolDescriptor> tools) {
        CacheKey key = key(serverId, serverVersion);
        Objects.requireNonNull(tools, "tools");
        Instant expiresAt = clock.instant().plus(ttl);
        entries.put(key, new Entry(List.copyOf(tools), expiresAt));
        while (entries.size() > capacity) {
            Iterator<Map.Entry<CacheKey, Entry>> iterator = entries.entrySet().iterator();
            iterator.next();
            iterator.remove();
            observability.cacheEvicted();
        }
    }

    public synchronized List<McpToolDescriptor> get(McpServerRecord server) {
        Objects.requireNonNull(server, "server");
        return get(server.id(), server.version());
    }

    public synchronized void put(McpServerRecord server, List<McpToolDescriptor> tools) {
        Objects.requireNonNull(server, "server");
        put(server.id(), server.version(), tools);
    }

    public synchronized void invalidate(UUID serverId) {
        UUID requiredId = Objects.requireNonNull(serverId, "serverId");
        entries.keySet().removeIf(key -> key.serverId().equals(requiredId));
        observability.cacheInvalidated();
    }

    public synchronized int size() {
        purgeExpired();
        return entries.size();
    }

    public Duration ttl() {
        return ttl;
    }

    public int capacity() {
        return capacity;
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        entries.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private record Entry(List<McpToolDescriptor> tools, Instant expiresAt) {
        private Entry {
            tools = List.copyOf(new ArrayList<>(tools));
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    private static CacheKey key(UUID serverId, long serverVersion) {
        Objects.requireNonNull(serverId, "serverId");
        if (serverVersion < 0) {
            throw new IllegalArgumentException("serverVersion must not be negative");
        }
        return new CacheKey(serverId, serverVersion);
    }

    private record CacheKey(UUID serverId, long serverVersion) {
    }
}
