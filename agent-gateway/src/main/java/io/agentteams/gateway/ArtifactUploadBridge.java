package io.agentteams.gateway;

import io.agentteams.application.api.ArtifactUploadHttp;

/** Control Plane artifact upload bridge used by the gRPC artifact service. */
public interface ArtifactUploadBridge {

    ArtifactUploadHttp.PrepareResponse prepare(ArtifactUploadHttp.PrepareRequest request);

    ArtifactUploadHttp.CompleteResponse complete(ArtifactUploadHttp.CompleteRequest request);
}
