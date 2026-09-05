package io.agentteams.controlplane.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.storage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArtifactVerificationTest {
    @Test
    void verifiesSizeAndSha256BeforeMetadataCanBeAccepted() throws Exception {
        byte[] payload = "artifact".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ArtifactService service = new ArtifactService(new InMemoryStorage(payload));
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload));
        assertThat(service.verifyUploadedObject("artifacts/x", sha, payload.length).sha256()).isEqualTo(sha);
        assertThatThrownBy(() -> service.verifyUploadedObject("artifacts/x", sha, payload.length + 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mismatch");
    }

    private static final class InMemoryStorage implements ObjectStorage {
        private final byte[] payload;
        private InMemoryStorage(byte[] payload) { this.payload = payload; }
        public void upload(String k, InputStream c, long l, String t) { }
        public InputStream download(String k) { return new ByteArrayInputStream(payload); }
        public void delete(String k) { }
        public URL presignGet(String k, Duration e) { return url(); }
        public URL presignPut(String k, String t, Duration e) { return url(); }
        private static URL url() { try { return new URL("https://example.test/object"); } catch (Exception e) { throw new AssertionError(e); } }
    }
}
