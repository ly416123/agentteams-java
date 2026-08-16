package io.agentteams.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAICompatibleProviderTest {
    private static final URI ENDPOINT = URI.create("https://llm.example.test/v1/chat/completions");

    @Test
    void retriesTransientNetworkFailureWithBoundedExponentialBackoff() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.<String>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("connection reset"))
                .thenThrow(new HttpTimeoutException("upstream timeout"))
                .thenReturn(response(200, "{\"model\":\"chat\",\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));
        List<Duration> sleeps = new ArrayList<>();
        OpenAICompatibleProvider provider = new OpenAICompatibleProvider(ENDPOINT, "secret", "chat", client,
                new ObjectMapper(), Duration.ofSeconds(2), new RetryPolicy(3, Duration.ofMillis(10), Duration.ofMillis(15)),
                sleeps::add);

        assertThat(provider.complete(new ModelProvider.ModelRequest("hello", 32)).content()).isEqualTo("ok");
        assertThat(sleeps).containsExactly(Duration.ofMillis(10), Duration.ofMillis(15));
        verify(client, org.mockito.Mockito.times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void stopsAfterFiniteRetryBudgetAndExposesTransientClassification() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.<String>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("connection reset"));
        List<Duration> sleeps = new ArrayList<>();
        OpenAICompatibleProvider provider = new OpenAICompatibleProvider(ENDPOINT, "secret", "chat", client,
                new ObjectMapper(), Duration.ofSeconds(2), new RetryPolicy(2, Duration.ofMillis(1), Duration.ofMillis(10)),
                sleeps::add);

        assertThatThrownBy(() -> provider.complete(new ModelProvider.ModelRequest("hello", 32)))
                .isInstanceOfSatisfying(ModelProviderException.class, error -> {
                    assertThat(error.category()).isEqualTo(ModelProviderException.Category.NETWORK);
                    assertThat(error.retryable()).isTrue();
                });
        assertThat(sleeps).hasSize(2);
        verify(client, org.mockito.Mockito.times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void doesNotRetryPermanentHttpErrors() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> unauthorized = response(401, "unauthorized");
        when(client.<String>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(unauthorized);
        List<Duration> sleeps = new ArrayList<>();
        OpenAICompatibleProvider provider = new OpenAICompatibleProvider(ENDPOINT, "secret", "chat", client,
                new ObjectMapper(), Duration.ofSeconds(2), new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(10)),
                sleeps::add);

        assertThatThrownBy(() -> provider.complete(new ModelProvider.ModelRequest("hello", 32)))
                .isInstanceOfSatisfying(ModelProviderException.class, error -> {
                    assertThat(error.category()).isEqualTo(ModelProviderException.Category.AUTHENTICATION);
                    assertThat(error.retryable()).isFalse();
                    assertThat(error.statusCode()).isEqualTo(401);
                });
        assertThat(sleeps).isEmpty();
        verify(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void classifiesMalformedSuccessfulResponseAsPermanentProtocolError() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> malformed = response(200, "not-json");
        when(client.<String>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(malformed);
        OpenAICompatibleProvider provider = new OpenAICompatibleProvider(ENDPOINT, "secret", "chat", client,
                new ObjectMapper(), Duration.ofSeconds(2), new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(10)),
                duration -> { throw new AssertionError("protocol errors must not sleep"); });

        assertThatThrownBy(() -> provider.complete(new ModelProvider.ModelRequest("hello", 32)))
                .isInstanceOfSatisfying(ModelProviderException.class, error -> {
                    assertThat(error.category()).isEqualTo(ModelProviderException.Category.PROTOCOL);
                    assertThat(error.retryable()).isFalse();
                });
        verify(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void retriesRateLimitAndServerResponsesButNotOtherClientErrors() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> rateLimited = response(429, "rate limited");
        HttpResponse<String> unavailable = response(503, "unavailable");
        HttpResponse<String> successful = response(200,
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        when(client.<String>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(rateLimited).thenReturn(unavailable).thenReturn(successful);
        List<Duration> sleeps = new ArrayList<>();
        OpenAICompatibleProvider provider = new OpenAICompatibleProvider(ENDPOINT, "secret", "chat", client,
                new ObjectMapper(), Duration.ofSeconds(2), new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(10)),
                sleeps::add);

        assertThat(provider.complete(new ModelProvider.ModelRequest("hello", 32)).content()).isEqualTo("ok");
        assertThat(sleeps).hasSize(2);
    }

    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }
}
