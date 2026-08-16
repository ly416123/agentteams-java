package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PrincipalTest {
    @Test
    void copiesPermissionsToKeepPrincipalImmutable() {
        Set<String> permissions = new HashSet<>(Set.of("task:read"));
        Principal principal = new Principal("subject-1",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), permissions);

        permissions.add("task:create");

        assertThat(principal.permissions()).containsExactly("task:read");
        assertThatThrownBy(() -> principal.permissions().add("task:create"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
