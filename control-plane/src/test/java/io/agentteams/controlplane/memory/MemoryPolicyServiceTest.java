package io.agentteams.controlplane.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.application.api.MemoryPolicy;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryPolicyServiceTest {
    private static final ExecutionContext CONTEXT = new ExecutionContext("org-1", "tenant-1", "project-1", "team-1", "user-1");

    @Test
    void allowsOnlyTheContextOwnerOrSharedResourceScope() {
        MemoryPolicyService service = new MemoryPolicyService();
        assertThat(service.canRead(policy(MemoryPolicy.Scope.USER_PRIVATE, "user-1", null, null), CONTEXT)).isTrue();
        assertThat(service.canRead(policy(MemoryPolicy.Scope.USER_PRIVATE, "user-2", null, null), CONTEXT)).isFalse();
        assertThat(service.canRead(policy(MemoryPolicy.Scope.PROJECT_SHARED, null, "project-1", null), CONTEXT)).isTrue();
        assertThat(service.canRead(policy(MemoryPolicy.Scope.TEAM_SHARED, null, null, "team-2"), CONTEXT)).isFalse();
    }

    @Test
    void requiresConfirmedConsentAndRejectsRestrictedMemoryWithoutProjection() {
        MemoryPolicyService service = new MemoryPolicyService();
        MemoryPolicy candidate = new MemoryPolicy(MemoryPolicy.Scope.PROJECT_SHARED, "org-1", "tenant-1",
                "project-1", null, null, MemoryPolicy.Sensitivity.NORMAL, MemoryPolicy.Consent.CANDIDATE,
                Duration.ofHours(1));
        assertThatThrownBy(() -> service.requireReadable(candidate, CONTEXT)).isInstanceOf(IllegalArgumentException.class);

        MemoryPolicy restricted = new MemoryPolicy(MemoryPolicy.Scope.PROJECT_SHARED, "org-1", "tenant-1",
                "project-1", null, null, MemoryPolicy.Sensitivity.RESTRICTED, MemoryPolicy.Consent.CONFIRMED,
                Duration.ofHours(1));
        assertThatThrownBy(() -> service.requireReadable(restricted, CONTEXT)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void taskMemoryRequiresTheExplicitTaskIdentity() {
        UUID taskId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        MemoryPolicy task = new MemoryPolicy(MemoryPolicy.Scope.TASK, "org-1", "tenant-1", "project-1", null,
                null, taskId.toString(), MemoryPolicy.Sensitivity.NORMAL, MemoryPolicy.Consent.CONFIRMED,
                Duration.ofHours(1));
        MemoryPolicyService service = new MemoryPolicyService();

        assertThat(service.canRead(task, CONTEXT)).isFalse();
        assertThat(service.canRead(task, CONTEXT, taskId)).isTrue();
        assertThat(service.canRead(task, CONTEXT, UUID.randomUUID())).isFalse();
    }

    private static MemoryPolicy policy(MemoryPolicy.Scope scope, String subject, String project, String team) {
        return new MemoryPolicy(scope, "org-1", "tenant-1", project, team, subject,
                MemoryPolicy.Sensitivity.NORMAL, MemoryPolicy.Consent.CONFIRMED, Duration.ofHours(1));
    }
}
