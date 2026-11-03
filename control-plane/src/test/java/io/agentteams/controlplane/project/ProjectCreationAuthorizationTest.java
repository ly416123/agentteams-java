package io.agentteams.controlplane.project;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;

import io.agentteams.controlplane.organization.ManagementAuthorizationService;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProjectCreationAuthorizationTest {
    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void requiresPlatformAdminBeforeWritingAnyProjectCreationState() {
        ProjectRepository repository = mock(ProjectRepository.class);
        ManagementAuthorizationService managementAuthorization = mock(ManagementAuthorizationService.class);
        doThrow(new AuthorizationException("platform administrator required"))
                .when(managementAuthorization).requirePlatformAdmin();
        ProjectAuthorizationService service = new ProjectAuthorizationService(repository, event -> { },
                managementAuthorization);
        PrincipalContext.set(new Principal("project-member",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of("project:create")));

        assertThatThrownBy(() -> service.createProject("create-key", "New Project"))
                .isInstanceOf(AuthorizationException.class);
        verify(managementAuthorization).requirePlatformAdmin();
        verifyNoInteractions(repository);
    }

    @Test
    void failsClosedWhenManagementAuthorizationIsNotWired() {
        ProjectRepository repository = mock(ProjectRepository.class);
        ProjectAuthorizationService service = new ProjectAuthorizationService(repository, event -> { });
        PrincipalContext.set(new Principal("project-member",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of("project:create")));

        assertThatThrownBy(() -> service.createProject("create-key", "New Project"))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("authorization is unavailable");
        verifyNoInteractions(repository);
    }
}
