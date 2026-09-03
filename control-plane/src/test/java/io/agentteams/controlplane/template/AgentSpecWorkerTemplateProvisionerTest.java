package io.agentteams.controlplane.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.agentspec.AgentSpecRecord;
import io.agentteams.controlplane.agentspec.AgentSpecService;
import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.service.AgentService;
import io.agentteams.controlplane.worker.WorkerCrdProvisioner;
import io.agentteams.domain.agent.WorkerType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentSpecWorkerTemplateProvisionerTest {

    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void persistsWorkerScopeMetadataForSubsequentWorkerDetailReads() {
        AgentSpecService specs = mock(AgentSpecService.class);
        AgentService agents = mock(AgentService.class);
        WorkerCrdProvisioner crd = mock(WorkerCrdProvisioner.class);
        UUID workerId = UUID.randomUUID();
        UUID instanceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PrincipalContext.set(new Principal("subject-1",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"),
                java.util.Set.of("agent:write")));
        when(specs.create(eq("template-spec-" + instanceId), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AgentSpecRecord(UUID.randomUUID(), "worker", "qwenpaw", "deepseek",
                        "deepseek-chat", null, "RUNNING", "DRAFT", "{}", NOW, NOW, 1));
        when(agents.create(eq("template-worker-" + instanceId), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AgentRecord(workerId, "template-worker-" + instanceId,
                        io.agentteams.domain.agent.AgentPhase.PROVISIONING, "qwenpaw", "{}", "{}", NOW, NOW, 0));
        AgentSpecWorkerTemplateProvisioner provisioner = new AgentSpecWorkerTemplateProvisioner(
                specs, agents, crd, "worker:image", 1, "gateway", 9090, "http://control-plane",
                "http://qwenpaw", "");

        provisioner.provision(new WorkerTemplateRevision(UUID.randomUUID(), 1,
                "{\"runtime\":\"qwenpaw\",\"modelProvider\":\"deepseek\",\"modelName\":\"deepseek-chat\"}",
                "digest-1", WorkerType.LEADER, TemplateStatus.PUBLISHED, "subject-1", NOW, NOW, 1),
                instanceId, "instance-key");

        ArgumentCaptor<AgentSpecService.Input> specInput = ArgumentCaptor.forClass(AgentSpecService.Input.class);
        verify(specs).create(eq("template-spec-" + instanceId), specInput.capture());
        assertThat(specInput.getValue().workerType()).isEqualTo(WorkerType.LEADER);
        ArgumentCaptor<AgentService.AgentInput> input = ArgumentCaptor.forClass(AgentService.AgentInput.class);
        verify(agents).create(eq("template-worker-" + instanceId), input.capture());
        assertThat(input.getValue().workerType()).isEqualTo(WorkerType.LEADER);
        assertThat(input.getValue().metadataJson()).isEqualTo(
                "{\"scope\":{\"tenant\":\"tenant-a\",\"project\":\"project-a\",\"team\":\"team-a\"}}");
        ArgumentCaptor<WorkerCrdProvisioner.Request> request = ArgumentCaptor.forClass(WorkerCrdProvisioner.Request.class);
        verify(crd).provision(request.capture());
        assertThat(request.getValue().tenantId()).isEqualTo("tenant-a");
        assertThat(request.getValue().projectId()).isEqualTo("project-a");
        assertThat(request.getValue().team()).isEqualTo("team-a");
    }
}
