package io.agentteams.controlplane.webhook;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HTTPS transport with request-scoped HMAC; secret material never enters delivery records. */
public final class WebhookHmacTransport implements WebhookTransport {
    private final HttpClient client;
    private final WebhookSecretResolver secrets;
    private final Duration timeout;

    public WebhookHmacTransport(HttpClient client, WebhookSecretResolver secrets) {
        this(client, secrets, Duration.ofSeconds(10));
    }

    public WebhookHmacTransport(HttpClient client, WebhookSecretResolver secrets, Duration timeout) {
        this.client = java.util.Objects.requireNonNull(client, "client");
        this.secrets = java.util.Objects.requireNonNull(secrets, "secrets");
        this.timeout = java.util.Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public void send(WebhookDelivery delivery) {
        WebhookEndpointPolicy.requireSafe(delivery.endpoint());
        String timestamp = delivery.createdAt().toString();
        String secret = secrets.resolve(delivery.secretRef());
        if (secret == null || secret.isBlank()) throw new IllegalStateException("Webhook secret is unavailable");
        String signature = "sha256=" + sign(secret, timestamp + "\n" + delivery.eventId() + "\n" + delivery.payloadJson());
        HttpRequest request = HttpRequest.newBuilder(URI.create(delivery.endpoint()))
                .timeout(timeout).header("Content-Type", "application/json")
                .header("X-AgentTeams-Event-Id", delivery.eventId().toString())
                .header("X-AgentTeams-Timestamp", timestamp)
                .header("X-AgentTeams-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(delivery.payloadJson(), StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Webhook HTTP status " + response.statusCode());
            }
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Webhook delivery failed", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Webhook delivery interrupted", error);
        }
    }

    private static String sign(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException error) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", error);
        }
    }
}
