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
import io.agentteams.controlplane.security.ResourceScopeRepository;
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
                .thenReturn(new AgentSpecRecord(UUID.randomUUID(), "worker", "agentscope", "deepseek",
                        "deepseek-chat", null, "RUNNING", "DRAFT", "{}", NOW, NOW, 1));
        when(agents.create(eq("template-worker-" + instanceId), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AgentRecord(workerId, "template-worker-" + instanceId,
                        io.agentteams.domain.agent.AgentPhase.PROVISIONING, "agentscope", "{}", "{}", NOW, NOW, 0));
        AgentSpecWorkerTemplateProvisioner provisioner = new AgentSpecWorkerTemplateProvisioner(
                specs, agents, crd, "worker:image", 1, "gateway", 9090, "http://control-plane", "");

        provisioner.provision(new WorkerTemplateRevision(UUID.randomUUID(), 1,
                "{\"runtime\":\"agentscope\",\"modelProvider\":\"deepseek\",\"modelName\":\"deepseek-chat\"}",
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
        assertThat(request.getValue().model()).isEqualTo("deepseek:deepseek-chat");
        assertThat(request.getValue().secretEnv()).isEmpty();
    }

    @Test
    void forwardsConfiguredCredentialSecretEnvsIntoProvisionRequests() {
        AgentSpecService specs = mock(AgentSpecService.class);
        AgentService agents = mock(AgentService.class);
        WorkerCrdProvisioner crd = mock(WorkerCrdProvisioner.class);
        UUID workerId = UUID.randomUUID();
        UUID instanceId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        PrincipalContext.set(new Principal("subject-1",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"),
                java.util.Set.of("agent:write")));
        when(specs.create(eq("template-spec-" + instanceId), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AgentSpecRecord(UUID.randomUUID(), "worker", "agentscope", "deepseek",
                        "deepseek-chat", null, "RUNNING", "DRAFT", "{}", NOW, NOW, 1));
        when(agents.create(eq("template-worker-" + instanceId), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AgentRecord(workerId, "template-worker-" + instanceId,
                        io.agentteams.domain.agent.AgentPhase.PROVISIONING, "agentscope", "{}", "{}", NOW, NOW, 0));
        AgentSpecWorkerTemplateProvisioner provisioner = new AgentSpecWorkerTemplateProvisioner(
                specs, agents, crd, "worker:image", 1, "gateway", 9090, "http://control-plane", "",
                "{\"DASHSCOPE_API_KEY\":{\"secret\":\"dashscope-credentials\",\"key\":\"apiKey\"}}");

        provisioner.provision(new WorkerTemplateRevision(UUID.randomUUID(), 1,
                "{\"runtime\":\"agentscope\",\"modelProvider\":\"deepseek\",\"modelName\":\"deepseek-chat\"}",
                "digest-1", WorkerType.LEADER, TemplateStatus.PUBLISHED, "subject-1", NOW, NOW, 1),
                instanceId, "instance-key");

        ArgumentCaptor<WorkerCrdProvisioner.Request> request = ArgumentCaptor.forClass(WorkerCrdProvisioner.Request.class);
        verify(crd).provision(request.capture());
        assertThat(request.getValue().secretEnv())
                .containsEntry("DASHSCOPE_API_KEY",
                        new WorkerCrdProvisioner.SecretEnvRef("dashscope-credentials", "apiKey"));
    }

    @Test
    void rejectsInvalidCredentialSecretEnvJsonAtConstructionTime() {
        AgentSpecService specs = mock(AgentSpecService.class);
        AgentService agents = mock(AgentService.class);
        WorkerCrdProvisioner crd = mock(WorkerCrdProvisioner.class);

        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new AgentSpecWorkerTemplateProvisioner(
                        specs, agents, crd, "worker:image", 1, "gateway", 9090, "http://control-plane",
                        "", "{not-json"))
                .withMessageContaining("credential-secret-envs");
    }

    @Test
    void injectsChatGptSessionReferenceOnlyForConfiguredEvaluationTeamUuid() {
        AgentSpecService specs = mock(AgentSpecService.class);
        AgentService agents = mock(AgentService.class);
        WorkerCrdProvisioner crd = mock(WorkerCrdProvisioner.class);
        UUID workerId = UUID.randomUUID();
        UUID instanceId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        String tenant = "tenant-a";
        String project = "project-a";
        String team = "team-a";
        ResourceScopeRepository scopes = mock(ResourceScopeRepository.class);
        when(scopes.matchesEvaluationTeamScope(org.mockito.ArgumentMatchers.any(),
                eq(UUID.fromString("429327d7-0250-600e-b51c-31d853528b8f")),
                eq(UUID.fromString("ef5916eb-14d9-4e75-9e9c-041c6b5fb447")),
                eq(UUID.fromString("b7aac93d-2e8c-4774-b04b-c80f839d4915")))).thenReturn(true);
        PrincipalContext.set(new Principal("subject-1",
                new AuthorizationService.Scope(tenant, project, team), java.util.Set.of("agent:write")));
        when(specs.create(eq("template-spec-" + instanceId), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AgentSpecRecord(UUID.randomUUID(), "worker", "agentscope", "chatgpt-plan",
                        "gpt-test", null, "RUNNING", "DRAFT", "{}", NOW, NOW, 1));
        when(agents.create(eq("template-worker-" + instanceId), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AgentRecord(workerId, "template-worker-" + instanceId,
                        io.agentteams.domain.agent.AgentPhase.PROVISIONING, "agentscope", "{}", "{}", NOW, NOW, 0));
        AgentSpecWorkerTemplateProvisioner provisioner = new AgentSpecWorkerTemplateProvisioner(
                specs, agents, crd, "worker:image", 1, "gateway", 9090, "http://control-plane", "", "",
                "429327d7-0250-600e-b51c-31d853528b8f", "ef5916eb-14d9-4e75-9e9c-041c6b5fb447",
                "b7aac93d-2e8c-4774-b04b-c80f839d4915", "chatgpt-oauth-session",
                "http://192.168.125.227:7892", true, scopes);

        provisioner.provision(new WorkerTemplateRevision(UUID.randomUUID(), 1,
                "{\"runtime\":\"agentscope\",\"modelProvider\":\"chatgpt-plan\",\"modelName\":\"gpt-test\"}",
                "digest-1", WorkerType.LEADER, TemplateStatus.PUBLISHED, "subject-1", NOW, NOW, 1),
                instanceId, "instance-key");

        ArgumentCaptor<WorkerCrdProvisioner.Request> request = ArgumentCaptor.forClass(WorkerCrdProvisioner.Request.class);
        verify(crd).provision(request.capture());
        assertThat(request.getValue().chatGptPlanTokenSecret()).isEqualTo("chatgpt-oauth-session");
        assertThat(request.getValue().environment()).containsEntry("AGENTTEAMS_CHATGPT_PROXY_URL",
                "http://192.168.125.227:7892");
        verify(scopes, org.mockito.Mockito.times(2)).matchesEvaluationTeamScope(org.mockito.ArgumentMatchers.any(),
                eq(UUID.fromString("429327d7-0250-600e-b51c-31d853528b8f")),
                eq(UUID.fromString("ef5916eb-14d9-4e75-9e9c-041c6b5fb447")),
                eq(UUID.fromString("b7aac93d-2e8c-4774-b04b-c80f839d4915")));
    }

    @Test
    void rejectsChatGptPlanBeforeCreatingWorkerForDifferentTeam() {
        AgentSpecService specs = mock(AgentSpecService.class);
        AgentService agents = mock(AgentService.class);
        WorkerCrdProvisioner crd = mock(WorkerCrdProvisioner.class);
        UUID instanceId = UUID.fromString("00000000-0000-0000-0000-000000000004");
        String tenant = "11111111-1111-4111-8111-111111111111";
        String project = "22222222-2222-4222-8222-222222222222";
        String allowedTeam = "33333333-3333-4333-8333-333333333333";
        PrincipalContext.set(new Principal("subject-1",
                new AuthorizationService.Scope(tenant, project, "44444444-4444-4444-8444-444444444444"),
                java.util.Set.of("agent:write")));
        AgentSpecWorkerTemplateProvisioner provisioner = new AgentSpecWorkerTemplateProvisioner(
                specs, agents, crd, "worker:image", 1, "gateway", 9090, "http://control-plane", "", "",
                tenant, project, allowedTeam, "chatgpt-oauth-session");

        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> provisioner.provision(new WorkerTemplateRevision(UUID.randomUUID(), 1,
                        "{\"runtime\":\"agentscope\",\"modelProvider\":\"chatgpt-plan\",\"modelName\":\"gpt-test\"}",
                        "digest-1", WorkerType.EXECUTOR, TemplateStatus.PUBLISHED, "subject-1", NOW, NOW, 1),
                        instanceId, "instance-key"))
                .withMessageContaining("ChatGPT plan credentials are restricted to the evaluation Team scope");
        org.mockito.Mockito.verifyNoInteractions(specs, agents, crd);
    }
}
