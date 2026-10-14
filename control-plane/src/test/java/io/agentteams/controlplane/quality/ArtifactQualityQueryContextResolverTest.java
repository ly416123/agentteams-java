package io.agentteams.controlplane.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.ProjectScope;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

final class ArtifactQualityQueryContextResolverTest {
    @Test
    void resolvesReadScopeFromActiveProjectMembershipWithoutRequiringOrgOrTenantMembership() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        UUID projectId = UUID.randomUUID();
        ProjectScope scope = new ProjectScope("tenant-a", projectId, "project-a", "team-a");
        Principal principal = new Principal("quota-admin",
                new AuthorizationService.Scope("tenant-a", projectId.toString(), "team-a"), Set.of());
        ExecutionContext expected = new ExecutionContext("org-id", "tenant-id", projectId.toString(),
                "team-a", "quota-admin");
        when(jdbc.query(any(String.class), org.mockito.ArgumentMatchers.<RowMapper<ExecutionContext>>any(),
                eq(projectId.toString()), eq("tenant-a"), eq("quota-admin")))
                .thenReturn(List.of(expected));

        assertThat(new ArtifactQualityQueryContextResolver(jdbc).resolve(principal, scope)).isEqualTo(expected);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), org.mockito.ArgumentMatchers.<RowMapper<ExecutionContext>>any(),
                eq(projectId.toString()), eq("tenant-a"), eq("quota-admin"));
        assertThat(sql.getValue()).contains("project_member.subject = ?")
                .contains("project_member.status = 'ACTIVE'")
                .doesNotContain("organization_member")
                .doesNotContain("tenant_member");
    }
}
