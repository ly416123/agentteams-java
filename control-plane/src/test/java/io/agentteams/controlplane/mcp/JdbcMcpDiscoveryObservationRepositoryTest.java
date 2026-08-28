package io.agentteams.controlplane.mcp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.aop.framework.ProxyFactory;

@ExtendWith(MockitoExtension.class)
class JdbcMcpDiscoveryObservationRepositoryTest {
    private static final UUID SERVER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-28T00:00:00Z");

    @Mock
    private JdbcTemplate jdbc;

    @Test
    void recordUsesServerRevisionAndInstanceAsConflictKey() {
        JdbcMcpDiscoveryObservationRepository repository = new JdbcMcpDiscoveryObservationRepository(jdbc);
        McpDiscoveryObservation observation = new McpDiscoveryObservation(SERVER_ID, 3, "pod-a",
                "sha256:abc", true, "SUCCESS", OBSERVED_AT, OBSERVED_AT.plusSeconds(60));

        repository.record(observation);

        verify(jdbc).update(anyString(), eq(SERVER_ID), eq(3L), eq("pod-a"), eq("sha256:abc"),
                eq(true), eq("SUCCESS"), any(), any());
    }

    @Test
    void findReadsOnlyTheRequestedRevision() {
        McpDiscoveryObservation expected = new McpDiscoveryObservation(SERVER_ID, 3, "pod-a",
                "", false, "TIMEOUT", OBSERVED_AT, OBSERVED_AT.plusSeconds(60));
        when(jdbc.query(anyString(), any(RowMapper.class), eq(SERVER_ID), eq(3L)))
                .thenReturn(List.of(expected));
        JdbcMcpDiscoveryObservationRepository repository = new JdbcMcpDiscoveryObservationRepository(jdbc);

        List<McpDiscoveryObservation> result = repository.find(SERVER_ID, 3);

        assertThat(result).containsExactly(expected);
        verify(jdbc).query(org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("server_id = ?") && sql.contains("server_revision = ?")),
                any(RowMapper.class), eq(SERVER_ID), eq(3L));
    }

    @Test
    void repositoryCanBeClassProxiedBySpringInfrastructure() {
        JdbcMcpDiscoveryObservationRepository repository = new JdbcMcpDiscoveryObservationRepository(jdbc);
        ProxyFactory proxyFactory = new ProxyFactory(repository);
        proxyFactory.setProxyTargetClass(true);

        assertThatCode(proxyFactory::getProxy).doesNotThrowAnyException();
    }
}
