package io.agentteams.worker;

import io.agentteams.contracts.v1.ConfigFile;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;

/** Downloads configuration files through the Control Plane and verifies them before staging. */
final class ConfigFileFetcher {
    private static final String URN_PREFIX = "urn:agentteams:config-file:";

    private final URI controlPlaneBase;
    private final HttpClient client;
    private final Duration timeout;
    private final long maxBytes;

    ConfigFileFetcher(URI controlPlaneBase, Duration timeout, long maxBytes) {
        this(controlPlaneBase, timeout, maxBytes,
                HttpClient.newBuilder().connectTimeout(Objects.requireNonNull(timeout, "timeout")).build());
    }

    ConfigFileFetcher(URI controlPlaneBase, Duration timeout, long maxBytes, HttpClient client) {
        this.controlPlaneBase = controlPlaneBase;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        this.maxBytes = maxBytes;
        this.client = Objects.requireNonNull(client, "client");
    }

    Path fetch(ConfigFile file, Path versionDirectory) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(versionDirectory, "versionDirectory");
        Path relative = safeRelativePath(file.getPath());
        long expectedSize = file.getSizeBytes();
        if (expectedSize < 0 || expectedSize > maxBytes) {
            throw new IllegalArgumentException("configuration file exceeds the configured size limit");
        }
        Path absoluteDirectory = versionDirectory.toAbsolutePath().normalize();
        Path target = absoluteDirectory.resolve(relative).normalize();
        if (!target.startsWith(absoluteDirectory)) {
            throw new IllegalArgumentException("configuration file escapes the version directory");
        }
        Path temporary;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(absoluteDirectory, ".config-", ".part");
            try {
                download(file, temporary, expectedSize);
                move(temporary, target);
                return target;
            } catch (RuntimeException | IOException error) {
                Files.deleteIfExists(temporary);
                if (error instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("configuration file staging failed", error);
            }
        } catch (IOException error) {
            throw new IllegalStateException("configuration file staging failed", error);
        }
    }

    private void download(ConfigFile file, Path temporary, long expectedSize) throws IOException {
        HttpResponse<InputStream> response;
        try {
            response = client.send(HttpRequest.newBuilder(resolve(file.getUri(), file.getPath()))
                    .timeout(timeout).header("Accept", file.getContentType()).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("configuration file download interrupted", error);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IllegalStateException("configuration file HTTP " + response.statusCode());
        }
        try (InputStream input = response.body(); var output = Files.newOutputStream(temporary)) {
            MessageDigest digest = sha256();
            byte[] buffer = new byte[8192];
            long size = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                size += read;
                if (size > maxBytes) throw new IllegalArgumentException("configuration file exceeds the configured size limit");
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (size != expectedSize || !actual.equalsIgnoreCase(file.getSha256())) {
                throw new IllegalArgumentException("configuration file checksum or size mismatch");
            }
        }
    }

    private URI resolve(String uri, String path) {
        if (uri == null || uri.isBlank()) throw new IllegalArgumentException("config file uri is required");
        URI candidate = URI.create(uri);
        if ("http".equalsIgnoreCase(candidate.getScheme()) || "https".equalsIgnoreCase(candidate.getScheme())) {
            return candidate;
        }
        if (!URN_PREFIX.equals(uri.substring(0, Math.min(uri.length(), URN_PREFIX.length())))
                || controlPlaneBase == null) {
            throw new IllegalArgumentException("config file uri must use http, https, or the AgentTeams URN");
        }
        String suffix = uri.substring(URN_PREFIX.length());
        int separator = suffix.indexOf(':');
        if (separator <= 0 || separator == suffix.length() - 1) {
            throw new IllegalArgumentException("config file URN must contain snapshot and path");
        }
        String snapshotId = suffix.substring(0, separator);
        String urnPath = suffix.substring(separator + 1);
        if (!path.equals(urnPath)) throw new IllegalArgumentException("config file URI path does not match path");
        String base = controlPlaneBase.toString().replaceAll("/+$", "");
        return URI.create(base + "/api/v1/config/snapshots/" + snapshotId
                + "/files/content?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8));
    }

    private static Path safeRelativePath(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("config file path is required");
        Path path = Path.of(value);
        if (path.isAbsolute() || path.getNameCount() == 0 || path.iterator().next().toString().equals("..")) {
            throw new IllegalArgumentException("config file path must be relative");
        }
        for (Path part : path) {
            if (part.toString().equals("..")) throw new IllegalArgumentException("config file path must be relative");
        }
        return path.normalize();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
