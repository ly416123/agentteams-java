package io.agentteams.controlplane.task;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Internal, token-protected view for operators and automated health checks. */
@RestController
@ConditionalOnProperty(name = "agentteams.task-state-consistency.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/internal/v1/task-state-consistency")
public final class TaskStateConsistencyController {
    static final String TOKEN_HEADER = "X-AgentTeams-Internal-Token";

    private final TaskStateConsistencyService service;
    private final String internalToken;

    public TaskStateConsistencyController(TaskStateConsistencyService service,
            @Value("${agentteams.quota.internal-token:}") String internalToken) {
        this.service = Objects.requireNonNull(service, "service");
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    @GetMapping("/issues")
    public ResponseEntity<?> findOpenIssues(
            @RequestHeader(name = TOKEN_HEADER, required = false) String token,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        if (!authorized(token)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        if (limit < 1 || limit > 1000) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(List.copyOf(service.findOpenIssues(limit)));
    }

    private boolean authorized(String supplied) {
        if (internalToken.isBlank() || supplied == null) return false;
        return MessageDigest.isEqual(internalToken.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }
}
