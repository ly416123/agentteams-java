package io.agentteams.controlplane.security;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public interface ReplayNonceStore {
    boolean tryStore(String credentialId, String nonce, Instant expiresAt);

    final class InMemory implements ReplayNonceStore {
        private final ConcurrentHashMap<String, Instant> nonces = new ConcurrentHashMap<>();
        private final Clock clock;

        public InMemory() {
            this(Clock.systemUTC());
        }

        public InMemory(Clock clock) {
            this.clock = java.util.Objects.requireNonNull(clock, "clock");
        }

        @Override
        public boolean tryStore(String credentialId, String nonce, Instant expiresAt) {
            String key = credentialId + "\u0000" + nonce;
            Instant now = clock.instant();
            prune(now);
            return nonces.putIfAbsent(key, expiresAt) == null;
        }

        private void prune(Instant now) {
            for (var entry : nonces.entrySet()) {
                if (entry.getValue().isBefore(now)) {
                    nonces.remove(entry.getKey(), entry.getValue());
                }
            }
        }
    }
}
