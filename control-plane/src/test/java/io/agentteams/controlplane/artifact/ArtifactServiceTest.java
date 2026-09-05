package io.agentteams.controlplane.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentteams.storage.ObjectStorage;
import java.net.URL;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArtifactServiceTest {
    @Test
    void preparesBoundedUploadAndDownloadUrlsWithCanonicalKey() throws Exception {
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storage.presignPut(any(), any(), any())).thenReturn(new URL("https://minio.test/upload"));
        when(storage.presignGet(any(), any())).thenReturn(new URL("https://minio.test/download"));
        UUID task = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();

        ArtifactUpload upload = new ArtifactService(storage).prepareUpload(task, attempt, "result.json",
                "application/json", Duration.ofMinutes(10));

        assertThat(upload.storageKey()).isEqualTo("tasks/" + task + "/attempts/" + attempt
                + "/artifacts/result.json");
        assertThat(upload.uploadUrl().toString()).contains("upload");
    }
}
