package io.agentteams.worker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;

/** Fetches a manifest without routing its bytes through the Agent Gateway. */
final class ConfigManifestFetcher {
    private final URI controlPlaneBase;
    private final HttpClient client;
    private final Duration timeout;
    private final long maxBytes;

    ConfigManifestFetcher(URI controlPlaneBase, Duration timeout, long maxBytes) {
        this(controlPlaneBase, timeout, maxBytes, HttpClient.newBuilder().connectTimeout(timeout).build());
    }

    ConfigManifestFetcher(URI controlPlaneBase, Duration timeout, long maxBytes, HttpClient client) {
        this.controlPlaneBase = controlPlaneBase;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        this.maxBytes = maxBytes;
        this.client = Objects.requireNonNull(client, "client");
    }

    String fetch(String manifestUri, String snapshotId, String expectedSha256, long expectedSize) {
        URI uri = resolve(manifestUri, snapshotId);
        if (expectedSize < 0 || expectedSize > maxBytes) {
            throw new IllegalArgumentException("configuration manifest exceeds the configured size limit");
        }
        try {
            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(uri)
                    .timeout(timeout).header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try (InputStream body = response.body()) {
                    throw new IllegalStateException("configuration manifest HTTP " + response.statusCode()
                            + ": " + readText(body, Math.min(maxBytes, 4096)));
                }
            }
            byte[] bytes;
            try (InputStream body = response.body()) {
                bytes = readBytes(body, maxBytes);
            }
            if (bytes.length != expectedSize) throw new IllegalArgumentException("configuration manifest size mismatch");
            String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!checksum.equalsIgnoreCase(expectedSha256)) {
                throw new IllegalArgumentException("configuration manifest checksum mismatch");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("configuration manifest download failed", error);
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private URI resolve(String manifestUri, String snapshotId) {
        if (manifestUri == null || manifestUri.isBlank()) throw new IllegalArgumentException("manifest_uri is required");
        URI uri = URI.create(manifestUri);
        if ("urn".equalsIgnoreCase(uri.getScheme())) {
            if (controlPlaneBase == null) throw new IllegalArgumentException("manifest URI requires a control-plane base URL");
            if (snapshotId == null || snapshotId.isBlank()) throw new IllegalArgumentException("snapshot_id is required");
            String base = controlPlaneBase.toString().replaceAll("/+$", "");
            return URI.create(base + "/api/v1/config/snapshots/" + snapshotId + "/manifest");
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("manifest_uri must use http, https, or the AgentTeams URN");
        }
        return uri;
    }

    private static byte[] readBytes(InputStream input, long maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) throw new IllegalArgumentException("configuration manifest exceeds the configured size limit");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String readText(InputStream input, long maxBytes) throws IOException {
        return new String(readBytes(input, maxBytes), StandardCharsets.UTF_8);
    }
}
