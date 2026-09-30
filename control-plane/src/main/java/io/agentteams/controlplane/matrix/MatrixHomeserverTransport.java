package io.agentteams.controlplane.matrix;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Sends Matrix outbox messages using the Matrix Client-Server API. */
public final class MatrixHomeserverTransport implements MatrixDeliveryService.MatrixTransport {
    private static final Duration MAX_RETRY_AFTER = Duration.ofMinutes(5);
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private final HttpClient client;
    private final URI baseUri;
    private final String accessToken;
    private final Duration requestTimeout;
    private final int maxResponseBytes;
    private final ObjectMapper mapper;

    public MatrixHomeserverTransport(HttpClient client, URI baseUri, String accessToken,
            Duration requestTimeout, int maxResponseBytes, ObjectMapper mapper) {
        this.client = Objects.requireNonNull(client, "client");
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("HTTP client must not follow redirects");
        }
        this.baseUri = validateBaseUri(baseUri);
        if (accessToken == null || accessToken.isBlank()
                || accessToken.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("access token must be non-empty and contain no control characters");
        }
        this.accessToken = accessToken;
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("request timeout must be positive");
        }
        if (maxResponseBytes < 1 || maxResponseBytes > 1_048_576) {
            throw new IllegalArgumentException("max response size must be between 1 byte and 1 MiB");
        }
        this.requestTimeout = requestTimeout;
        this.maxResponseBytes = maxResponseBytes;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void send(MatrixOutboundMessage message) {
        byte[] payload;
        try {
            var json = mapper.createObjectNode();
            json.put("msgtype", "m.text");
            json.put("body", message.body());
            payload = mapper.writeValueAsBytes(json);
        } catch (JsonProcessingException error) {
            throw new MatrixDeliveryException("Matrix delivery payload is invalid", true, null);
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint(message))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new MatrixDeliveryException("Matrix homeserver request was interrupted", true, null);
        } catch (IOException | RuntimeException error) {
            throw new MatrixDeliveryException("Matrix homeserver is unavailable", true, null);
        }
        Duration retryAfter = parseRetryAfter(response.headers());
        try (InputStream stream = response.body()) {
            if (readBounded(stream).length > maxResponseBytes) {
                throw new MatrixDeliveryException("Matrix homeserver response exceeded the size limit", true, null);
            }
        } catch (MatrixDeliveryException error) {
            throw error;
        } catch (IOException error) {
            throw new MatrixDeliveryException("Matrix homeserver response could not be read", true, null);
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) return;
        if (status == 429) throw new MatrixDeliveryException("Matrix homeserver rate limited delivery", true, retryAfter);
        if (status >= 500 && status < 600) {
            throw new MatrixDeliveryException("Matrix homeserver temporarily failed", true, retryAfter);
        }
        if (status >= 400 && status < 500) {
            throw new MatrixDeliveryException("Matrix homeserver rejected delivery with HTTP " + status, false, null);
        }
        throw new MatrixDeliveryException("Matrix homeserver returned unexpected HTTP " + status, false, null);
    }

    private URI endpoint(MatrixOutboundMessage message) {
        String separator = baseUri.toString().endsWith("/") ? "" : "/";
        return URI.create(baseUri + separator + "_matrix/client/v3/rooms/" + encode(message.roomId())
                + "/send/m.room.message/" + message.id());
    }

    private byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxResponseBytes, 8192));
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = input.read(buffer, 0, Math.min(buffer.length, maxResponseBytes + 1 - total))) != -1) {
            output.write(buffer, 0, read);
            total += read;
            if (total > maxResponseBytes) break;
        }
        return output.toByteArray();
    }

    private static URI validateBaseUri(URI uri) {
        if (uri == null || !uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("homeserver URI must be absolute HTTP(S) without credentials");
        }
        return uri;
    }

    private static String encode(String value) {
        StringBuilder result = new StringBuilder();
        for (byte item : value.getBytes(StandardCharsets.UTF_8)) {
            int b = item & 0xff;
            if (b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b >= '0' && b <= '9'
                    || b == '-' || b == '.' || b == '_' || b == '~') result.append((char) b);
            else result.append('%').append(HEX[b >>> 4]).append(HEX[b & 15]);
        }
        return result.toString();
    }

    private static Duration parseRetryAfter(HttpHeaders headers) {
        String value = headers.firstValue("Retry-After").orElse("").trim();
        if (value.isEmpty()) return null;
        if (value.matches("[0-9]+")) {
            try {
                long seconds = Long.parseLong(value);
                return seconds <= MAX_RETRY_AFTER.toSeconds() ? Duration.ofSeconds(seconds) : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        try {
            Duration delay = Duration.between(Instant.now(), ZonedDateTime.parse(value,
                    DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            return !delay.isNegative() && delay.compareTo(MAX_RETRY_AFTER) <= 0 ? delay : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
