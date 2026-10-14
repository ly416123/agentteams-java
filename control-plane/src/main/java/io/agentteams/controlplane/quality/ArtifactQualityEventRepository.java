package io.agentteams.controlplane.quality;

import io.agentteams.controlplane.security.ExecutionContext;

public interface ArtifactQualityEventRepository {
    boolean append(ExecutionContext context, ArtifactQualityEvent event);
    ArtifactQualityEventFilter.Page find(ExecutionContext context, ArtifactQualityEventFilter filter);
}
