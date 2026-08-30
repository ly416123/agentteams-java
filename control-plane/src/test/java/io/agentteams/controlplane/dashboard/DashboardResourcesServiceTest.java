package io.agentteams.controlplane.dashboard;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class DashboardResourcesServiceTest {
    @Mock private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        PrincipalContext.set(new Principal("alice",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of()));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    void rejectsMissingOrInactiveProjectMembershipBeforeAggregating() {
        when(jdbc.queryForObject(eq(DashboardResourcesService.ACTIVE_MEMBERSHIP_SQL), eq(Boolean.class),
                eq("tenant-a"), eq("project-a"), eq("project-a"), eq("alice"))).thenReturn(false);

        assertThatThrownBy(() -> new DashboardResourcesService(jdbc).summarize())
                .isInstanceOf(io.agentteams.controlplane.security.AuthorizationException.class)
                .hasMessage("active project membership required");
        verify(jdbc).queryForObject(eq(DashboardResourcesService.ACTIVE_MEMBERSHIP_SQL), eq(Boolean.class),
                eq("tenant-a"), eq("project-a"), eq("project-a"), eq("alice"));
        verifyNoMoreInteractions(jdbc);
    }
}
