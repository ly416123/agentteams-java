package io.agentteams.controlplane.agentspec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.config.ConfigBindingRecord;
import io.agentteams.controlplane.config.ConfigDeploymentService;
import io.agentteams.controlplane.config.ConfigSnapshot;
import io.agentteams.controlplane.config.ConfigSnapshotService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentSpecDeploymentServiceTest {
    @Mock private AgentSpecService specs;
    @Mock private ConfigSnapshotService snapshots;
    @Mock private ConfigDeploymentService deployments;

    @Test
    void convertsSpecToVersionedConfigAndDeploysIt() {
        UUID specId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        AgentSpecRecord spec = new AgentSpecRecord(specId, "analyst", "qwenpaw", "deepseek", "deepseek-chat",
                "research", "RUNNING", "DRAFT", "{\"skillRefs\":[\"search-v1\"]}", now, now, 2);
        ConfigSnapshot snapshot = new ConfigSnapshot(snapshotId, "agent-spec:" + specId, 1, "{}", "checksum",
                "operator", now);
        ConfigBindingRecord binding = new ConfigBindingRecord(bindingId, snapshot.subject(), agentId, snapshotId, now);
        ConfigDeploymentService.ConfigDeployment deployment = new ConfigDeploymentService.ConfigDeployment(binding,
                snapshot, UUID.randomUUID());
        when(specs.get(specId)).thenReturn(spec);
        when(snapshots.create(any(), any(), any())).thenReturn(snapshot);
        when(deployments.deploy(agentId, snapshot.subject(), snapshot)).thenReturn(deployment);

        AgentSpecDeploymentService service = new AgentSpecDeploymentService(specs, snapshots, deployments,
                new ObjectMapper());
        AgentSpecDeploymentService.AgentSpecDeployment result = service.deploy(specId, agentId, "operator");

        assertThat(result.spec()).isSameAs(spec);
        assertThat(result.snapshot()).isSameAs(snapshot);
        verify(snapshots).create("agent-spec:" + specId,
                "{\"apiVersion\":\"agentteams.io/v1\",\"kind\":\"AgentSpec\",\"agentSpecId\":\""
                        + specId + "\",\"agentSpecVersion\":2,\"name\":\"analyst\",\"runtime\":\"qwenpaw\","
                        + "\"modelProvider\":\"deepseek\",\"modelName\":\"deepseek-chat\",\"teamRef\":\"research\","
                        + "\"scope\":{\"tenant\":\"default\",\"project\":\"default\",\"team\":\"research\"},"
                        + "\"desiredState\":\"RUNNING\",\"spec\":{\"skillRefs\":[\"search-v1\"]}}", "operator");
    }
}
