package io.agentteams.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.transport.ProxyConfig;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkerRuntimeFactoryTest {
    @Test
    void productionClasspathProvidesDashScopeModelResolution() {
        assertThat(ModelRegistry.canResolve("dashscope:qwen-plus")).isTrue();
    }

    @Test
    void productionClasspathProvidesDeepSeekModelResolution() {
        assertThat(ModelRegistry.canResolve("deepseek:deepseek-chat")).isTrue();
    }

    @Test
    void registersChatGptPlanProviderWithoutChangingExistingProviders() {
        new WorkerRuntimeFactory();

        assertThat(ModelRegistry.canResolve("chatgpt-plan:gpt-test")).isTrue();
        assertThat(ModelRegistry.canResolve("chatgpt-plan:bad:model")).isFalse();
        assertThat(ModelRegistry.canResolve("deepseek:deepseek-chat")).isTrue();
    }

    @Test
    void appliesOptionalProviderNeutralBaseUrlToModelCreationContext() {
        ModelCreationContext context = WorkerRuntimeFactory.modelCreationContext(Map.of(
                "AGENTTEAMS_MODEL_BASE_URL", "https://model-provider.example.com"));

        assertThat(context.getBaseUrl()).isEqualTo("https://model-provider.example.com");
    }

    @Test
    void appliesOptionalModelProxyToModelCreationContext() {
        ModelCreationContext context = WorkerRuntimeFactory.modelCreationContext(Map.of(
                "AGENTTEAMS_MODEL_PROXY_HOST", "agentteams-deepseek-proxy",
                "AGENTTEAMS_MODEL_PROXY_PORT", "8080"));

        ProxyConfig proxy = context.component(ProxyConfig.class);
        assertThat(proxy).isNotNull();
        assertThat(proxy.getHost()).isEqualTo("agentteams-deepseek-proxy");
        assertThat(proxy.getPort()).isEqualTo(8080);
    }

    @Test
    void explicitlyConfiguredAgentScopeFailsFastWhenHarnessOrModelIsMissing() {
        AgentTeamsWorker.WorkerConfiguration configuration = AgentTeamsWorker.WorkerConfiguration.from(Map.of(
                "AGENTTEAMS_AGENT_ID", "agent-a",
                "AGENTTEAMS_RUNTIME", "AGENTSCOPE",
                "AGENTTEAMS_MODEL", "unknown"));

        assertThatThrownBy(() -> new WorkerRuntimeFactory().validate(configuration))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AGENTSCOPE runtime requires configured Harness and model");
    }

    @Test
    void requiresAInjectedSandboxStateProbeForConfiguredAgentScopeWiring() {
        SandboxStateProbePort probe = (sandboxId, taskId, attemptId) -> null;

        WorkerRuntimeFactory factory = new WorkerRuntimeFactory(null, probe);

        org.assertj.core.api.Assertions.assertThat(factory.sandboxStateProbe()).isSameAs(probe);
    }
}
