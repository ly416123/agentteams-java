package io.agentteams.controlplane.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Optional HTTP adapter for the Skill approval callback boundary.
 *
 * <p>The request contains only immutable identifiers and bounded scan metadata. Manifest JSON,
 * package bytes, scanner detail, and callback response bodies never cross this boundary. Any
 * transport, encoding, or response-contract failure remains {@link
 * SkillScanApprovalPort.ApprovalStatus#PENDING}.</p>
 */
public final class HttpSkillApprovalCallbackClient implements SkillScanApprovalPort {
    private static final Pattern SHA256_DIGEST = Pattern.compile("^sha256:[0-9a-fA-F]{64}$");

    private final HttpClient httpClient;
    private final URI endpoint;
    private final Duration requestTimeout;
    private final int maxResponseBytes;
    private final ObjectMapper objectMapper;

    public HttpSkillApprovalCallbackClient(HttpClient httpClient, URI endpoint, Duration requestTimeout,
            int maxResponseBytes) {
        this(httpClient, endpoint, requestTimeout, maxResponseBytes, new ObjectMapper());
    }

    HttpSkillApprovalCallbackClient(HttpClient httpClient, URI endpoint, Duration requestTimeout,
            int maxResponseBytes, ObjectMapper objectMapper) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.endpoint = validateEndpoint(endpoint);
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        if (maxResponseBytes < 1) throw new IllegalArgumentException("maxResponseBytes must be positive");
        this.maxResponseBytes = maxResponseBytes;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        if (httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("approval callback HTTP client must disable redirects");
        }
    }

    @Override
    public SkillScanApprovalPort.ApprovalStatus onReviewRequired(
            SkillScanApprovalPort.ApprovalRequest request) {
        if (request == null || !SHA256_DIGEST.matcher(request.digest()).matches()) {
            return SkillScanApprovalPort.ApprovalStatus.PENDING;
        }
        String classification = safeClassification(request.classification());
        if (classification == null) return SkillScanApprovalPort.ApprovalStatus.PENDING;

        final String body;
        try {
            var payload = objectMapper.createObjectNode();
            payload.put("skillId", request.skillId().toString());
            payload.put("versionId", request.versionId().toString());
            payload.put("classification", classification);
            payload.put("digest", request.digest());
            body = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException | RuntimeException error) {
            return SkillScanApprovalPort.ApprovalStatus.PENDING;
        }

        HttpRequest httpRequest;
        try {
            httpRequest = HttpRequest.newBuilder(endpoint)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException error) {
            return SkillScanApprovalPort.ApprovalStatus.PENDING;
        }

        final HttpResponse<byte[]> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException error) {
            return SkillScanApprovalPort.ApprovalStatus.PENDING;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return SkillScanApprovalPort.ApprovalStatus.PENDING;
        } catch (RuntimeException error) {
            return SkillScanApprovalPort.ApprovalStatus.PENDING;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300
                || response.body() == null || response.body().length == 0
                || response.body().length > maxResponseBytes) {
            return SkillScanApprovalPort.ApprovalStatus.PENDING;
        }
        return parseStatus(response.body());
    }

    private SkillScanApprovalPort.ApprovalStatus parseStatus(byte[] responseBytes) {
        try {
            JsonNode root = objectMapper.readTree(responseBytes);
            if (root == null || !root.isObject() || !root.has("status")
                    || !root.get("status").isTextual()) {
                return SkillScanApprovalPort.ApprovalStatus.PENDING;
            }
            return SkillScanApprovalPort.ApprovalStatus.valueOf(
                    root.get("status").asText().trim().toUpperCase(Locale.ROOT));
        } catch (IOException | RuntimeException error) {
            return SkillScanApprovalPort.ApprovalStatus.PENDING;
        }
    }

    private static String safeClassification(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return normalized.isBlank() ? null
                : normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    private static URI validateEndpoint(URI value) {
        Objects.requireNonNull(value, "endpoint");
        if (!value.isAbsolute() || value.getHost() == null || value.getUserInfo() != null
                || value.getFragment() != null
                || (!("http".equalsIgnoreCase(value.getScheme())
                        || "https".equalsIgnoreCase(value.getScheme())))) {
            throw new IllegalArgumentException(
                    "endpoint must be an absolute http(s) URI without credentials or fragment");
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
