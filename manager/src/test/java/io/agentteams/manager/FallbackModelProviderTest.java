package io.agentteams.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class FallbackModelProviderTest {
    @Test
    void usesFallbackForRetryableFailures() {
        ModelProvider primary = mock(ModelProvider.class);
        ModelProvider fallback = mock(ModelProvider.class);
        when(primary.complete(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new ModelProviderException("timeout", ModelProviderException.Category.TIMEOUT, true,
                        408, null));
        when(fallback.complete(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ModelProvider.ModelResponse("ok", "local", 1, 1));

        assertThat(new FallbackModelProvider(primary, fallback)
                .complete(new ModelProvider.ModelRequest("hello", 10)).content()).isEqualTo("ok");
    }

    @Test
    void doesNotFallbackAuthenticationFailures() {
        ModelProvider primary = mock(ModelProvider.class);
        ModelProvider fallback = mock(ModelProvider.class);
        ModelProviderException error = new ModelProviderException("unauthorized",
                ModelProviderException.Category.AUTHENTICATION, false, 401, null);
        when(primary.complete(org.mockito.ArgumentMatchers.any())).thenThrow(error);

        assertThatThrownBy(() -> new FallbackModelProvider(primary, fallback)
                .complete(new ModelProvider.ModelRequest("hello", 10))).isSameAs(error);
    }
}
