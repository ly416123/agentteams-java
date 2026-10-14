package io.agentteams.controlplane.quality;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ArtifactQualityEventFilter(UUID projectId, UUID taskId, String artifactName, String status,
        String categoryCode, Instant from, Instant to, int page, int pageSize) {
    public ArtifactQualityEventFilter {
        if (page < 0) throw new IllegalArgumentException("page must not be negative");
        if (pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("pageSize must be between 1 and 100");
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("from must not be after to");
        if (artifactName != null && artifactName.length() > 128) throw new IllegalArgumentException("artifactName is too long");
        if (status != null && status.length() > 64) throw new IllegalArgumentException("status is too long");
        if (categoryCode != null && categoryCode.length() > 64) throw new IllegalArgumentException("categoryCode is too long");
    }

    public record Page(List<ArtifactQualityEvent> events, long total, int page, int pageSize) {
        public Page { events = List.copyOf(events); }
    }
}
