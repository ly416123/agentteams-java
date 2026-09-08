package io.agentteams.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.agentteams.contracts.v1.AgentMessage;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.TaskAssigned;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GatewayRuntimeAdapterEventReportTest {

    static final class CapturingChannel implements AgentChannelPort {
        final List<AgentMessage> sent = new ArrayList<>();

        @Override public void send(AgentMessage message) { sent.add(message); }
    }

    private static TaskAssigned assignment(UUID taskId) {
        Instant now = Instant.now();
        return TaskAssigned.newBuilder()
                .setMetadata(EventMetadata.newBuilder()
                        .setTaskId(taskId.toString())
                        .setAttemptId(UUID.randomUUID().toString())
                        .setLeaseId(UUID.randomUUID().toString())
                        .setExpectedVersion(0)
                        .setOccurredAt(Timestamp.newBuilder().setSeconds(now.getEpochSecond())
                                .setNanos(now.getNano()).build()))
                .setLeaseExpiresAt(Timestamp.newBuilder().setSeconds(now.getEpochSecond() + 60).build())
                .setTaskType("qwenpaw")
                .setInputJson(ByteString.copyFromUtf8("{}"))
                .build();
    }

    private GatewayRuntimeAdapter adapter(CapturingChannel channel, int eventRateLimit) {
        FakeRuntime runtime = new FakeRuntime();
        runtime.start(new AgentRuntimeContext("qwenpaw", 4, Clock.systemUTC(), result -> { }, Map.of()));
        return new GatewayRuntimeAdapter("agent-1", channel, runtime,
                Clock.fixed(Instant.now(), ZoneOffset.UTC), null, eventRateLimit);
    }

    @Test
    void reportEventSendsActionWithWorkerLocalSequence() {
        CapturingChannel channel = new CapturingChannel();
        GatewayRuntimeAdapter adapter = adapter(channel, 0);
        UUID taskId = UUID.randomUUID();
        adapter.acceptAssignment(assignment(taskId));
        channel.sent.clear();

        adapter.reportEvent(taskId, "tool.called", "{\"tool\":\"web_search\"}");
        adapter.reportEvent(taskId, "tool.finished", "{\"tool\":\"web_search\",\"elapsedMs\":12,\"ok\":true}");

        assertEquals(2, channel.sent.size());
        var first = channel.sent.get(0).getTaskEventReport();
        assertEquals("tool.called", first.getEventType());
        assertEquals(1, first.getSequence());
        assertEquals("{\"tool\":\"web_search\"}", first.getPayload().toStringUtf8());
        assertEquals(taskId.toString(), first.getMetadata().getTaskId());
        assertEquals("agent-1", first.getMetadata().getAgentId());
        // 过程上报不得推进任务聚合版本：两次上报携带的 expectedVersion 保持不变，worker 序列 1→2 递增。
        assertEquals(2, channel.sent.get(1).getTaskEventReport().getSequence());
        assertEquals(channel.sent.get(0).getTaskEventReport().getMetadata().getExpectedVersion(),
                channel.sent.get(1).getTaskEventReport().getMetadata().getExpectedVersion());
    }

    @Test
    void reportEventDropsUnknownTaskAndOversizedPayload() {
        CapturingChannel channel = new CapturingChannel();
        GatewayRuntimeAdapter adapter = adapter(channel, 0);
        adapter.reportEvent(UUID.randomUUID(), "tool.called", "{}");
        UUID taskId = UUID.randomUUID();
        adapter.acceptAssignment(assignment(taskId));
        channel.sent.clear();
        adapter.reportEvent(taskId, "tool.called", "x".repeat(4097));
        assertTrue(channel.sent.isEmpty());
    }

    @Test
    void reportEventRespectsRateLimit() {
        CapturingChannel channel = new CapturingChannel();
        GatewayRuntimeAdapter adapter = adapter(channel, 1);
        UUID taskId = UUID.randomUUID();
        adapter.acceptAssignment(assignment(taskId));
        channel.sent.clear();
        adapter.reportEvent(taskId, "tool.called", "{}");
        adapter.reportEvent(taskId, "tool.called", "{}");
        assertEquals(1, channel.sent.size());
    }

    @Test
    void rateLimitBudgetIsPerTask() {
        CapturingChannel channel = new CapturingChannel();
        GatewayRuntimeAdapter adapter = adapter(channel, 1);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        adapter.acceptAssignment(assignment(first));
        adapter.acceptAssignment(assignment(second));
        channel.sent.clear();
        adapter.reportEvent(first, "tool.called", "{}");
        adapter.reportEvent(second, "tool.called", "{}");
        // 规格要求每任务滑动窗口：两个任务各自的预算互不挤占（worker 级共享窗口只会放行 1 条）。
        assertEquals(2, channel.sent.size());
    }
}
