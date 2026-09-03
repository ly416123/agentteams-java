package io.agentteams.manager.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ManagerProjectScopeResolverTest {
    @Test
    void canonicalizesNameAndUuidInputsToTheSameProjectUuid() {
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                return List.of((T) "cccccccc-cccc-cccc-cccc-cccccccccccc");
            }
        };
        ManagerProjectScopeResolver resolver = new ManagerProjectScopeResolver(jdbc);
        ManagerPrincipal principal = new ManagerPrincipal("alice", "tenant-a", "project-a", "team-a", Set.of());

        assertThat(resolver.canonicalize(principal, "project-a").projectId())
                .isEqualTo("cccccccc-cccc-cccc-cccc-cccccccccccc");
        assertThat(resolver.canonicalize(principal,
                "cccccccc-cccc-cccc-cccc-cccccccccccc").projectId())
                .isEqualTo("cccccccc-cccc-cccc-cccc-cccccccccccc");
    }
}
