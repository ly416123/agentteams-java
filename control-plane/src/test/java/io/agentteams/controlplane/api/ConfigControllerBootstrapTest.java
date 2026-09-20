package io.agentteams.controlplane.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.config.ConfigBindingRecord;
import io.agentteams.controlplane.config.ConfigDeploymentService;
import io.agentteams.controlplane.config.ConfigFileRecord;
import io.agentteams.controlplane.config.ConfigSnapshot;
import io.agentteams.controlplane.config.ConfigSnapshotRepository;
import io.agentteams.controlplane.config.ConfigSnapshotService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ConfigControllerBootstrapTest {
    @Test
    void returnsEmptyArrayWhenAgentHasNoCurrentBindings() throws Exception {
        ConfigDeploymentService deployments = mock(ConfigDeploymentService.class);
        UUID agentId = UUID.randomUUID();
        when(deployments.findCurrentForAgent(agentId)).thenReturn(List.of());
        MockMvc mvc = mvc(deployments);

        mvc.perform(get("/api/v1/config/agents/{agentId}/current", agentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    void returnsSnapshotIntegrityMetadataAndCompletedFiles() throws Exception {
        ConfigDeploymentService deployments = mock(ConfigDeploymentService.class);
        UUID agentId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        ConfigSnapshot snapshot = new ConfigSnapshot(snapshotId, "agent-spec:a", 7,
                "{\"model\":\"qwen\"}", "manifest-sha", "actor", Instant.parse("2026-09-20T00:00:00Z"));
        ConfigBindingRecord binding = new ConfigBindingRecord(bindingId, snapshot.subject(), agentId, snapshotId,
                snapshot.createdAt());
        ConfigFileRecord file = new ConfigFileRecord(UUID.randomUUID(), snapshotId, "models/provider.json",
                "configs/provider", "file-sha", 12, "application/json");
        when(deployments.findCurrentForAgent(agentId)).thenReturn(List.of(
                new ConfigDeploymentService.CurrentConfigBinding(binding, snapshot, List.of(file))));

        mvc(deployments).perform(get("/api/v1/config/agents/{agentId}/current", agentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bindingId").value(bindingId.toString()))
                .andExpect(jsonPath("$[0].agentId").value(agentId.toString()))
                .andExpect(jsonPath("$[0].snapshotId").value(snapshotId.toString()))
                .andExpect(jsonPath("$[0].configVersion").value(7))
                .andExpect(jsonPath("$[0].manifestJson").value(snapshot.manifestJson()))
                .andExpect(jsonPath("$[0].manifestSha256").value("manifest-sha"))
                .andExpect(jsonPath("$[0].sizeBytes").value(snapshot.manifestJson().getBytes().length))
                .andExpect(jsonPath("$[0].files[0].uri")
                        .value("urn:agentteams:config-file:" + snapshotId + ":models/provider.json"))
                .andExpect(jsonPath("$[0].files[0].sha256").value("file-sha"));
    }

    private static MockMvc mvc(ConfigDeploymentService deployments) {
        return MockMvcBuilders.standaloneSetup(new ConfigController(mock(ConfigSnapshotService.class),
                mock(ConfigSnapshotRepository.class), deployments)).build();
    }
}
