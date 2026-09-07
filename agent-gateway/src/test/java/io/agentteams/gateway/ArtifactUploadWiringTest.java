package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The artifact bridge is enabled through {@code agentteams.gateway.artifact.*}.
 * The property name intentionally avoids hyphens so that Kubernetes environment
 * variables (AGENTTEAMS_GATEWAY_ARTIFACT_ENABLED) can bind to it; this test
 * locks the conditional wiring to that exact name.
 */
class ArtifactUploadWiringTest {

    @Test
    void keepsArtifactBridgeOutOfTheContextWhenDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(AgentGatewayGrpcConfiguration.class)
                .withPropertyValues("agentteams.gateway.grpc.port=0")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ControlPlaneArtifactUploadClient.class);
                    assertThat(context).doesNotHaveBean(ArtifactUploadHandler.class);
                });
    }

    @Test
    void registersArtifactBridgeBeansWhenEnabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(AgentGatewayGrpcConfiguration.class)
                .withPropertyValues("agentteams.gateway.grpc.port=0",
                        "agentteams.gateway.artifact.enabled=true",
                        "agentteams.gateway.artifact.control-plane-url=http://control-plane:8080",
                        "agentteams.gateway.artifact.internal-token=kind-quota-internal")
                .run(context -> {
                    assertThat(context).hasSingleBean(ControlPlaneArtifactUploadClient.class);
                    assertThat(context).hasSingleBean(ArtifactUploadHandler.class);
                    assertThat(context).hasSingleBean(AgentGatewayGrpcServer.class);
                });
    }
}
