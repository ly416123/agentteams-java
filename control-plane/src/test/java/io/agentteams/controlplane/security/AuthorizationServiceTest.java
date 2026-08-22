package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AuthorizationServiceTest {
    @Test
    void deniesMissingPermissionBeforeExternalMutation() {
        assertThatThrownBy(() -> new AuthorizationService().require("manager", Permission.TASK_CREATE,
                Set.of(Permission.TASK_READ.value()))).isInstanceOf(AuthorizationException.class);
    }

    @Test
    void deniesCrossTenantOrProjectScope() {
        AuthorizationService service = new AuthorizationService();
        AuthorizationService.Scope caller = new AuthorizationService.Scope("tenant-a", "project-a", "team-a");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.requireScope(caller,
                new AuthorizationService.Scope("tenant-b", "project-a", "team-a")))
                .isInstanceOf(AuthorizationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.requireScope(caller,
                new AuthorizationService.Scope("tenant-a", "project-b", "team-a")))
                .isInstanceOf(AuthorizationException.class);
    }

    @Test
    void requiresCompleteMatchingScopeOnJsonResources() {
        AuthorizationService service = new AuthorizationService();
        Principal principal = new Principal("alice",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of("task:create"));

        service.requireScope(principal,
                "{\"scope\":{\"tenant\":\"tenant-a\",\"project\":\"project-a\",\"team\":\"team-a\"}}");
        assertThatThrownBy(() -> service.requireScope(principal,
                "{\"scope\":{\"tenant\":\"tenant-b\",\"project\":\"project-a\",\"team\":\"team-a\"}}"))
                .isInstanceOf(AuthorizationException.class);
        assertThatThrownBy(() -> service.requireScope(principal, "{}"))
                .isInstanceOf(AuthorizationException.class);
    }
}
