package io.agentteams.controlplane.taskfile;

import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskAttemptRecord;
import io.agentteams.controlplane.service.ResourceNotFoundException;
import io.agentteams.storage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * G05 任务文件账本业务：OUTPUT 服务端直传（先 storage 后落库，与 ConversationFileService
 * 同序）、INPUT 元数据快照登记、清单查询、MISSING 对账。run 终态不因交付失败改变
 * （过程 best-effort 公理）；去重按 (task, role, name, sha256) 返回既有记录。
 */
@Service
public final class TaskFileService {

    public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    private final ObjectProvider<ObjectStorage> storageProvider;
    private final JdbcTaskFileRepository repository;
    private final FoundationPersistenceService persistence;
    private final Clock clock;

    public TaskFileService(ObjectProvider<ObjectStorage> storageProvider,
            JdbcTaskFileRepository repository, FoundationPersistenceService persistence, Clock clock) {
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    private ObjectStorage requireStorage() {
        ObjectStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            throw new IllegalStateException("object storage is not enabled (agentteams.storage.enabled=false)");
        }
        return storage;
    }

    public TaskFileRecord upload(UUID taskId, String originalName, String contentType, byte[] content) {
        ObjectStorage storage = requireStorage();
        requireExistingTask(taskId);
        if (content.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("file exceeds 50MB limit");
        }
        String name = TaskFileNames.sanitize(originalName);
        String sha256 = sha256(content);
        var existing = repository.findDedup(taskId, TaskFileRecord.OUTPUT, name, sha256);
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID fileId = UUID.randomUUID();
        String safeContentType = contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType;
        String storageKey = "tasks/" + taskId + "/files/" + fileId + "/" + name;
        storage.upload(storageKey, new ByteArrayInputStream(content), content.length, safeContentType);
        Instant now = clock.instant();
        TaskFileRecord record = new TaskFileRecord(fileId, taskId, latestAttemptId(taskId),
                TaskFileRecord.OUTPUT, name, safeContentType, content.length, sha256, storageKey,
                null, null, TaskFileRecord.AVAILABLE, now, now);
        repository.insert(record);
        return record;
    }

    public TaskFileRecord registerInput(UUID taskId, UUID sessionId, UUID sourceFileId,
            String name, long sizeBytes) {
        requireExistingTask(taskId);
        String safeName = TaskFileNames.sanitize(name);
        var existing = repository.findByTask(taskId, TaskFileRecord.INPUT).stream()
                .filter(item -> safeName.equals(item.name())
                        && sourceFileId.equals(item.sourceFileId()))
                .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant now = clock.instant();
        TaskFileRecord record = new TaskFileRecord(UUID.randomUUID(), taskId, null,
                TaskFileRecord.INPUT, safeName, null, sizeBytes, null, "",
                sessionId, sourceFileId, TaskFileRecord.AVAILABLE, now, now);
        repository.insert(record);
        return record;
    }

    public List<TaskFileRecord> list(UUID taskId, String role) {
        requireExistingTask(taskId);
        return repository.findByTask(taskId, role);
    }

    public TaskFileRecord get(UUID taskId, UUID fileId) {
        requireExistingTask(taskId);
        return repository.findById(fileId)
                .filter(record -> record.taskId().equals(taskId))
                .orElseThrow(() -> new ResourceNotFoundException("task file", fileId));
    }

    /** Streams an OUTPUT object; INPUT records never reach here（controller 409 门卫）。 */
    public InputStream outputContent(TaskFileRecord record) {
        if (record.isInput()) {
            throw new IllegalArgumentException("input attachments live in the conversation domain");
        }
        return requireStorage().download(record.storageKey());
    }

    /** Browser-audience presigned GET（presignEndpoint 受众）；storage 未启用时 503。 */
    public java.net.URL presignForBrowser(TaskFileRecord record, Duration expiry) {
        return requireStorage().presignGet(record.storageKey(), expiry);
    }

    public void reconcile(UUID taskId) {
        List<TaskFileRecord> files = repository.findByTask(taskId, null);
        if (files.isEmpty()) {
            return;
        }
        ObjectStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            return;
        }
        Instant now = clock.instant();
        for (TaskFileRecord record : files) {
            markMissingIfGone(storage, record, now);
        }
    }

    /** 全表批处理对账（TaskFileReconciliationJob 用）；返回标记 MISSING 的数量。 */
    public int reconcileBatch(int limit) {
        // storage 未启用时直接跳过，不做无谓 DB 查询。
        ObjectStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            return 0;
        }
        List<TaskFileRecord> outputs = repository.findAvailableOutputs(limit);
        if (outputs.isEmpty()) {
            return 0;
        }
        Instant now = clock.instant();
        int marked = 0;
        for (TaskFileRecord record : outputs) {
            if (markMissingIfGone(storage, record, now)) {
                marked++;
            }
        }
        return marked;
    }

    /** 探测单条记录；真正标记了 MISSING（OUTPUT）才返回 true。 */
    private boolean markMissingIfGone(ObjectStorage storage, TaskFileRecord record, Instant now) {
        if (record.isInput() || TaskFileRecord.MISSING.equals(record.status())) {
            return false;
        }
        if (!storage.exists(record.storageKey())) {
            return repository.markMissing(record.id(), now);
        }
        return false;
    }

    private void requireExistingTask(UUID taskId) {
        // 轻量存在性校验：复用 FoundationPersistenceService，避免整 spec 解析开销。
        if (persistence.findTask(taskId).isEmpty()) {
            throw new ResourceNotFoundException("task", taskId);
        }
    }

    /** 最近创建的 attempt（无则空）；以 createdAt 比较避免依赖 findByTaskId 排序（规格偏离 2）。 */
    private UUID latestAttemptId(UUID taskId) {
        return persistence.findTaskExecution(taskId).stream()
                .map(FoundationPersistenceService.TaskExecutionRecord::attempt)
                .max(Comparator.comparing(TaskAttemptRecord::createdAt))
                .map(TaskAttemptRecord::id)
                .orElse(null);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
