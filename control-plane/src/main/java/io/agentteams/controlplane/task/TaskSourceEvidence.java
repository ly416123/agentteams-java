package io.agentteams.controlplane.task;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Immutable, metadata-only search source evidence for one task execution Run. */
public record TaskSourceEvidence(UUID taskId, UUID runId, List<SearchCall> calls, boolean truncated,
        int observedCallCount, int observedSourceCount) {

    public TaskSourceEvidence {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        calls = List.copyOf(Objects.requireNonNull(calls, "calls"));
        if (observedCallCount < calls.size() || observedSourceCount < 0) {
            throw new IllegalArgumentException("observed evidence counts are invalid");
        }
    }

    public record SearchCall(UUID taskId, UUID runId, UUID attemptId, String callId, String provider, boolean complete,
            List<Source> sources, Instant capturedAt) {
        public SearchCall {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(attemptId, "attemptId");
            callId = required(callId, "callId", 128);
            provider = required(provider, "provider", 100);
            sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
            Objects.requireNonNull(capturedAt, "capturedAt");
        }
    }

    public record Source(String title, String url, String sha256, String evidenceRef) {
        public Source {
            title = required(title, "title", 500);
            url = required(url, "url", 2_048);
            sha256 = required(sha256, "sha256", 64).toLowerCase(Locale.ROOT);
            evidenceRef = required(evidenceRef, "evidenceRef", 200);
            validateUrl(url);
            if (!sha256.matches("[a-f0-9]{64}") || !sha256.equals(hash(url))) {
                throw new IllegalArgumentException("source hash does not match URL");
            }
            if (!evidenceRef.startsWith("task-source:") || !evidenceRef.endsWith(":" + sha256)) {
                throw new IllegalArgumentException("source evidence reference is invalid");
            }
        }

        private static void validateUrl(String value) {
            try {
                URI uri = URI.create(value);
                String scheme = uri.getScheme();
                if (scheme == null || !(scheme.equals("https") || scheme.equals("http"))
                        || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                        || uri.getRawAuthority() == null || !uri.getRawAuthority().equals(
                                uri.getRawAuthority().toLowerCase(Locale.ROOT))
                        || !uri.normalize().toASCIIString().equals(value)) {
                    throw new IllegalArgumentException("source URL must be normalized HTTP(S) without userinfo or fragment");
                }
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("source URL must be normalized HTTP(S) without userinfo or fragment");
            }
        }

        private static String hash(String value) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(digest);
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is unavailable", impossible);
            }
        }
    }

    private static String required(String value, String name, int maxLength) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return normalized;
    }
}
