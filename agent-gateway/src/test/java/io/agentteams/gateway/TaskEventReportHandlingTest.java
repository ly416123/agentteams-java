package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.agentteams.application.api.ExecutionEventPort;
import io.agentteams.application.api.TaskExecutionObservationPort;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.TaskEventReport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TaskEventReportHandlingTest {

    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID ATTEMPT_ID = UUID.randomUUID();
    private static final UUID LEASE_ID = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-09-08T00:00:00Z");

    private EventMetadata metadata() {
        return EventMetadata.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setAgentId("worker-1")
                .setTaskId(TASK_ID.toString())
                .setAttemptId(ATTEMPT_ID.toString())
                .setLeaseId(LEASE_ID.toString())
                .setOccurredAt(Timestamp.newBuilder().setSeconds(AT.getEpochSecond()).setNanos(AT.getNano()).build())
                .build();
    }

    private ConnectionRegistry.ConnectionSnapshot connection() {
        return new ConnectionRegistry.ConnectionSnapshot(UUID.randomUUID(), "worker-1", "qwenpaw", "0.4.0",
                "", "", "", Map.of(), AT, 0);
    }

    @Test
    void forwardsValidReportToExecutionEvents() {
        ExecutionEventPort events = mock(ExecutionEventPort.class);
        ControlPlaneGatewayApplicationHandler handler = new ControlPlaneGatewayApplicationHandler(events, clock());
        handler.taskEventReport(connection(), TaskEventReport.newBuilder()
                .setMetadata(metadata()).setSequence(3).setEventType("tool.called")
                .setPayload(ByteString.copyFromUtf8("{\"tool\":\"web_search\"}")).build());

        ArgumentCaptor<ExecutionEventPort.TaskEventReportCommand> commands =
                ArgumentCaptor.forClass(ExecutionEventPort.TaskEventReportCommand.class);
        verify(events).taskEventReport(eq(TASK_ID), commands.capture());
        assertThat(commands.getValue().eventType()).isEqualTo("tool.called");
        assertThat(commands.getValue().sequence()).isEqualTo(3);
        assertThat(commands.getValue().attemptId()).isEqualTo(ATTEMPT_ID);
    }

    @Test
    void dropsReportFromForeignAgentWithoutThrowing() {
        ExecutionEventPort events = mock(ExecutionEventPort.class);
        ControlPlaneGatewayApplicationHandler handler = new ControlPlaneGatewayApplicationHandler(events, clock());
        // 伪造 agent_id：归属不符必须丢弃（不抛 InvalidMessage，不威胁流）。
        handler.taskEventReport(connection(), TaskEventReport.newBuilder()
                .setMetadata(metadata().toBuilder().setAgentId("someone-else")).setEventType("tool.called")
                .build());
        verifyNoInteractions(events);
    }

    @Test
    void keepsStreamAliveWhenPublishingFails() {
        ExecutionEventPort events = mock(ExecutionEventPort.class);
        doThrow(new IllegalStateException("nats unavailable")).when(events)
                .taskEventReport(eq(TASK_ID), any());
        ControlPlaneGatewayApplicationHandler handler = new ControlPlaneGatewayApplicationHandler(events, clock());
        // NATS 发布失败不得沿 route() 传播关闭承载终态事件的 gRPC 流。
        handler.taskEventReport(connection(), TaskEventReport.newBuilder()
                .setMetadata(metadata()).setSequence(1).setEventType("tool.called")
                .setPayload(ByteString.copyFromUtf8("{}")).build());
    }

    @Test
    void dropsOversizedPayloadWithoutThrowing() {
        ExecutionEventPort events = mock(ExecutionEventPort.class);
        ControlPlaneGatewayApplicationHandler handler = new ControlPlaneGatewayApplicationHandler(events, clock());
        handler.taskEventReport(connection(), TaskEventReport.newBuilder()
                .setMetadata(metadata()).setEventType("tool.called")
                .setPayload(ByteString.copyFromUtf8("x".repeat(4097))).build());
        verifyNoInteractions(events);
    }

    @Test
    void countsDroppedTaskEventReportsByReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExecutionEventPort events = mock(ExecutionEventPort.class);
        doThrow(new IllegalStateException("nats unavailable")).when(events)
                .taskEventReport(eq(TASK_ID), any());
        ControlPlaneGatewayApplicationHandler handler = new ControlPlaneGatewayApplicationHandler(events,
                command -> { }, TaskExecutionObservationPort.noop(), clock(), new GatewayMetrics(registry));

        // 归属不符 → invalid；有效报告但发布失败 → publish_failed。
        handler.taskEventReport(connection(), TaskEventReport.newBuilder()
                .setMetadata(metadata().toBuilder().setAgentId("someone-else")).setEventType("tool.called")
                .build());
        handler.taskEventReport(connection(), TaskEventReport.newBuilder()
                .setMetadata(metadata()).setSequence(1).setEventType("tool.called")
                .setPayload(ByteString.copyFromUtf8("{}")).build());

        assertThat(registry.counter("agentteams.gateway.task.events.dropped", "reason", "invalid").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("agentteams.gateway.task.events.dropped", "reason", "publish_failed").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("agentteams.gateway.task.events.dropped", "reason", "unknown").count())
                .isEqualTo(0.0);
    }

    private Clock clock() {
        return Clock.fixed(AT, ZoneOffset.UTC);
    }
}
