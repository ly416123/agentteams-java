package io.agentteams.controlplane.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.application.api.TaskResultManifest;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.IdempotencyConflictException;
import io.agentteams.controlplane.persistence.IdempotencyKeyRecord;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.persistence.TaskResultVersionRecord;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceAction;
import io.agentteams.controlplane.security.ResourceAuthorizationService;
import io.agentteams.controlplane.service.IdempotencyService;
import io.agentteams.controlplane.service.ResourceNotFoundException;
import io.agentteams.domain.task.TaskPhase;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 业务结果版本（G02 D1-D4）：一次「已提交待验收」的交付物。
 * D2：manifest publish（SUCCEEDED）同事务联动提交；同 run 重放幂等跳过。
 * D4：评审仅针对 SUBMITTED 结果；打回必填意见，重新交付走显式 retry。
 */
@Service
public class TaskResultVersionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskResultVersionService.class);

    private static final String SUBMIT_RESULT = "SUBMIT_RESULT";
    private static final String REVIEW_RESULT = "REVIEW_RESULT";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FoundationPersistenceService persistence;
    private final IdempotencyService idempotency;
    private final Clock clock;
    private final ResourceAuthorizationService authorization;

    @Autowired
    public TaskResultVersionService(FoundationPersistenceService persistence, IdempotencyService idempotency,
            ObjectProvider<ResourceAuthorizationService> authorization) {
        this(persistence, idempotency, Clock.systemUTC(), authorization.getIfAvailable());
    }

    TaskResultVersionService(FoundationPersistenceService persistence, IdempotencyService idempotency, Clock clock,
            ResourceAuthorizationService authorization) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.authorization = authorization;
    }

    /** D2：由 {@link TaskResultManifestService#publish} 在其事务内调用。 */
    @Transactional
    public Optional<TaskResultVersionRecord> onManifestPublished(ExecutionContext context,
            TaskResultManifest manifest) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(manifest, "manifest");
        if (!"SUCCEEDED".equalsIgnoreCase(manifest.status())) {
            return Optional.empty();
        }
        return persistence.inTransaction(tx -> {
            // 任务行锁：序列化同任务的结果版本号分配，避免并发提交撞 task_seq_unique；
            // 同 run 幂等检查必须在锁内复查——并发重复投递时两个事务都能通过锁外检查，
            // 串行进锁后会各自插入同 run 的两个结果版本（UNIQUE(run_id) 兕底见 V92）
            tx.tasks().findByIdForUpdate(manifest.taskId())
                    .orElseThrow(() -> new ResourceNotFoundException("task", manifest.taskId()));
            if (tx.taskResultVersions().findByRun(manifest.runId()).isPresent()) {
                return Optional.<TaskResultVersionRecord>empty();
            }
            // G03 D3 硬约束：MAIN 任务存在非 SUCCEEDED 子任务时跳过结果版本
            // （拆解轮 run 正常结束但不产生结果版本；汇总轮 publish 时子任务已全 SUCCEEDED。
            //  countByParentNotPhase 对无子任务的任务恒为 0，G02 既有行为不变）。
            if (tx.tasks().countByParentNotPhase(manifest.taskId(), TaskPhase.SUCCEEDED) > 0) {
                LOGGER.warn("result version skipped, subtasks not all SUCCEEDED taskId={}",
                        manifest.taskId());
                return Optional.<TaskResultVersionRecord>empty();
            }
            int seq = tx.taskResultVersions().latestSeq(manifest.taskId()).orElse(0) + 1;
            Instant now = clock.instant();
            TaskResultVersionRecord record = new TaskResultVersionRecord(UUID.randomUUID(), manifest.taskId(),
                    manifest.runId(), manifestId(manifest.runId()), null, seq,
                    TaskResultVersionRecord.STATUS_SUBMITTED, manifest.summary(),
                    contentJson(manifest.artifacts()), context.subjectId(), now, null, null, null, now, now, 0);
            tx.taskResultVersions().insert(record);
            FoundationPersistenceService.appendEvent(tx, "task", manifest.taskId(), "ResultSubmitted",
                    resultPayload(record), now, tx.tasks().incrementVersion(manifest.taskId()));
            return Optional.of(record);
        });
    }

    public record ReviewCommand(String decision, String comment, String actor) {
    }

    /** D4：仅 SUBMITTED 可评审；REVISION_REQUIRED 必填 comment；幂等重放返回首次结果。 */
    public TaskResultVersionRecord review(UUID taskId, UUID resultId, ReviewCommand input, Long expectedVersion,
            String idempotencyKey) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(resultId, "resultId");
        Objects.requireNonNull(input, "input");
        String decision = input.decision() == null ? "" : input.decision().trim().toUpperCase(Locale.ROOT);
        if (!TaskResultVersionRecord.STATUS_ACCEPTED.equals(decision)
                && !TaskResultVersionRecord.STATUS_REVISION_REQUIRED.equals(decision)) {
            throw new IllegalArgumentException("decision must be ACCEPTED or REVISION_REQUIRED");
        }
        String comment = input.comment() == null ? null : input.comment().trim();
        if (TaskResultVersionRecord.STATUS_REVISION_REQUIRED.equals(decision)
                && (comment == null || comment.isBlank())) {
            throw new IllegalArgumentException("comment is required when returning a result for revision");
        }
        authorizeTask(taskId);
        String actor = defaultActor(input.actor());
        String requestHash = idempotency.requestHash(taskId.toString(), resultId.toString(), decision,
                comment == null ? "" : comment, actor);
        return persistence.inTransaction(tx -> {
            String key = idempotency.requireKey(idempotencyKey);
            var existing = tx.idempotencyKeys().findByKey(key);
            if (existing.isPresent()) {
                assertIdempotency(existing.get(), requestHash, key);
                return tx.taskResultVersions().findById(existing.get().resourceId())
                        .orElseThrow(() -> new IllegalStateException("idempotent result review is missing"));
            }
            // 任务行锁：串行化同任务的评审事件追加（事件 version 递增与 updateReview 写入顺序稳定）
            tx.tasks().findByIdForUpdate(taskId)
                    .orElseThrow(() -> new ResourceNotFoundException("task", taskId));
            TaskResultVersionRecord current = tx.taskResultVersions().findById(resultId)
                    .orElseThrow(() -> new ResourceNotFoundException("task_result_version", resultId));
            if (!current.taskId().equals(taskId)) {
                throw new ResourceNotFoundException("task_result_version", resultId);
            }
            if (!current.submitted()) {
                throw new TaskReviewConflictException("RESULT_NOT_SUBMITTED",
                        "only SUBMITTED results can be reviewed");
            }
            // 幂等记录锚定到结果版本自身：重放恢复时按 resultId 回查 task_result_versions
            IdempotencyKeyRecord keyRecord = new IdempotencyKeyRecord(UUID.randomUUID(), key, REVIEW_RESULT,
                    requestHash, "task_result_version", resultId, resultPayload(current),
                    clock.instant(), clock.instant(), 0);
            if (!tx.idempotencyKeys().insertIfAbsent(keyRecord)) {
                IdempotencyKeyRecord winner = tx.idempotencyKeys().findByKey(key)
                        .orElseThrow(() -> new IllegalStateException("idempotency key disappeared"));
                assertIdempotency(winner, requestHash, key);
                return tx.taskResultVersions().findById(winner.resourceId())
                        .orElseThrow(() -> new IllegalStateException("idempotent result review is missing"));
            }
            long version = expectedVersion == null ? current.version() : expectedVersion;
            TaskResultVersionRecord updated = tx.taskResultVersions().updateReview(resultId, decision, actor,
                    comment, clock.instant(), version);
            FoundationPersistenceService.appendEvent(tx, "task", taskId, "ResultReviewed",
                    reviewPayload(updated), clock.instant(), tx.tasks().incrementVersion(taskId));
            return updated;
        });
    }

    public List<TaskResultVersionRecord> list(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId");
        return persistence.inTransaction(tx -> {
            requireVisible(tx, taskId);
            return tx.taskResultVersions().findByTask(taskId, 1000);
        });
    }

    public TaskResultVersionRecord get(UUID taskId, UUID resultId) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(resultId, "resultId");
        return persistence.inTransaction(tx -> {
            requireVisible(tx, taskId);
            TaskResultVersionRecord record = tx.taskResultVersions().findById(resultId)
                    .orElseThrow(() -> new ResourceNotFoundException("task_result_version", resultId));
            if (!record.taskId().equals(taskId)) {
                throw new ResourceNotFoundException("task_result_version", resultId);
            }
            return record;
        });
    }

    private void requireVisible(io.agentteams.controlplane.persistence.FoundationTransaction tx, UUID taskId) {
        if (PrincipalContext.current().isPresent()) {
            TaskRecord task = tx.tasks().findById(taskId)
                    .orElseThrow(() -> new ResourceNotFoundException("task", taskId));
            PrincipalContext.requireScope(task.specJson());
        }
    }

    private void authorizeTask(UUID taskId) {
        if (authorization == null && PrincipalContext.current().isEmpty()) {
            return;
        }
        TaskRecord task = persistence.findTask(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("task", taskId));
        if (PrincipalContext.current().isPresent()) {
            PrincipalContext.requireScope(task.specJson());
            if (authorization != null) {
                authorization.require(ResourceAction.TASK_APPROVE, PrincipalContext.current().orElseThrow().scope());
            }
        }
    }

    private void assertIdempotency(IdempotencyKeyRecord existing, String requestHash, String key) {
        if (!REVIEW_RESULT.equals(existing.operation()) || !requestHash.equals(existing.requestHash())) {
            throw new IdempotencyConflictException(key, REVIEW_RESULT);
        }
    }

    private static UUID manifestId(UUID runId) {
        // 与 JdbcTaskResultManifestRepository.manifestId 同源派生，保证两表可关联追溯
        return UUID.nameUUIDFromBytes(("task-result-manifest:" + runId).getBytes(StandardCharsets.UTF_8));
    }

    private static String contentJson(List<TaskResultManifest.ArtifactMetadata> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return null;
        }
        try {
            List<java.util.Map<String, Object>> items = artifacts.stream().<java.util.Map<String, Object>>map(
                    artifact -> java.util.Map.of("name", artifact.name(), "storageRef", artifact.storageRef(),
                            "contentType", artifact.contentType(), "sizeBytes", artifact.sizeBytes(),
                            "sha256", artifact.sha256(), "stage", artifact.stage())).toList();
            return JSON.writeValueAsString(java.util.Map.of("artifacts", items));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("result artifacts could not be encoded", error);
        }
    }

    private static String resultPayload(TaskResultVersionRecord record) {
        return "{\"taskId\":\"" + record.taskId() + "\",\"resultId\":\"" + record.id()
                + "\",\"seq\":" + record.seq() + ",\"status\":\"" + record.status() + "\"}";
    }

    private static String reviewPayload(TaskResultVersionRecord record) {
        // reviewActor 来自请求体（用户可控），必须经序列化而非手工拼接，避免引号破坏 JSON
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("taskId", record.taskId().toString());
        payload.put("resultId", record.id().toString());
        payload.put("seq", record.seq());
        payload.put("status", record.status());
        payload.put("reviewActor", record.reviewActor());
        try {
            return JSON.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("result review payload could not be encoded", error);
        }
    }

    private static String defaultActor(String fallback) {
        return PrincipalContext.actorOr(fallback == null || fallback.isBlank() ? "api" : fallback.trim());
    }
}
