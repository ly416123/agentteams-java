package io.agentteams.controlplane.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

/**
 * Credential-blind HTTP implementation of the existing sandbox client SPI.
 *
 * <p>The wire contract is intentionally small: a POST JSON request containing {@code manifestJson}
 * and, when present, {@code archiveBase64}; the response must be an object containing string
 * {@code decision} and {@code classification} fields. Provider detail is deliberately ignored.</p>
 */
public final class HttpSkillSandboxScannerClient implements SkillSandboxScannerClient {
    private final HttpClient httpClient;
    private final URI endpoint;
    private final Duration requestTimeout;
    private final int maxResponseBytes;
    private final ObjectMapper objectMapper;

    public HttpSkillSandboxScannerClient(HttpClient httpClient, URI endpoint, Duration requestTimeout,
            int maxResponseBytes) {
        this(httpClient, endpoint, requestTimeout, maxResponseBytes, new ObjectMapper());
    }

    public HttpSkillSandboxScannerClient(HttpClient httpClient, URI endpoint, Duration requestTimeout,
            int maxResponseBytes, ObjectMapper objectMapper) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.endpoint = validateEndpoint(endpoint);
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        if (maxResponseBytes < 1) throw new IllegalArgumentException("maxResponseBytes must be positive");
        this.maxResponseBytes = maxResponseBytes;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        if (httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("sandbox HTTP client must disable redirects");
        }
    }

    @Override
    public ScanResult scan(ScanRequest request) {
        Objects.requireNonNull(request, "request");
        final String body;
        try {
            body = requestBody(request);
        } catch (JsonProcessingException error) {
            throw new InvalidResultException("sandbox request could not be encoded", error);
        }

        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException error) {
            throw new TimeoutException("sandbox HTTP request timed out", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new UnavailableException("sandbox HTTP request was interrupted", error);
        } catch (IOException | RuntimeException error) {
            throw new UnavailableException("sandbox HTTP service is unavailable", error);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new UnavailableException("sandbox HTTP service returned a non-success status");
        }
        byte[] responseBytes = response.body();
        if (responseBytes == null || responseBytes.length == 0 || responseBytes.length > maxResponseBytes) {
            throw new InvalidResultException("sandbox HTTP response exceeds the result contract");
        }
        return parseResult(responseBytes);
    }

    private String requestBody(ScanRequest request) throws JsonProcessingException {
        var body = objectMapper.createObjectNode();
        body.put("manifestJson", request.manifestJson());
        if (request.archiveBytes() != null) {
            body.put("archiveBase64", Base64.getEncoder().encodeToString(request.archiveBytes()));
        }
        return objectMapper.writeValueAsString(body);
    }

    private ScanResult parseResult(byte[] responseBytes) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(responseBytes);
        } catch (IOException error) {
            throw new InvalidResultException("sandbox HTTP response is not valid JSON", error);
        }
        if (root == null || !root.isObject()
                || !root.has("decision") || !root.get("decision").isTextual()
                || !root.has("classification") || !root.get("classification").isTextual()) {
            throw new InvalidResultException("sandbox HTTP response has an invalid shape");
        }

        String classification = root.get("classification").asText();
        if (classification.isBlank() || classification.length() > 120) {
            throw new InvalidResultException("sandbox classification is invalid");
        }
        String normalizedClassification = classification.replaceAll("[^A-Za-z0-9_.:-]", "_");
        if (normalizedClassification.isBlank()) {
            throw new InvalidResultException("sandbox classification is invalid");
        }
        SkillSandboxScannerClient.Decision decision;
        try {
            decision = SkillSandboxScannerClient.Decision.valueOf(
                    root.get("decision").asText().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new InvalidResultException("sandbox decision is invalid", error);
        }
        // The optional provider detail is never copied across this boundary.
        return new ScanResult(decision,
                normalizedClassification.length() <= 120
                        ? normalizedClassification : normalizedClassification.substring(0, 120),
                null);
    }

    private static URI validateEndpoint(URI value) {
        Objects.requireNonNull(value, "endpoint");
        if (!value.isAbsolute() || value.getHost() == null || value.getUserInfo() != null
                || value.getFragment() != null
                || (!"http".equalsIgnoreCase(value.getScheme())
                        && !"https".equalsIgnoreCase(value.getScheme()))) {
            throw new IllegalArgumentException("endpoint must be an absolute http(s) URI without credentials or fragment");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
