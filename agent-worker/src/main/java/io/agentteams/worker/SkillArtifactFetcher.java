package io.agentteams.worker;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;

/** Downloads an explicitly addressed Skill artifact and verifies it before activation. */
final class SkillArtifactFetcher {
    private final HttpClient client;
    private final Duration timeout;
    private final long maxBytes;

    SkillArtifactFetcher(Duration timeout, long maxBytes) {
        this(timeout, maxBytes, HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(timeout, "timeout")).build());
    }

    SkillArtifactFetcher(Duration timeout, long maxBytes, HttpClient client) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        this.maxBytes = maxBytes;
        this.client = Objects.requireNonNull(client, "client");
    }

    Path fetch(ResourceBindingLoader.ResourceBinding binding, Path versionDirectory) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(versionDirectory, "versionDirectory");
        if (!"SKILL".equals(binding.type()) || binding.artifactRef() == null) {
            throw new IllegalArgumentException("Skill binding artifactRef is required");
        }
        URI uri = URI.create(binding.artifactRef());
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Skill artifactRef must use http or https");
        }
        if (binding.sizeBytes() < 0 || binding.sizeBytes() > maxBytes) {
            throw new IllegalArgumentException("Skill artifact exceeds the configured size limit");
        }
        Path directory = versionDirectory.toAbsolutePath().normalize();
        Path target = directory.resolve("skills").resolve(artifactFileName(binding)).normalize();
        if (!target.startsWith(directory)) throw new IllegalArgumentException("Skill artifact path is unsafe");
        Path temporary;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), ".skill-", ".part");
            try {
                download(uri, binding, temporary);
                move(temporary, target);
                return target;
            } catch (RuntimeException | IOException error) {
                Files.deleteIfExists(temporary);
                if (error instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Skill artifact staging failed", error);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Skill artifact staging failed", error);
        }
    }

    private void download(URI uri, ResourceBindingLoader.ResourceBinding binding, Path temporary) throws IOException {
        HttpResponse<InputStream> response;
        try {
            response = client.send(HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/gzip")
                    .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Skill artifact download interrupted", error);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IllegalStateException("Skill artifact HTTP " + response.statusCode());
        }
        try (InputStream input = response.body(); var output = Files.newOutputStream(temporary)) {
            MessageDigest digest = sha256();
            byte[] buffer = new byte[8192];
            long size = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                size += read;
                if (size > maxBytes) throw new IllegalArgumentException("Skill artifact exceeds the configured size limit");
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (size != binding.sizeBytes() || !actual.equalsIgnoreCase(normalizeDigest(binding.digest()))) {
                throw new IllegalArgumentException("Skill artifact digest or size mismatch");
            }
        }
    }

    private static String safeName(ResourceBindingLoader.ResourceBinding binding) {
        return sha256Hex(binding.reference() + "\u0000" + binding.revision()) + ".tar.gz";
    }

    static String artifactFileName(ResourceBindingLoader.ResourceBinding binding) {
        return safeName(binding);
    }

    static String artifactDirectoryName(ResourceBindingLoader.ResourceBinding binding) {
        return sha256Hex(binding.reference() + "\u0000" + binding.revision());
    }

    private static String normalizeDigest(String digest) {
        String value = digest == null ? "" : digest.trim();
        if (value.regionMatches(true, 0, "sha256:", 0, 7)) value = value.substring(7);
        if (!value.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Skill digest must be SHA-256");
        return value;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256().digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
