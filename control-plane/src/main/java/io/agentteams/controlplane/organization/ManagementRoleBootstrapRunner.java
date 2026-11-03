package io.agentteams.controlplane.organization;

import io.agentteams.controlplane.audit.AuditEvent;
import io.agentteams.controlplane.audit.AuditRecorder;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** One-time first platform administrator bootstrap, enabled only by deployment configuration. */
@Component
public final class ManagementRoleBootstrapRunner implements ApplicationRunner {
    private final ManagementRoleRepository roles;
    private final AuditRecorder auditRecorder;
    private final Clock clock;
    private final String subject;

    @Autowired
    public ManagementRoleBootstrapRunner(ManagementRoleRepository roles, AuditRecorder auditRecorder,
            @Value("${agentteams.management.bootstrap-platform-admin-subject:}") String subject) {
        this(roles, auditRecorder, Clock.systemUTC(), subject);
    }

    ManagementRoleBootstrapRunner(ManagementRoleRepository roles, AuditRecorder auditRecorder, Clock clock,
            String subject) {
        this.roles = Objects.requireNonNull(roles, "roles");
        this.auditRecorder = Objects.requireNonNull(auditRecorder, "auditRecorder");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.subject = subject == null ? "" : subject.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (subject.isBlank()) return;
        var now = clock.instant();
        if (!roles.bootstrapPlatformAdminIfAbsent(subject, "bootstrap", now)) return;
        auditRecorder.record(new AuditEvent(UUID.randomUUID(), "bootstrap", "PLATFORM_ADMIN_BOOTSTRAPPED",
                "management_role", "platform", Map.of("target_subject_hash", hash(subject)), now));
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
