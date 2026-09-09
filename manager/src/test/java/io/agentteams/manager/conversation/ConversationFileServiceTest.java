package io.agentteams.manager.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.storage.ObjectStorage;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ConversationFileServiceTest {
    private static final UUID SESSION = UUID.randomUUID();
    private final ObjectStorage storage = mock(ObjectStorage.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ObjectStorage> storageProvider = mock(ObjectProvider.class);
    private final JdbcConversationFileRepository repository = mock(JdbcConversationFileRepository.class);
    private final ConversationFileService service = new ConversationFileService(storageProvider, repository);

    @BeforeEach
    void wireStorage() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
    }

    @Test
    void uploadSanitizesNameStoresObjectAndPersists() throws Exception {
        URL presigned = new URL("https://minio.test/get");
        when(storage.presignGet(anyString(), any(Duration.class))).thenReturn(presigned);
        ConversationFile file = service.upload(SESSION, "../../weird 空格 名.pdf\r\n",
                "application/pdf", new byte[] {1, 2, 3});
        assertThat(file.name()).isEqualTo("weird 空格 名.pdf");
        assertThat(file.sessionId()).isEqualTo(SESSION);
        assertThat(file.sizeBytes()).isEqualTo(3);
        assertThat(file.storageKey()).startsWith("conversations/" + SESSION + "/files/");
        assertThat(file.storageKey()).endsWith("/weird 空格 名.pdf");
        verify(storage).upload(anyString(), any(InputStream.class), anyLong(), anyString());
        verify(repository).insert(any(ConversationFile.class));
        when(repository.find(SESSION, file.id())).thenReturn(file);
        assertThat(service.presignDownload(SESSION, file.id(), Duration.ofMinutes(15))).isNotNull();
    }

    @Test
    void uploadRejectsOversizedContent() {
        byte[] big = new byte[(int) ConversationFileService.MAX_FILE_BYTES + 1];
        assertThatThrownBy(() -> service.upload(SESSION, "big.bin", "application/octet-stream", big))
                .isInstanceOf(ConversationFileService.FileTooLargeException.class);
        verify(storage, never()).upload(anyString(), any(InputStream.class), anyLong(), anyString());
    }

    @Test
    void sanitizedNameFallsBackToFileAndKeepsExtensionWhenTruncated() {
        String longName = "很长的名字".repeat(80) + ".pdf";
        String sanitized = ConversationFileService.sanitizeName(longName);
        assertThat(sanitized.length()).isLessThanOrEqualTo(255);
        assertThat(sanitized).endsWith(".pdf");
        assertThat(ConversationFileService.sanitizeName("///")).isEqualTo("file");
        assertThat(ConversationFileService.sanitizeName("a/b/c.txt")).isEqualTo("c.txt");
    }

    @Test
    void downloadPresignsWithRequestedExpiry() throws Exception {
        UUID fileId = UUID.randomUUID();
        when(repository.find(SESSION, fileId)).thenReturn(new ConversationFile(fileId, SESSION,
                "a.pdf", "application/pdf", 1, "conversations/" + SESSION + "/files/" + fileId + "/a.pdf",
                Instant.EPOCH));
        when(storage.presignGet(anyString(), any(Duration.class)))
                .thenReturn(new URL("https://minio.test/signed"));
        URL url = service.presignDownload(SESSION, fileId, Duration.ofMinutes(15));
        assertThat(url.toString()).isEqualTo("https://minio.test/signed");
    }

    @Test
    void downloadReturnsNullWhenFileMissing() {
        when(repository.find(SESSION, UUID.randomUUID())).thenReturn(null);
        assertThat(service.presignDownload(SESSION, UUID.randomUUID(), Duration.ofMinutes(15))).isNull();
    }

    @Test
    void requiresStorage() {
        when(storageProvider.getIfAvailable()).thenReturn(null);
        assertThatThrownBy(() -> service.upload(SESSION, "a.txt", "text/plain", new byte[1]))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.presignDownload(SESSION, UUID.randomUUID(), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
