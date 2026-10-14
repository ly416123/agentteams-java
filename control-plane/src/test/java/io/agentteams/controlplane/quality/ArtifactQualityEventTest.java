package io.agentteams.controlplane.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ArtifactQualityEventTest {
    @Test
    void permitsFailedGenerationWithoutAnArtifact() {
        ArtifactQualityEvent event = event(null, null, "GENERATE_FAILED", "INCONCLUSIVE", "provider_timeout");

        assertEquals(null, event.fileId());
        assertEquals("provider_timeout", event.categoryCode());
    }

    @Test
    void rejectsUnboundedOrCredentialBearingDescriptions() {
        assertThrows(IllegalArgumentException.class,
                () -> new ArtifactQualityEvent(UUID.randomUUID(), "org-1", "tenant-1", UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID(), 0, "QUALITY_CHECK",
                        null, null, null, null, null, null, null, "FAILED", null, null, "Bearer abc.def.ghi",
                        "LIVE", "runtime:evaluation:123e4567-e89b-12d3-a456-426614174000", "HIGH", "event-1", Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new ArtifactQualityEvent(UUID.randomUUID(), "org-1", "tenant-1", UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID(), 0, "QUALITY_CHECK",
                        null, null, null, null, null, null, null, "FAILED", null, null, "x".repeat(501),
                        "LIVE", "runtime:evaluation:123e4567-e89b-12d3-a456-426614174000", "HIGH", "event-2", Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new ArtifactQualityEvent(UUID.randomUUID(), "org-1", "tenant-1", UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID(), 0, "QUALITY_CHECK", "report.pdf", null, null, null,
                        null, null, null, "FAILED", null, null, "A check failed", "HISTORICAL",
                        "/private/eval/report.json", "HIGH", "event-3", Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new ArtifactQualityEvent(UUID.randomUUID(), "org-1", "tenant-1", UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID(), 0, "QUALITY_CHECK", "report.pdf", null, null, null,
                        null, null, null, "FAILED", null, null, "See https://private.example/report", "HISTORICAL",
                        "history:sha256:" + "a".repeat(64) + "#case:case-1:assertion:0", "HIGH", "event-4", Instant.now()));
    }

    private static ArtifactQualityEvent event(UUID fileId, String sha256, String type, String status, String category) {
        return new ArtifactQualityEvent(UUID.randomUUID(), "org-1", "tenant-1", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 0, type, "report.pdf", fileId, null, sha256,
                category, null, null, status, null, null, "PDF artifact missing", "HISTORICAL",
                "history:sha256:" + "a".repeat(64) + "#case:case-1:assertion:0", "HIGH", "key-1",
                Instant.parse("2026-10-13T00:00:00Z"));
    }
}
