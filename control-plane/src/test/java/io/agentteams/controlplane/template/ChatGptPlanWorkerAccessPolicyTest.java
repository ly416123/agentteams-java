package io.agentteams.controlplane.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.domain.agent.WorkerType;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import java.util.Set;
import java.util.UUID;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;

class ChatGptPlanWorkerAccessPolicyTest {
    private static final String TENANT = "1d239ea0-a805-428e-a9cf-6d437b4753b3";
    private static final String PROJECT = "2e349fb1-b916-439f-bad0-7e548c5864c4";
    private static final String TEAM = "3f45a0c2-ca27-44a0-8be1-8f659d6975d5";

    @Test
    void grantsSessionSecretOnlyForConfiguredEvaluationScope() {
        var policy = new ChatGptPlanWorkerAccessPolicy(true, TENANT, PROJECT, TEAM, "chatgpt-session",
                "http://192.168.125.227:7892");

        assertThat(policy.secretFor("chatgpt-plan", TENANT, PROJECT, TEAM, WorkerType.LEADER))
                .isEqualTo("chatgpt-session");
        assertThat(policy.secretFor("deepseek", TENANT, PROJECT, TEAM, WorkerType.EXECUTOR)).isEmpty();
        assertThat(policy.proxyUrlFor("chatgpt-plan", TENANT, PROJECT, TEAM, WorkerType.LEADER))
                .isEqualTo("http://192.168.125.227:7892");
        assertThat(policy.proxyUrlFor("deepseek", TENANT, PROJECT, TEAM, WorkerType.EXECUTOR)).isEmpty();
    }

    @Test
    void rejectsChatGptPlanForAnyOtherTenantProjectOrTeam() {
        var policy = new ChatGptPlanWorkerAccessPolicy(true, TENANT, PROJECT, TEAM, "chatgpt-session");

        assertThatThrownBy(() -> policy.secretFor("chatgpt-plan", TENANT,
                "4a56b1d3-db38-45b1-9cf2-907a6e7a86e6", TEAM, WorkerType.EXECUTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ChatGPT plan credentials are restricted to the evaluation Team scope");
    }

    @Test
    void failsClosedWhenThePolicyIsNotConfigured() {
        var policy = new ChatGptPlanWorkerAccessPolicy(true, "", "", "", "");

        assertThatThrownBy(() -> policy.secretFor("chatgpt-plan", TENANT, PROJECT, TEAM, WorkerType.EXECUTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ChatGPT plan credentials are restricted to the evaluation Team scope");
    }

    @Test
    void requiresUuidScopeValuesWhenChatGptPlanIsSelected() {
        var policy = new ChatGptPlanWorkerAccessPolicy(true, "tenant-a", PROJECT, TEAM, "chatgpt-session");

        assertThatThrownBy(() -> policy.secretFor("chatgpt-plan", "tenant-a", PROJECT, TEAM, WorkerType.LEADER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ChatGPT plan credentials are restricted to the evaluation Team scope");
    }

    @Test
    void grantsSecretForLegacyClaimsOnlyWhenTheyResolveToTheConfiguredUuidTeam() {
        var policy = new ChatGptPlanWorkerAccessPolicy(true, TENANT, PROJECT, TEAM, "chatgpt-session");
        ResourceScopeRepository scopes = mock(ResourceScopeRepository.class);
        Principal caller = new Principal("subject-1",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of());
        when(scopes.matchesEvaluationTeamScope(caller, UUID.fromString(TENANT), UUID.fromString(PROJECT),
                UUID.fromString(TEAM))).thenReturn(true);

        assertThat(policy.secretFor("chatgpt-plan", caller, WorkerType.EXECUTOR, scopes))
                .isEqualTo("chatgpt-session");
        assertThat(policy.proxyUrlFor("chatgpt-plan", caller, WorkerType.EXECUTOR, scopes))
                .isEqualTo("");
    }

    @Test
    void rejectsLegacyClaimsWhenTheyDoNotResolveToTheConfiguredUuidTeam() {
        var policy = new ChatGptPlanWorkerAccessPolicy(true, TENANT, PROJECT, TEAM, "chatgpt-session");
        ResourceScopeRepository scopes = mock(ResourceScopeRepository.class);
        Principal caller = new Principal("subject-1",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of());

        assertThatThrownBy(() -> policy.secretFor("chatgpt-plan", caller, WorkerType.EXECUTOR, scopes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ChatGPT plan credentials are restricted to the evaluation Team scope");
    }

    @Test
    void honorsExplicitDisabledSwitchEvenWhenScopeIsConfigured() {
        var policy = new ChatGptPlanWorkerAccessPolicy(false, TENANT, PROJECT, TEAM, "chatgpt-session");

        assertThatThrownBy(() -> policy.secretFor("chatgpt-plan", TENANT, PROJECT, TEAM, WorkerType.EXECUTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ChatGPT plan credentials are restricted to the evaluation Team scope");
    }

    @Test
    void rejectsProxyUrlsContainingCredentials() {
        assertThatThrownBy(() -> new ChatGptPlanWorkerAccessPolicy(true, TENANT, PROJECT, TEAM,
                "chatgpt-session", "http://user:secret@proxy.example:7892"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ChatGPT plan proxy URL must not contain credentials");
    }
}
