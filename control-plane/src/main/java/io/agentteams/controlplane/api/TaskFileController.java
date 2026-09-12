package io.agentteams.controlplane.api;

import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.service.TaskService;
import io.agentteams.controlplane.taskfile.TaskFileRecord;
import io.agentteams.controlplane.taskfile.TaskFileService;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * G05 任务文件端点：上传/登记/清单/内容/浏览器 302。鉴权沿 SubtaskController 模式
 * ——未知任务 404，有 principal 时按任务 specJson 校验作用域；写操作要求幂等键
 * （协议存在性校验）。浏览器 302 出口用 presignEndpoint（浏览器受众）；集群内组件
 * 一律走 /content 代理流（presigned 受众 pitfall）。
 * 登记端点路径沿规格 §6 为 /api/v1/tasks/{taskId}/attachments（无 /files 段），
 * 故不用类级 @RequestMapping 前缀，各方法写全路径。
 */
@RestController
public final class TaskFileController {

    private static final Duration BROWSER_PRESIGN_TTL = Duration.ofMinutes(15);

    private final TaskFileService files;
    private final TaskService tasks;

    public TaskFileController(TaskFileService files, TaskService tasks) {
        this.files = files;
        this.tasks = tasks;
    }

    /** OUTPUT multipart 上传（服务端直传 MinIO，先对象后落库）。 */
    @PostMapping("/api/v1/tasks/{taskId}/files")
    public ResponseEntity<TaskFileResponse> upload(@PathVariable UUID taskId,
            @RequestPart("file") MultipartFile part) throws Exception {
        requireExistingTaskScope(taskId);
        TaskFileRecord record = files.upload(taskId, part.getOriginalFilename(),
                part.getContentType(), part.getBytes());
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Location", "/api/v1/tasks/" + taskId + "/files/" + record.id())
                .body(TaskFileResponse.from(record));
    }

    /** INPUT 登记快照（JSON，幂等键必带）。 */
    @PostMapping("/api/v1/tasks/{taskId}/attachments")
    public ResponseEntity<AttachmentListResponse> registerAttachments(@PathVariable UUID taskId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AttachmentsRequest request) {
        requireExistingTaskScope(taskId);
        requireIdempotencyKey(idempotencyKey);
        if (request == null || request.attachments() == null) {
            throw new IllegalArgumentException("attachments is required");
        }
        List<TaskFileResponse> registered = request.attachments().stream()
                .map(item -> TaskFileResponse.from(files.registerInput(taskId, item.sessionId(),
                        item.fileId(), item.name(), item.sizeBytes())))
                .toList();
        return ResponseEntity.status(HttpStatus.CREATED).body(new AttachmentListResponse(registered));
    }

    @GetMapping("/api/v1/tasks/{taskId}/files")
    public List<TaskFileResponse> list(@PathVariable UUID taskId,
            @RequestParam(required = false) String role) {
        requireExistingTaskScope(taskId);
        return files.list(taskId, role).stream().map(TaskFileResponse::from).toList();
    }

    /** 集群内代理流下载（MCP/汇总轮受众）。INPUT 记录 409（对象在会话域）。 */
    @GetMapping("/api/v1/tasks/{taskId}/files/{fileId}/content")
    public ResponseEntity<StreamingResponseBody> content(@PathVariable UUID taskId,
            @PathVariable UUID fileId) {
        requireExistingTaskScope(taskId);
        TaskFileRecord record = files.get(taskId, fileId);
        if (record.isInput()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        InputStream stream = files.outputContent(record);
        StreamingResponseBody body = output -> stream.transferTo(output);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM).body(body);
    }

    /** 浏览器 302 出口（presignEndpoint 受众，与 artifacts downloadUrl 同款）；仅 OUTPUT。 */
    @GetMapping("/api/v1/tasks/{taskId}/files/{fileId}/download")
    public ResponseEntity<Void> download(@PathVariable UUID taskId, @PathVariable UUID fileId)
            throws java.net.URISyntaxException {
        requireExistingTaskScope(taskId);
        TaskFileRecord record = files.get(taskId, fileId);
        if (record.isInput()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        java.net.URL presigned = files.presignForBrowser(record, BROWSER_PRESIGN_TTL);
        return ResponseEntity.status(HttpStatus.FOUND).location(presigned.toURI()).build();
    }

    /** SubtaskController.requireExistingTaskScope 同款（未知任务 404，有 principal 校验作用域）。 */
    private void requireExistingTaskScope(UUID taskId) {
        TaskRecord task = tasks.get(taskId);
        if (PrincipalContext.current().isPresent()) {
            PrincipalContext.requireScope(task.specJson());
        }
    }

    /** SubtaskController.requireIdempotencyKey 同款（协议存在性校验，无去重语义）。 */
    private static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        if (idempotencyKey.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must be at most 255 characters");
        }
    }

    /** Storage disabled (or misconfigured) degrades to an explicit 503（ConversationFileController 同款）。 */
    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Void> storageUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    /** Multipart over 50MB degrades to an explicit 413（spec §6；application.yml 上限由容器抛出）。 */
    @org.springframework.web.bind.annotation.ExceptionHandler(
            org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Void> tooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
    }

    public record AttachmentsRequest(List<AttachmentInput> attachments) {
    }

    public record AttachmentInput(UUID sessionId, UUID fileId, String name, long sizeBytes) {
    }

    public record AttachmentListResponse(List<TaskFileResponse> files) {
    }

    public record TaskFileResponse(UUID fileId, String role, String name, String contentType,
            long sizeBytes, String sha256, String status, UUID sessionId, UUID sourceFileId) {

        static TaskFileResponse from(TaskFileRecord record) {
            return new TaskFileResponse(record.id(), record.role(), record.name(),
                    record.contentType(), record.sizeBytes(), record.sha256(), record.status(),
                    record.sourceSessionId(), record.sourceFileId());
        }
    }
}
