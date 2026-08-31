package io.agentteams.controlplane.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebhookHmacTransportTest {
    @Test
    void signsEventIdTimestampAndBodyWithoutExposingSecret() {
        WebhookSecretResolver secrets = mock(WebhookSecretResolver.class);
        when(secrets.resolve("secret-ref")).thenReturn("top-secret");
        CapturingHttpClient client = new CapturingHttpClient();
        WebhookHmacTransport transport = new WebhookHmacTransport(client, secrets);
        WebhookDelivery delivery = new WebhookDelivery(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "https://203.0.113.10/hook", "secret-ref", "{\"event\":\"task.completed\"}",
                WebhookDelivery.Status.PENDING, 0, Instant.parse("2026-08-31T12:00:00Z"),
                Instant.parse("2026-08-31T12:00:00Z"), Instant.parse("2026-08-31T12:00:00Z"), null);

        transport.send(delivery);

        assertThat(client.request.uri().toString()).isEqualTo("https://203.0.113.10/hook");
        assertThat(client.request.headers().firstValue("X-AgentTeams-Event-Id")).isPresent();
        assertThat(client.request.headers().firstValue("X-AgentTeams-Timestamp")).contains("2026-08-31T12:00:00Z");
        assertThat(client.request.headers().firstValue("X-AgentTeams-Signature").orElseThrow()).startsWith("sha256=");
        assertThat(client.request.headers().toString()).doesNotContain("top-secret");
    }

    private static final class CapturingHttpClient extends HttpClient {
        private java.net.http.HttpRequest request;

        @Override
        public java.util.Optional<java.net.ProxySelector> proxy() { return java.util.Optional.empty(); }
        @Override
        public java.util.Optional<java.net.Authenticator> authenticator() { return java.util.Optional.empty(); }
        @Override
        public java.net.http.HttpClient.Version version() { return Version.HTTP_1_1; }
        @Override
        public java.util.Optional<java.time.Duration> connectTimeout() { return java.util.Optional.empty(); }
        @Override
        public java.net.http.HttpClient.Redirect followRedirects() { return Redirect.NEVER; }
        @Override
        public java.util.Optional<java.util.concurrent.Executor> executor() { return java.util.Optional.empty(); }
        @Override
        public javax.net.ssl.SSLContext sslContext() { return null; }
        @Override
        public javax.net.ssl.SSLParameters sslParameters() { return new javax.net.ssl.SSLParameters(); }
        @Override
        public java.util.Optional<java.net.CookieHandler> cookieHandler() { return java.util.Optional.empty(); }
        @Override
        public <T> java.net.http.HttpResponse<T> send(java.net.http.HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> handler) {
            this.request = request;
            return new StubResponse<>();
        }
        @Override
        public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }
        @Override
        public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> handler,
                java.net.http.HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }
        private static final class StubResponse<T> implements java.net.http.HttpResponse<T> {
            public int statusCode() { return 204; }
            public java.net.http.HttpRequest request() { return null; }
            public java.util.Optional<java.net.http.HttpResponse<T>> previousResponse() { return java.util.Optional.empty(); }
            public java.net.http.HttpHeaders headers() { return java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true); }
            public T body() { return null; }
            public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
            public java.net.URI uri() { return java.net.URI.create("https://example.test/hook"); }
            public java.net.http.HttpClient.Version version() { return java.net.http.HttpClient.Version.HTTP_1_1; }
        }
    }
}
