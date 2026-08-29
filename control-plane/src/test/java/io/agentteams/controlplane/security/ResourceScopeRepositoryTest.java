package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ResourceScopeRepositoryTest {
    @AfterEach
    void clearPrincipal() { PrincipalContext.clear(); }

    @Test
    void visibilityIsFalseWithoutAnAuthenticatedPrincipal() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        assertThat(new ResourceScopeRepository(jdbc).visible("TEAM", UUID.randomUUID())).isFalse();
        verifyNoInteractions(jdbc);
    }
}
