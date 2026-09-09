package io.agentteams.manager.conversation;

import io.agentteams.storage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Uploads conversation-produced files to object storage and presigns downloads. */
@Service
public final class ConversationFileService {
    public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    private static final int MAX_NAME_LENGTH = 255;

    /** Raised when the uploaded file exceeds {@link #MAX_FILE_BYTES}. */
    public static final class FileTooLargeException extends RuntimeException {
        public FileTooLargeException(long size) {
            super("file exceeds " + MAX_FILE_BYTES + " bytes: " + size);
        }
    }

    private final ObjectProvider<ObjectStorage> storageProvider;
    private final JdbcConversationFileRepository repository;

    public ConversationFileService(ObjectProvider<ObjectStorage> storageProvider,
            JdbcConversationFileRepository repository) {
        this.storageProvider = storageProvider;
        this.repository = repository;
    }

    /** 清洗文件名：取最后路径段、去控制字符、截断（保留尾部扩展名）。 */
    static String sanitizeName(String raw) {
        String name = raw == null ? "" : raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (name.isBlank()) {
            return "file";
        }
        return name.length() <= MAX_NAME_LENGTH ? name : name.substring(name.length() - MAX_NAME_LENGTH);
    }

    private ObjectStorage requireStorage() {
        ObjectStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            throw new IllegalStateException(
                    "object storage is not enabled (agentteams.storage.enabled=false)");
        }
        return storage;
    }

    /** Stores the bytes and persists the record; returns the stored file. */
    public ConversationFile upload(UUID sessionId, String originalName, String contentType, byte[] content) {
        ObjectStorage storage = requireStorage();
        if (content.length > MAX_FILE_BYTES) {
            throw new FileTooLargeException(content.length);
        }
        String name = sanitizeName(originalName);
        UUID fileId = UUID.randomUUID();
        String safeContentType = contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType;
        String storageKey = "conversations/" + sessionId + "/files/" + fileId + "/" + name;
        storage.upload(storageKey, new ByteArrayInputStream(content), content.length, safeContentType);
        ConversationFile file = new ConversationFile(fileId, sessionId, name, safeContentType,
                content.length, storageKey, Instant.now());
        repository.insert(file);
        return file;
    }

    /** Presigned GET for the record; null when the file does not exist. */
    public URL presignDownload(UUID sessionId, UUID fileId, Duration expiry) {
        ObjectStorage storage = requireStorage();
        ConversationFile file = repository.find(sessionId, fileId);
        if (file == null) {
            return null;
        }
        return storage.presignGet(file.storageKey(), expiry);
    }
}
