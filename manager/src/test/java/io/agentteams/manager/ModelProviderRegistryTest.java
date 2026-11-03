package io.agentteams.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelProviderRegistryTest {
    @Test
    void resolvesDefaultAndFallbackProviders() {
        ModelProvider chatgpt = mock(ModelProvider.class);
        ModelProvider local = mock(ModelProvider.class);
        ModelProviderRegistry registry = new ModelProviderRegistry("chatgpt-plan", "local",
                Map.of("chatgpt-plan", chatgpt, "local", local));

        assertThat(registry.defaultProvider()).isSameAs(chatgpt);
        assertThat(registry.fallbackProvider()).containsSame(local);
    }

    @Test
    void rejectsUnknownProvider() {
        ModelProvider provider = mock(ModelProvider.class);
        ModelProviderRegistry registry = new ModelProviderRegistry("default", null, Map.of("default", provider));

        assertThatThrownBy(() -> registry.resolve("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not registered");
    }
}
