package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import io.agentteams.contracts.v1.ServerMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcCommandEventStoreTest {

    @Test
    void allocatesPerAgentSequenceAndPersistsProtobufBytes() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        doReturn(7L).when(jdbc).queryForObject(anyString(), eq(Long.class), any(Object[].class));
        JdbcCommandEventStore store = new JdbcCommandEventStore(jdbc, fixedClock());

        SequencedCommand result = store.append("agent-1", ServerMessage.newBuilder()
                .setTaskAssigned(GatewayTestFixtures.assignment("other-agent", "command-7"))
                .build());

        assertThat(result.sequence()).isEqualTo(7);
        assertThat(result.message().getTaskAssigned().getMetadata().getAgentId()).isEqualTo("agent-1");
        assertThat(result.message().getTaskAssigned().getMetadata().getSequence()).isEqualTo(7);
        verify(jdbc).queryForObject(contains("gateway_agent_sequences"), eq(Long.class), any(Object[].class));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("gateway_commands"), args.capture());
        Object[] values = args.getValue();
        assertThat(values).contains("agent-1", 7L);
        assertThat(values).anyMatch(value -> value instanceof byte[] bytes
                && java.util.Arrays.equals(bytes, result.message().toByteArray()));
    }

    @Test
    void reusesExistingSequenceWhenAnOutboxEventIsDeliveredAgain() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        String eventId = UUID.randomUUID().toString();
        ServerMessage command = ServerMessage.newBuilder()
                .setTaskAssigned(GatewayTestFixtures.assignment("agent-1", eventId))
                .build();
        ServerMessage persisted = command.toBuilder().setTaskAssigned(command.getTaskAssigned().toBuilder()
                .setMetadata(command.getTaskAssigned().getMetadata().toBuilder()
                        .setAgentId("agent-1").setEventId(eventId).setSequence(4).build()).build()).build();
        doReturn(8L).when(jdbc).queryForObject(contains("gateway_agent_sequences"), eq(Long.class),
                any(Object[].class));
        org.mockito.Mockito.doThrow(new DuplicateKeyException("event already exists"))
                .when(jdbc).update(contains("gateway_commands"), any(Object[].class));
        doReturn(List.of(new SequencedCommand(4, persisted))).when(jdbc).query(
                argThat(sql -> sql.contains("WHERE agent_id = ? AND event_id = ?")),
                any(org.springframework.jdbc.core.RowMapper.class), eq("agent-1"), eq(eventId));

        SequencedCommand result = new JdbcCommandEventStore(jdbc, fixedClock()).append("agent-1", command);

        assertThat(result.sequence()).isEqualTo(4);
        assertThat(result.message()).isEqualTo(persisted);
    }

    @Test
    void replaysRowsAndUsesDurableDeliveryAndAckCursors() throws Exception {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        ServerMessage message = ServerMessage.newBuilder()
                .setTaskAssigned(GatewayTestFixtures.assignment("agent-1", "command-8"))
                .build();
        doReturn(List.of(new SequencedCommand(8, message)))
                .when(jdbc).query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), eq("agent-1"));
        doReturn(8L).when(jdbc).queryForObject(contains("gateway_ack_cursors"), eq(Long.class), eq("agent-1"));
        JdbcCommandEventStore store = new JdbcCommandEventStore(jdbc, fixedClock());
        UUID connectionId = UUID.randomUUID();

        assertThat(store.replayUnacknowledged("agent-1")).containsExactly(new SequencedCommand(8, message));
        store.markDelivered("agent-1", connectionId, 8);
        store.acknowledge("agent-1", 8);
        assertThat(store.lastAcknowledgedSequence("agent-1")).isEqualTo(8);

        verify(jdbc).query(contains("gateway_commands"), any(org.springframework.jdbc.core.RowMapper.class),
                eq("agent-1"));
        verify(jdbc).update(contains("gateway_command_deliveries"), any(Object[].class));
        verify(jdbc).update(contains("gateway_ack_cursors"), any(Object[].class));
    }

    @Test
    void acceptsOnlyASequenceDurablyDeliveredToTheConnection() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        doReturn(3L).when(jdbc).queryForObject(contains("MAX(sequence)"), eq(Long.class),
                any(Object[].class));
        doReturn(1L).when(jdbc).queryForObject(
                argThat(sql -> sql.contains("gateway_command_deliveries") && sql.contains("sequence = ?")),
                eq(Long.class), any(Object[].class));
        JdbcCommandEventStore store = new JdbcCommandEventStore(jdbc, fixedClock());
        UUID connectionId = UUID.randomUUID();

        AcknowledgementValidation accepted = store.validateAcknowledgement("agent-1", connectionId, 2);

        assertThat(accepted.accepted()).isTrue();
        assertThat(accepted.highestDeliveredSequence()).isEqualTo(3);
    }

    @Test
    void treatsMissingAcknowledgementCursorAsZero() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        org.mockito.Mockito.doThrow(new org.springframework.dao.EmptyResultDataAccessException(1))
                .when(jdbc).queryForObject(contains("gateway_ack_cursors"), eq(Long.class), eq("new-agent"));
        JdbcCommandEventStore store = new JdbcCommandEventStore(jdbc, fixedClock());

        assertThat(store.lastAcknowledgedSequence("new-agent")).isZero();
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC);
    }
}
