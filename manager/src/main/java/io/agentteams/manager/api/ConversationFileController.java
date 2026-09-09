package io.agentteams.manager.api;

import io.agentteams.manager.conversation.ConversationFile;
import io.agentteams.manager.conversation.ConversationFileService;
import io.agentteams.manager.conversation.ConversationService;
import io.agentteams.manager.security.ConversationScopeAuthorizer;
import io.agentteams.manager.security.ManagerPrincipal;
import io.agentteams.manager.security.ManagerRequestContext;
import java.net.URL;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

/**
 * Uploads files produced by the conversation agent and serves presigned downloads.
 * POST requires an authenticated principal with conversation scope; GET is
 * intentionally anonymous (high-entropy fileId is the capability, mirroring the
 * anonymous presigned GET of the task artifact chain).
 */
@RestController
@RequestMapping("/api/v1/conversations")
public final class ConversationFileController {
    private final ConversationFileService files;
    private final ConversationService conversations;
    private final ConversationScopeAuthorizer scopeAuthorizer;

    public ConversationFileController(ConversationFileService files, ConversationService conversations,
            ConversationScopeAuthorizer scopeAuthorizer) {
        this.files = files;
        this.conversations = conversations;
        this.scopeAuthorizer = scopeAuthorizer;
    }

    @PostMapping("/{sessionId}/files")
    public ResponseEntity<FileResponse> upload(@PathVariable UUID sessionId,
            @RequestPart("file") MultipartFile part) throws Exception {
        ManagerPrincipal principal = ManagerRequestContext.require();
        var conversation = conversations.get(sessionId);
        // 服务身份（MCP 工具账号）代表用户上传：校验 scope 可达而非 owner 匹配。
        scopeAuthorizer.requireAccessible(conversation.context().project(),
                conversation.context().team(), principal);
        ConversationFile file = files.upload(sessionId, part.getOriginalFilename(),
                part.getContentType(), part.getBytes());
        String url = "/api/v1/conversations/" + sessionId + "/files/" + file.id();
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Location", url)
                .body(new FileResponse(file.id(), file.name(), file.contentType(), file.sizeBytes(), url));
    }

    @GetMapping("/{sessionId}/files/{fileId}")
    public ResponseEntity<Void> download(@PathVariable UUID sessionId, @PathVariable UUID fileId)
            throws java.net.URISyntaxException {
        URL presigned = files.presignDownload(sessionId, fileId, Duration.ofMinutes(15));
        if (presigned == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(presigned.toURI()).build();
    }

    /** Storage disabled (or misconfigured) degrades to an explicit 503, not a stack trace. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Void> storageUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    /** Multipart over 50MB degrades to an explicit 413 (spec §6). */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Void> tooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
    }

    public record FileResponse(UUID fileId, String name, String contentType, long sizeBytes, String url) { }
}
