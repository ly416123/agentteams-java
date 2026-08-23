package io.agentteams.controlplane.audit;

import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only operation audit endpoint. */
@RestController
@RequestMapping("/api/v1/audit-events")
public final class AuditEventsController {
    private final JdbcAuditQueryService service;

    public AuditEventsController(JdbcAuditQueryService service) {
        this.service = java.util.Objects.requireNonNull(service, "service");
    }

    @GetMapping
    public List<AuditEvent> list(
            @RequestParam(name = "actor", required = false) String actor,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "resourceType", required = false) String resourceType,
            @RequestParam(name = "resourceId", required = false) String resourceId,
            @RequestParam(name = "before", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return service.find(new JdbcAuditQueryService.AuditEventQuery(actor, action, resourceType, resourceId,
                before, limit));
    }
}
