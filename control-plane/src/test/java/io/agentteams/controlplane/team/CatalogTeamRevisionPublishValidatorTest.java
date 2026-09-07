package io.agentteams.controlplane.team;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.agentspec.AgentSpecReferenceValidationResult;
import io.agentteams.controlplane.agentspec.AgentSpecReferenceValidator;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CatalogTeamRevisionPublishValidatorTest {
    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void checksRevisionMembersAgainstWorkerResourceScope() {
        PrincipalContext.set(new Principal("quota-admin",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of("team:write")));
        TeamRevisionRepository revisions = mock(TeamRevisionRepository.class);
        ResourceScopeRepository scopes = mock(ResourceScopeRepository.class);
        AgentSpecReferenceValidator references = mock(AgentSpecReferenceValidator.class);
        when(references.validate(any())).thenReturn(AgentSpecReferenceValidationResult.valid());

        UUID teamId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        TeamRevision revision = new TeamRevision(teamId, 1, workerId, "{}", "digest",
                TeamRevisionStatus.DRAFT, null, "quota-admin", Instant.now(), 0, List.of(workerId));

        new CatalogTeamRevisionPublishValidator(revisions, scopes, references).validate(revision);

        verify(scopes).requireVisible("TEAM", teamId);
        verify(scopes).requireVisible("WORKER", workerId);
        verify(scopes, never()).requireVisible(eq("AGENT"), eq(workerId));
        verify(revisions).validatePublish(revision);
    }
}
