package io.agentteams.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.contracts.v1.ConfigChanged;
import io.agentteams.contracts.v1.ConfigFile;
import io.agentteams.contracts.v1.EventMetadata;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Fetches the current desired configuration bindings for a Worker at startup. */
final class ConfigBootstrapFetcher {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final URI controlPlaneBase;
    private final HttpClient client;
    private final Duration timeout;
    private final long maxResponseBytes;
    private final long maxFileBytes;

    ConfigBootstrapFetcher(URI controlPlaneBase, Duration timeout, long maxResponseBytes, long maxFileBytes) {
        this(controlPlaneBase, timeout, maxResponseBytes, maxFileBytes,
                HttpClient.newBuilder().connectTimeout(Objects.requireNonNull(timeout, "timeout")).build());
    }

    ConfigBootstrapFetcher(URI controlPlaneBase, Duration timeout, long maxResponseBytes, long maxFileBytes,
            HttpClient client) {
        this.controlPlaneBase = Objects.requireNonNull(controlPlaneBase, "controlPlaneBase");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        if (maxResponseBytes <= 0) throw new IllegalArgumentException("maxResponseBytes must be positive");
        if (maxFileBytes <= 0) throw new IllegalArgumentException("maxFileBytes must be positive");
        this.maxResponseBytes = maxResponseBytes;
        this.maxFileBytes = maxFileBytes;
        this.client = Objects.requireNonNull(client, "client");
    }

    List<ConfigChanged> fetch(String agentId) {
        String id = requireText(agentId, "agentId");
        URI uri = URI.create(controlPlaneBase.toString().replaceAll("/+$", "")
                + "/api/v1/config/agents/" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "/current");
        try {
            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(uri)
                    .timeout(timeout).header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try (InputStream body = response.body()) {
                    throw new IllegalStateException("configuration bootstrap HTTP " + response.statusCode()
                            + ": " + readText(body, Math.min(maxResponseBytes, 4096)));
                }
            }
            byte[] body;
            try (InputStream input = response.body()) {
                body = readBytes(input, maxResponseBytes);
            }
            return parse(body, id);
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("configuration bootstrap download failed", error);
        }
    }

    private List<ConfigChanged> parse(byte[] body, String agentId) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || !root.isArray()) {
                throw new IllegalArgumentException("configuration bootstrap response must be an array");
            }
            List<ConfigChanged> result = new ArrayList<>();
            for (JsonNode item : root) result.add(parseBinding(item, agentId));
            return List.copyOf(result);
        } catch (IOException error) {
            throw new IllegalArgumentException("configuration bootstrap response must be valid JSON", error);
        }
    }

    private ConfigChanged parseBinding(JsonNode item, String agentId) {
        if (item == null || !item.isObject()) throw new IllegalArgumentException("configuration binding must be an object");
        if (!agentId.equals(required(item, "agentId"))) throw new IllegalArgumentException("config agent_id does not match Worker");
        String bindingId = uuid(item, "bindingId");
        String snapshotId = uuid(item, "snapshotId");
        long version = positive(item, "configVersion");
        String manifestUri = required(item, "manifestUri");
        String manifestSha = sha256(item, "manifestSha256");
        long sizeBytes = nonNegative(item, "sizeBytes");
        String manifestJson = optional(item, "manifestJson");
        byte[] manifestBytes = manifestJson.getBytes(StandardCharsets.UTF_8);
        if (!manifestJson.isEmpty()) {
            if (manifestBytes.length != sizeBytes) throw new IllegalArgumentException("configuration manifest size mismatch");
            if (!manifestSha.equalsIgnoreCase(sha256(manifestBytes))) {
                throw new IllegalArgumentException("configuration manifest checksum mismatch");
            }
        }
        ConfigChanged.Builder changed = ConfigChanged.newBuilder()
                .setMetadata(EventMetadata.newBuilder().setAgentId(agentId).setSequence(0).build())
                .setConfigVersion(version).setManifestUri(manifestUri).setManifestSha256(manifestSha)
                .setSizeBytes(sizeBytes).setBindingId(bindingId).setSnapshotId(snapshotId)
                .setManifestJson(manifestJson).setRollback(false);
        JsonNode files = item.path("files");
        if (!files.isMissingNode() && !files.isArray()) throw new IllegalArgumentException("configuration files must be an array");
        if (files.isArray()) for (JsonNode file : files) changed.addFiles(parseFile(file, snapshotId));
        return changed.build();
    }

    private ConfigFile parseFile(JsonNode item, String snapshotId) {
        if (item == null || !item.isObject()) throw new IllegalArgumentException("configuration file must be an object");
        String path = required(item, "path");
        String uri = required(item, "uri");
        String sha = sha256(item, "sha256");
        long size = nonNegative(item, "sizeBytes");
        if (size > maxFileBytes) throw new IllegalArgumentException("configuration file exceeds the configured size limit");
        String contentType = required(item, "contentType");
        String expectedPrefix = "urn:agentteams:config-file:" + snapshotId + ":";
        if (uri.startsWith("urn:agentteams:config-file:") && !uri.startsWith(expectedPrefix)) {
            throw new IllegalArgumentException("configuration file URI snapshot does not match");
        }
        return ConfigFile.newBuilder().setPath(path).setUri(uri).setSha256(sha).setSizeBytes(size)
                .setContentType(contentType).build();
    }

    private static String required(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("configuration bootstrap field " + name + " is required");
        }
        return value.asText();
    }

    private static String optional(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? "" : value.isTextual() ? value.asText() : value.toString();
    }

    private static String uuid(JsonNode node, String name) {
        String value = required(node, name);
        try { return UUID.fromString(value).toString(); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("configuration " + name + " must be a UUID", error); }
    }

    private static long positive(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.canConvertToLong() || value.asLong() <= 0) {
            throw new IllegalArgumentException("configuration " + name + " must be positive");
        }
        return value.asLong();
    }

    private static long nonNegative(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.canConvertToLong() || value.asLong() < 0) {
            throw new IllegalArgumentException("configuration " + name + " must not be negative");
        }
        return value.asLong();
    }

    private static String sha256(JsonNode node, String name) {
        String value = required(node, name);
        if (!value.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("configuration " + name + " must be SHA-256");
        return value;
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 is unavailable", error); }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    private static byte[] readBytes(InputStream input, long maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) throw new IllegalArgumentException("configuration bootstrap response exceeds the configured size limit");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String readText(InputStream input, long maxBytes) throws IOException {
        return new String(readBytes(input, maxBytes), StandardCharsets.UTF_8);
    }
}
