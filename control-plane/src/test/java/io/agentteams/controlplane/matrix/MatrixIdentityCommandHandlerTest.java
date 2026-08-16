package io.agentteams.controlplane.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MatrixIdentityCommandHandlerTest {
    @Test
    void identityEntryIsAvailableWithoutBreakingFunctionalSenderEntry() {
        MatrixCommandHandler handler = (sender, command) -> sender;
        MatrixIdentity identity = new MatrixIdentity("@alice:example.org",
                new Principal("alice", new AuthorizationService.Scope("tenant-a", "project-a", "team-a"),
                        Set.of("task:read")));

        assertThat(handler.handle(identity, new MatrixCommand.Start("hello"))).isEqualTo("@alice:example.org");
    }
}
