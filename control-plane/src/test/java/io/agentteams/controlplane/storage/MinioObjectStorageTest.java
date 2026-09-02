package io.agentteams.controlplane.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MinioObjectStorageTest {

    @Mock
    private MinioClient minioClient;

    @Captor
    private ArgumentCaptor<PutObjectArgs> putObjectArgs;

    @Captor
    private ArgumentCaptor<GetObjectArgs> getObjectArgs;

    @Captor
    private ArgumentCaptor<RemoveObjectArgs> removeObjectArgs;

    @Captor
    private ArgumentCaptor<GetPresignedObjectUrlArgs> presignedObjectUrlArgs;

    @Test
    void uploadsObjectToConfiguredBucketWithContentMetadata() throws Exception {
        MinioObjectStorage storage = new MinioObjectStorage(minioClient, "agent-artifacts");
        InputStream content = new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8));

        storage.upload("tasks/1/output.txt", content, 7, "text/plain");

        verify(minioClient).putObject(putObjectArgs.capture());
        assertThat(putObjectArgs.getValue().bucket()).isEqualTo("agent-artifacts");
        assertThat(putObjectArgs.getValue().object()).isEqualTo("tasks/1/output.txt");
        assertThat(putObjectArgs.getValue().objectSize()).isEqualTo(7);
        assertThat(putObjectArgs.getValue().contentType()).isEqualTo("text/plain");
    }

    @Test
    void downloadsObjectFromConfiguredBucket() throws Exception {
        MinioObjectStorage storage = new MinioObjectStorage(minioClient, "agent-artifacts");
        GetObjectResponse expected = org.mockito.Mockito.mock(GetObjectResponse.class);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(expected);

        InputStream actual = storage.download("tasks/1/output.bin");

        assertThat(actual).isSameAs(expected);
        verify(minioClient).getObject(getObjectArgs.capture());
        assertThat(getObjectArgs.getValue().bucket()).isEqualTo("agent-artifacts");
        assertThat(getObjectArgs.getValue().object()).isEqualTo("tasks/1/output.bin");
    }

    @Test
    void deletesObjectFromConfiguredBucket() throws Exception {
        MinioObjectStorage storage = new MinioObjectStorage(minioClient, "agent-artifacts");

        storage.delete("tasks/1/output.bin");

        verify(minioClient).removeObject(removeObjectArgs.capture());
        assertThat(removeObjectArgs.getValue().bucket()).isEqualTo("agent-artifacts");
        assertThat(removeObjectArgs.getValue().object()).isEqualTo("tasks/1/output.bin");
    }

    @Test
    void createsGetPresignedUrlWithRequestedExpiry() throws Exception {
        MinioObjectStorage storage = new MinioObjectStorage(minioClient, "agent-artifacts");
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("https://minio.example.test/agent-artifacts/tasks/1/output.txt");

        URL url = storage.presignGet("tasks/1/output.txt", Duration.ofMinutes(15));

        assertThat(url.toString()).isEqualTo(
                "https://minio.example.test/agent-artifacts/tasks/1/output.txt");
        verify(minioClient).getPresignedObjectUrl(presignedObjectUrlArgs.capture());
        assertThat(presignedObjectUrlArgs.getValue().method()).isEqualTo(Method.GET);
        assertThat(presignedObjectUrlArgs.getValue().bucket()).isEqualTo("agent-artifacts");
        assertThat(presignedObjectUrlArgs.getValue().object()).isEqualTo("tasks/1/output.txt");
        assertThat(presignedObjectUrlArgs.getValue().expiry()).isEqualTo(900);
    }

    @Test
    void usesDedicatedClientForBrowserFacingPresignedUrls() throws Exception {
        MinioClient presignClient = org.mockito.Mockito.mock(MinioClient.class);
        MinioObjectStorage storage = new MinioObjectStorage(
                minioClient, presignClient, "agent-artifacts");
        when(presignClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://127.0.0.1:19000/agent-artifacts/tasks/1/input.zip");

        URL url = storage.presignPut(
                "tasks/1/input.zip", "application/zip", Duration.ofMinutes(10));

        assertThat(url.toString()).isEqualTo(
                "http://127.0.0.1:19000/agent-artifacts/tasks/1/input.zip");
        verify(presignClient).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
        verifyNoInteractions(minioClient);
    }

    @Test
    void rejectsInvalidObjectKeyAndExpiry() {
        MinioObjectStorage storage = new MinioObjectStorage(minioClient, "agent-artifacts");

        assertThatThrownBy(() -> storage.download(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("objectKey must not be blank");
        assertThatThrownBy(() -> storage.presignGet("object.txt", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expiry must be between one second and seven days");
    }

    @Test
    void wrapsMinioFailuresWithOperationAndObjectKey() throws Exception {
        MinioObjectStorage storage = new MinioObjectStorage(minioClient, "agent-artifacts");
        RuntimeException failure = new RuntimeException("backend unavailable");
        when(minioClient.getObject(any(GetObjectArgs.class))).thenThrow(failure);

        assertThatThrownBy(() -> storage.download("tasks/1/output.bin"))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessage("object storage download failed for tasks/1/output.bin")
                .hasCause(failure);
    }

    @Test
    void validatesMinioConfiguration() {
        assertThatThrownBy(() -> new MinioObjectStorageConfig(" ", "bucket", "key", "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("endpoint must not be blank");
        assertThatThrownBy(() -> new MinioObjectStorageConfig("http://minio:9000", " ", "key", "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("bucket must not be blank");
    }
}
