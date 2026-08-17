package io.agentteams.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelProviderRegistryTest {
    @Test
    void resolvesDefaultAndFallbackProviders() {
        ModelProvider deepseek = mock(ModelProvider.class);
        ModelProvider local = mock(ModelProvider.class);
        ModelProviderRegistry registry = new ModelProviderRegistry("deepseek", "local",
                Map.of("deepseek", deepseek, "local", local));

        assertThat(registry.defaultProvider()).isSameAs(deepseek);
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
