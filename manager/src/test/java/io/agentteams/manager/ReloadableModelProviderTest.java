package io.agentteams.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReloadableModelProviderTest {
    @Test
    void reconnectsToTheProviderBuiltWithTheRotatedSecret() {
        ReloadableModelProvider provider = new ReloadableModelProvider(
                new StubProvider("old-secret", "old-response"));

        assertThat(provider.complete(new ModelProvider.ModelRequest("hello", 16)).content())
                .isEqualTo("old-response");

        long previousGeneration = provider.connectionGeneration();
        provider.reconnect(() -> new StubProvider("new-secret", "new-response"));

        assertThat(provider.connectionGeneration()).isEqualTo(previousGeneration + 1);
        assertThat(provider.complete(new ModelProvider.ModelRequest("hello", 16)).content())
                .isEqualTo("new-response");
        assertThat(provider.providerName()).isEqualTo("stub:new-secret");
    }

    @Test
    void failedReconnectKeepsTheExistingConnectionUsable() {
        ReloadableModelProvider provider = new ReloadableModelProvider(
                new StubProvider("old-secret", "old-response"));
        long previousGeneration = provider.connectionGeneration();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.reconnect(() -> {
            throw new IllegalStateException("rotated secret unavailable");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(provider.connectionGeneration()).isEqualTo(previousGeneration);
        assertThat(provider.complete(new ModelProvider.ModelRequest("hello", 16)).content())
                .isEqualTo("old-response");
    }

    @Test
    void usesTheRotatedSecretOnTheNextRealHttpModelCall() throws Exception {
        HttpClient oldClient = mock(HttpClient.class);
        HttpClient newClient = mock(HttpClient.class);
        HttpResponse<String> oldResponse = response(200, responseBody("old-model", "old-response"));
        HttpResponse<String> newResponse = response(200, responseBody("new-model", "new-response"));
        when(oldClient.<String>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(oldResponse);
        when(newClient.<String>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(newResponse);

        ReloadableModelProvider provider = new ReloadableModelProvider(openAi(oldClient, "old-secret", "old-model"));
        assertThat(provider.complete(new ModelProvider.ModelRequest("hello", 16)).content())
                .isEqualTo("old-response");

        provider.reconnect(() -> openAi(newClient, "new-secret", "new-model"));
        assertThat(provider.complete(new ModelProvider.ModelRequest("hello", 16)).content())
                .isEqualTo("new-response");

        org.mockito.ArgumentCaptor<HttpRequest> oldRequest = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.ArgumentCaptor<HttpRequest> newRequest = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(oldClient).send(oldRequest.capture(), any(HttpResponse.BodyHandler.class));
        verify(newClient).send(newRequest.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(oldRequest.getValue().headers().firstValue("Authorization"))
                .hasValue("Bearer old-secret");
        assertThat(newRequest.getValue().headers().firstValue("Authorization"))
                .hasValue("Bearer new-secret");
    }

    private static OpenAICompatibleProvider openAi(HttpClient client, String secret, String model) {
        return new OpenAICompatibleProvider(URI.create("https://llm.example.test/v1/chat/completions"), secret,
                model, client, new ObjectMapper(), Duration.ofSeconds(2));
    }

    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    private static String responseBody(String model, String content) {
        return "{\"model\":\"" + model + "\",\"choices\":[{\"message\":{\"content\":\""
                + content + "\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}";
    }

    private record StubProvider(String secret, String response) implements ModelProvider {
        @Override
        public ModelResponse complete(ModelRequest request) {
            return new ModelResponse(response, "stub-model", 1, 1);
        }

        @Override
        public String providerName() {
            return "stub:" + secret;
        }

        @Override
        public String modelName() {
            return "stub-model";
        }
    }
}
