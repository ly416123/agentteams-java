# 任务过程可见性一期 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [x]`）语法来跟踪进度。

**目标：** 对话创建的任务在 Console 详情页实时可见执行过程——QwenPaw SSE 中的白名单动作（工具调用）经 worker→gateway→NATS→控制面落入 `task_process_events`，前端主列时间线合并生命周期流与过程事件流，右栏新增信息面板（DAG、标签页、来源会话回链）。

**架构：** 新增 gRPC `TaskEventReport`（oneof 字段 11）贯穿既有上报链路：runtime 拦截 SSE 中间事件（白名单转换+配对计时）→ `GatewayRuntimeAdapter.reportEvent`（限速+4KB+worker 序列）→ gateway 校验归属后发布 `TASK_EVENT` envelope → 控制面二次校验（白名单+4KB+scope）落既有 `task_process_events` 表 → 既有 SSE 端点推给 Console。中间事件 best-effort（公理一），双层校验不信任上游（公理二），不做跨版本兼容（开发阶段全组件同步升级）。

**技术栈：** Java 21 + Maven 多模块、protobuf gRPC、NATS JetStream、PostgreSQL、React 18 + TypeScript + vitest、Python 3（MCP 脚本 unittest）。

**规格：** `docs/superpowers/specs/2026-09-08-task-process-visibility-design.md`

**构建与测试命令：**
- 单模块测试：`cd /Users/gecko/code/agentteams-java && mvn -q -pl <module> -am test`（模块名：contracts / application-contracts / runtime / agent-worker / agent-gateway / control-plane）
- Console 测试：`cd console && npx vitest run tests/features/<file>`
- MCP 脚本测试：`python3 scripts/test_agentteams_task_mcp.py`（unittest 风格，pytest 不可用）
- 提交规范：type 英文 + scope/subject 中文动宾短语，body 含背景/方案/影响/测试确认（见 chinese-commit-conventions 技能）

---

## 文件结构

| 文件 | 职责 | 动作 |
| --- | --- | --- |
| `contracts/src/main/proto/agent_channel.proto` | 线路协议：新增 TaskEventReport 消息与 oneof 字段 11 | 修改 |
| `application-contracts/.../ExecutionEventPort.java` | 应用边界：新增 TaskEventReportCommand + taskEventReport 方法 | 修改 |
| `application-contracts/.../ExecutionEventEnvelope.java` | Gateway→控制面 envelope：新增 TASK_EVENT 类型 | 修改 |
| `runtime/.../RuntimeEvent.java` | 白名单中间事件值对象（新建） | 创建 |
| `runtime/.../RuntimeEventSink.java` | 中间事件上报 SPI（新建，可空） | 创建 |
| `runtime/.../RuntimeEventRateLimiter.java` | 滑动窗口限速器（新建） | 创建 |
| `runtime/.../AgentRuntime.java` | AgentRuntime SPI：default setEventSink | 修改 |
| `runtime/.../QwenPawProcessPort.java` | 进程端口 SPI：default setEventSink | 修改 |
| `runtime/.../QwenPawRuntime.java` | 把 sink 转发给进程端口 | 修改 |
| `runtime/.../QwenPawHttpRuntimePort.java` | SSE 中间事件白名单转换 + 配对计时 | 修改 |
| `runtime/.../GatewayRuntimeAdapter.java` | reportEvent：限速 + 4KB + worker 序列 + 发送 | 修改 |
| `agent-worker/.../QwenPawWorker.java` | WorkerConfiguration 加 eventRateLimit；构造器接线 sink | 修改 |
| `agent-worker/.../WorkerRuntimeRouter.java` | setEventSink 委托 qwenPaw | 修改 |
| `agent-gateway/.../GatewayApplicationHandler.java` | 应用缝：default taskEventReport | 修改 |
| `agent-gateway/.../InboundEventHandler.java` | oneof 路由：metadata + route 两个 switch | 修改 |
| `agent-gateway/.../ControlPlaneGatewayApplicationHandler.java` | 归属校验，违规丢弃不抛（公理一） | 修改 |
| `agent-gateway/.../NatsExecutionEventPublisher.java` | 发布 TASK_EVENT envelope | 修改 |
| `control-plane/.../outbox/NatsExecutionEventConsumer.java` | TASK_EVENT 分发分支 | 修改 |
| `control-plane/.../application/ControlPlaneExecutionEventAdapter.java` | ExecutionEventPort 控制面实现：转发 observations | 修改 |
| `control-plane/.../application/ControlPlaneTaskExecutionObservationAdapter.java` | observed()：二次校验 + 落库 | 修改 |
| `control-plane/.../api/TaskController.java` | TaskResponse 加 source（来源会话回链数据） | 修改 |
| `scripts/agentteams-task-mcp.py` | create_task 加 source 参数写入 inputJson | 修改 |
| `scripts/test_agentteams_task_mcp.py` | source 契约测试 | 修改 |
| `console/src/api/types.ts` | Task 类型加 source | 修改 |
| `console/src/features/tasks/TaskDag.tsx` | SVG 分层 DAG（新建） | 创建 |
| `console/src/features/tasks/TaskInfoPanel.tsx` | 右栏信息面板（新建） | 创建 |
| `console/src/features/tasks/TaskDetailPage.tsx` | grid 重排 + 双流合并时间线 | 修改 |
| `console/src/styles/global.css` | detail-layout / 右栏样式 | 修改 |
| `console/tests/features/TaskInfoPanel.test.tsx` | 面板与 DAG 测试（新建） | 创建 |

既有实现要点（写代码前先读）：`ControlPlaneTaskExecutionObservationAdapter.recordProcess` 是落库模板（contextForTask → ensureRun → nextSequence → TaskProcessEvent → append）；`NatsExecutionEventConsumer.process` 的类型分发在 L295-303；`QwenPawHttpRuntimePort.processResponse` 的 SSE 循环在 L273-321。

---

### 任务 1：contracts — proto 新增 TaskEventReport

**文件：**
- 修改：`contracts/src/main/proto/agent_channel.proto`（TaskProgress 之后 L120 附近 + AgentMessage oneof L237-250）

- [x] **步骤 1：在 `message TaskProgress { ... }` 之后（L120 `}` 与 L122 `message TaskHeartbeat` 之间）插入消息**

```protobuf
// Worker-reported, best-effort action stream entry (e.g. tool calls). It never
// carries prompts, chain-of-thought, or task state transitions, and must not
// be retried by transport machinery: dedup happens on metadata.event_id at the
// Control Plane.
message TaskEventReport {
  EventMetadata metadata = 1;
  // Worker-local per-task ordering hint. The Control Plane assigns its own
  // storage sequence and uses it as the SSE cursor.
  uint32 sequence = 2;
  string event_type = 3;
  bytes payload = 4;
}
```

- [x] **步骤 2：在 `AgentMessage` 的 oneof 中（`AgentHeartbeat agent_heartbeat = 10;` 之后）加字段**

```protobuf
    TaskEventReport task_event_report = 11;
```

- [x] **步骤 3：编译生成并确认**

运行：`cd /Users/gecko/code/agentteams-java && mvn -q -pl contracts install -DskipTests`
预期：BUILD SUCCESS。再确认生成类存在：
`ls contracts/target/generated-sources/protobuf/java/io/agentteams/contracts/v1/ | grep TaskEventReport`
预期：输出 `TaskEventReport.java`、`TaskEventReportOrBuilder.java`

- [x] **步骤 4：Commit**

```bash
git add contracts/src/main/proto/agent_channel.proto
git commit -m "feat(契约): 添加任务过程上报消息定义

对话创建的任务需要在上报链路中携带执行动作流水（工具调用）。
在 AgentChannel 协议中新增 TaskEventReport 消息并挂入 AgentMessage
oneof 字段 11：metadata 沿用既有事件元数据，sequence 为 worker 本地
排序提示，event_type/payload 为白名单动作事实。payload 上限 4096
字节由出口与入口双层校验。

影响范围：contracts（生成代码），下游模块后续任务接线
测试确认：mvn -pl contracts install 编译通过，生成类存在"
```

---

### 任务 2：application-contracts — TaskEventReportCommand 与 TASK_EVENT envelope

**文件：**
- 修改：`application-contracts/src/main/java/io/agentteams/application/api/ExecutionEventPort.java`
- 修改：`application-contracts/src/main/java/io/agentteams/application/api/ExecutionEventEnvelope.java`
- 测试：`application-contracts/src/test/java/io/agentteams/application/api/ExecutionEventEnvelopeTest.java`（新建）

- [x] **步骤 1：编写失败的测试**

创建 `application-contracts/src/test/java/io/agentteams/application/api/ExecutionEventEnvelopeTest.java`：

```java
package io.agentteams.application.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExecutionEventEnvelopeTest {

    private ExecutionEventPort.TaskEventReportCommand command(String payload) {
        return new ExecutionEventPort.TaskEventReportCommand(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Instant.now(), "worker-1", "tool.called", payload, 7, "corr-1");
    }

    @Test
    void taskEventEnvelopeCarriesOnlyTaskEventReport() {
        UUID taskId = UUID.randomUUID();
        ExecutionEventEnvelope envelope = ExecutionEventEnvelope.taskEvent(taskId, command("{\"tool\":\"web_search\"}"));
        assertEquals("TASK_EVENT", envelope.type());
        assertEquals(taskId, envelope.taskId());
        assertEquals("tool.called", envelope.taskEventReport().eventType());
        assertEquals(7, envelope.taskEventReport().sequence());
    }

    @Test
    void taskEventCommandRejectsOversizedPayload() {
        assertThrows(IllegalArgumentException.class, () -> command("x".repeat(4097)));
    }

    @Test
    void taskEventCommandRejectsBlankEventType() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionEventPort.TaskEventReportCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
                "worker-1", " ", "{}", 0, "corr-1"));
    }
}
```

- [x] **步骤 2：运行验证失败**

运行：`mvn -q -pl application-contracts -am test -Dtest=ExecutionEventEnvelopeTest`
预期：编译错误 `cannot find symbol: method taskEvent` / `TaskEventReportCommand`（红）

- [x] **步骤 3：实现 ExecutionEventPort 的命令与端口方法**

在 `ExecutionEventPort.java` 中，`rejectUnaccepted` 声明之后加端口方法，`ArtifactReference` record 之前加命令 record：

```java
    /** Publishes a best-effort worker action report without touching task state. */
    void taskEventReport(UUID taskId, TaskEventReportCommand command);

    int MAX_EVENT_PAYLOAD_BYTES = 4096;

    /**
     * Best-effort middle-of-execution action report (tool calls etc.). It is
     * never a task state transition: the sequence is worker-local ordering
     * context and deduplication happens on eventId at the Control Plane.
     */
    record TaskEventReportCommand(UUID eventId, UUID attemptId, UUID leaseId, Instant occurredAt,
            String agentId, String eventType, String payloadJson, long sequence, String correlationId) {
        public TaskEventReportCommand {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(attemptId, "attemptId");
            Objects.requireNonNull(leaseId, "leaseId");
            Objects.requireNonNull(occurredAt, "occurredAt");
            requireText(agentId, "agentId");
            requireText(eventType, "eventType");
            if (sequence < 0) {
                throw new IllegalArgumentException("sequence must not be negative");
            }
            if (payloadJson != null && payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    > MAX_EVENT_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("payload exceeds " + MAX_EVENT_PAYLOAD_BYTES + " bytes");
            }
            TraceContext context = new TraceContext(correlationId, "", "");
            correlationId = context.correlationId();
        }
    }
```

- [x] **步骤 4：实现 ExecutionEventEnvelope 的 TASK_EVENT 类型**

四处修改 `ExecutionEventEnvelope.java`：

① record 组件列表在 `ExecutionEventPort.RejectionCommand rejection,` 之后、`List<...> artifacts,` 之前插入 `ExecutionEventPort.TaskEventReportCommand taskEventReport,`；同步更新两个次构造器的委托参数（在 `null` 对应 rejection 的位置后面多传一个 `null`）。

② 紧凑构造器的不变式区（`REJECTION` 校验之后）追加：

```java
        if ("TASK_EVENT".equals(type) == (taskEventReport == null)) {
            throw new IllegalArgumentException("TASK_EVENT envelope must contain only taskEventReport");
        }
```

③ 静态工厂（`rejection(...)` 之后）：

```java
    public static ExecutionEventEnvelope taskEvent(UUID taskId,
            ExecutionEventPort.TaskEventReportCommand command) {
        return new ExecutionEventEnvelope(1, "TASK_EVENT", taskId, null, null, null,
                Objects.requireNonNull(command, "command"), List.of(), command.correlationId(), "", "");
    }
```

- [x] **步骤 5：运行验证通过**

运行：`mvn -q -pl application-contracts -am test`
预期：PASS（含既有测试，说明既有 TASK/LEASE_RENEWAL/REJECTION 构造点未被破坏——次构造器已同步补参）

- [x] **步骤 6：Commit**

```bash
git add application-contracts/src/main/java/io/agentteams/application/api/ExecutionEventPort.java \
  application-contracts/src/main/java/io/agentteams/application/api/ExecutionEventEnvelope.java \
  application-contracts/src/test/java/io/agentteams/application/api/ExecutionEventEnvelopeTest.java
git commit -m "feat(契约): 添加任务过程上报命令与 TASK_EVENT 信封

过程上报与任务状态迁移严格分离：TaskEventReportCommand 携带事件
事实（event_type/payload/worker 序列），payload 上限 4096 字节在
命令构造时强制；ExecutionEventEnvelope 新增 TASK_EVENT 类型并沿用
单一命令组件不变式。

影响范围：application-contracts；gateway 发布与控制面消费在后续任务接线
测试确认：mvn -pl application-contracts -am test 全绿"
```

---

### 任务 3：runtime — 事件 SPI（RuntimeEvent / RuntimeEventSink / 限速器）

**文件：**
- 创建：`runtime/src/main/java/io/agentteams/runtime/RuntimeEvent.java`
- 创建：`runtime/src/main/java/io/agentteams/runtime/RuntimeEventSink.java`
- 创建：`runtime/src/main/java/io/agentteams/runtime/RuntimeEventRateLimiter.java`
- 修改：`runtime/src/main/java/io/agentteams/runtime/AgentRuntime.java`（default setEventSink）
- 修改：`runtime/src/main/java/io/agentteams/runtime/QwenPawProcessPort.java`（default setEventSink）
- 修改：`runtime/src/main/java/io/agentteams/runtime/QwenPawRuntime.java`（存储并转发）
- 测试：`runtime/src/test/java/io/agentteams/runtime/RuntimeEventRateLimiterTest.java`（新建）

- [x] **步骤 1：编写失败的限速器测试**

创建 `runtime/src/test/java/io/agentteams/runtime/RuntimeEventRateLimiterTest.java`：

```java
package io.agentteams.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RuntimeEventRateLimiterTest {

    @Test
    void allowsUpToLimitInsideWindow() {
        MutableClock clock = new MutableClock();
        RuntimeEventRateLimiter limiter = new RuntimeEventRateLimiter(3, Duration.ofSeconds(1), clock);
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void freesSlotsWhenWindowSlides() {
        MutableClock clock = new MutableClock();
        RuntimeEventRateLimiter limiter = new RuntimeEventRateLimiter(1, Duration.ofSeconds(1), clock);
        assertTrue(limiter.tryAcquire());
        clock.advanceMillis(1001);
        assertTrue(limiter.tryAcquire());
    }

    /** 手动推进的测试时钟，避免 Thread.sleep。 */
    static final class MutableClock extends Clock {
        private long millis;

        void advanceMillis(long delta) {
            millis += delta;
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
    }
}
```

- [x] **步骤 2：运行验证失败**

运行：`mvn -q -pl runtime -am test -Dtest=RuntimeEventRateLimiterTest`
预期：编译错误 `cannot find symbol: class RuntimeEventRateLimiter`（红）

- [x] **步骤 3：实现三个新类型与两处 SPI 转发**

创建 `RuntimeEventRateLimiter.java`：

```java
package io.agentteams.runtime;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** Sliding-window rate limiter used to shed best-effort event reporting. */
public final class RuntimeEventRateLimiter {
    private final int limit;
    private final long windowMillis;
    private final Clock clock;
    private final Deque<Long> timestamps = new ArrayDeque<>();

    public RuntimeEventRateLimiter(int limit, Duration window, Clock clock) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
        this.windowMillis = Objects.requireNonNull(window, "window").toMillis();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Returns true when the caller may emit one more event inside the window. */
    public synchronized boolean tryAcquire() {
        long now = clock.millis();
        long windowStart = now - windowMillis;
        while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
            timestamps.pollFirst();
        }
        if (timestamps.size() >= limit) {
            return false;
        }
        timestamps.addLast(now);
        return true;
    }
}
```

创建 `RuntimeEvent.java`：

```java
package io.agentteams.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A whitelisted middle-of-execution action observed from a runtime stream. */
public record RuntimeEvent(UUID taskId, String eventType, String payloadJson, Instant occurredAt) {
    public RuntimeEvent {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(eventType, "eventType");
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        payloadJson = payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson;
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
```

创建 `RuntimeEventSink.java`：

```java
package io.agentteams.runtime;

/**
 * Optional sink for whitelisted middle-of-execution runtime events.
 * Implementations must be cheap and failure-tolerant: runtime code invokes
 * the sink on its SSE reader thread and treats any failure as droppable.
 */
@FunctionalInterface
public interface RuntimeEventSink {
    void accept(RuntimeEvent event);
}
```

`AgentRuntime.java` 在 `default void applyConfig(...)` 之后加：

```java
    /**
     * Optional hook to receive whitelisted middle-of-execution events.
     * Runtimes without event reporting simply keep the default no-op.
     */
    default void setEventSink(RuntimeEventSink eventSink) {
    }
```

`QwenPawProcessPort.java` 在 `applyConfig` default 方法之后加：

```java
    /** Optional middle-event reporting hook; ports without a sink stay silent. */
    default void setEventSink(RuntimeEventSink eventSink) {
    }
```

`QwenPawRuntime.java`：字段区（`private volatile RuntimeConfigSnapshot activeConfiguration;` 之后）加
`private volatile RuntimeEventSink eventSink;`；`start(...)` 内 `process.start(...)` 之前加一行
`process.setEventSink(eventSink);`；`stop()` 的 finally 中加 `eventSink = null;`；类尾部加 override：

```java
    @Override
    public void setEventSink(RuntimeEventSink eventSink) {
        this.eventSink = eventSink;
    }
```

- [x] **步骤 4：运行验证通过**

运行：`mvn -q -pl runtime -am test`
预期：PASS（既有 runtime 测试全绿，default 方法零破坏）

- [x] **步骤 5：Commit**

```bash
git add runtime/src/main/java/io/agentteams/runtime/RuntimeEvent.java \
  runtime/src/main/java/io/agentteams/runtime/RuntimeEventSink.java \
  runtime/src/main/java/io/agentteams/runtime/RuntimeEventRateLimiter.java \
  runtime/src/main/java/io/agentteams/runtime/AgentRuntime.java \
  runtime/src/main/java/io/agentteams/runtime/QwenPawProcessPort.java \
  runtime/src/main/java/io/agentteams/runtime/QwenPawRuntime.java \
  runtime/src/test/java/io/agentteams/runtime/RuntimeEventRateLimiterTest.java
git commit -m "feat(运行时): 添加过程事件上报 SPI 与滑动窗口限速器

过程上报采用可空 SPI：RuntimeEventSink 通过 AgentRuntime 与
QwenPawProcessPort 的 default 方法逐层注入，未接线的运行时保持
静默；RuntimeEventRateLimiter 以滑动窗口为出口限速提供依据。

影响范围：runtime
测试确认：mvn -pl runtime -am test 全绿（含新增限速器用例）"
```

---

### 任务 4：runtime — QwenPawHttpRuntimePort 白名单转换与配对计时

**文件：**
- 修改：`runtime/src/main/java/io/agentteams/runtime/QwenPawHttpRuntimePort.java`
- 测试：`runtime/src/test/java/io/agentteams/runtime/QwenPawHttpRuntimeEventTest.java`（新建，自包含）

- [x] **步骤 1：编写失败的测试**

创建 `runtime/src/test/java/io/agentteams/runtime/QwenPawHttpRuntimeEventTest.java`：

```java
package io.agentteams.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QwenPawHttpRuntimeEventTest {

    private HttpServer server;
    private QwenPawHttpRuntimePort port;
    private final List<RuntimeEvent> events = new CopyOnWriteArrayList<>();
    private final CountDownLatch terminal = new CountDownLatch(1);

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/console/chat", exchange -> {
            String body = String.join("\n", List.of(
                    "data: {\"type\":\"tool.started\",\"tool\":\"web_search\"}",
                    "",
                    "data: {\"type\":\"reasoning\",\"text\":\"internal thought\"}",
                    "",
                    "data: {\"type\":\"plugin_call_output\",\"tool\":\"web_search\",\"status\":\"success\"}",
                    "",
                    "data: {\"object\":\"response\",\"status\":\"completed\",\"output\":\"done\"}",
                    "",
                    "data: [DONE]",
                    ""));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        port = new QwenPawHttpRuntimePort(new QwenPawHttpRuntimeConfiguration(
                URI.create("http://localhost:" + server.getAddress().getPort()), "agent-1", null,
                Duration.ofSeconds(2), "user-1", "console", "/api/models/active", false));
        port.start(new AgentRuntimeContext("qwenpaw", 1, Clock.systemUTC(), result -> { }, Map.of()),
                result -> terminal.countDown());
        port.setEventSink(events::add);
    }

    @AfterEach
    void stop() {
        port.stop();
        server.stop(0);
    }

    @Test
    void reportsWhitelistedToolEventsAndDropsReasoning() throws Exception {
        port.submit(new RuntimeTask(UUID.randomUUID(), "qwenpaw", "{\"prompt\":\"hi\"}", Map.of()));
        assertTrue(terminal.await(5, TimeUnit.SECONDS));
        // reasoning 被白名单丢弃；tool.started→tool.called、plugin_call_output→tool.finished。
        assertEquals(List.of("tool.called", "tool.finished"),
                events.stream().map(RuntimeEvent::eventType).toList());
        RuntimeEvent called = events.get(0);
        assertTrue(called.payloadJson().contains("\"tool\":\"web_search\""));
        RuntimeEvent finished = events.get(1);
        assertTrue(finished.payloadJson().contains("\"ok\":true"));
        assertTrue(finished.payloadJson().contains("\"elapsedMs\":"));
    }

    @Test
    void staysSilentWithoutSink() throws Exception {
        port.setEventSink(null);
        port.submit(new RuntimeTask(UUID.randomUUID(), "qwenpaw", "{\"prompt\":\"hi\"}", Map.of()));
        assertTrue(terminal.await(5, TimeUnit.SECONDS));
        assertEquals(0, events.size());
    }
}
```

- [x] **步骤 2：运行验证失败**

运行：`mvn -q -pl runtime -am test -Dtest=QwenPawHttpRuntimeEventTest`
预期：FAIL——第一个用例 events 为空（白名单转换尚未实现）

- [x] **步骤 3：实现白名单转换与配对计时**

修改 `QwenPawHttpRuntimePort.java`：

① import 区加 `import java.time.Duration;` 与 `import io.agentteams.runtime.RuntimeEvent;`（同包无需后者，只加 Duration）。

② 字段区（`requests` 声明之后）加：

```java
    private volatile RuntimeEventSink eventSink;
    /** tool 名 → tool.started 时刻，用于在同一任务内配对计算 elapsedMs。 */
    private final Map<UUID, Map<String, Instant>> toolStarts = new ConcurrentHashMap<>();
```

③ 类内加 override 与私有方法（放在 `stop()` 之后）：

```java
    @Override
    public void setEventSink(RuntimeEventSink eventSink) {
        this.eventSink = eventSink;
    }

    /** 公理一：中间事件 best-effort，回调失败绝不影响 SSE 主循环。 */
    private void reportMiddleEvent(UUID taskId, CharSequence data) {
        RuntimeEventSink sink = eventSink;
        if (sink == null || data.isEmpty() || "[DONE]".contentEquals(data)) {
            return;
        }
        try {
            RuntimeEvent event = runtimeEvent(taskId, objectMapper.readTree(data.toString()));
            if (event != null) {
                try {
                    sink.accept(event);
                } catch (RuntimeException ignored) {
                    // 上报方失败按丢弃处理。
                }
            }
        } catch (IOException ignored) {
            // 非 JSON 数据由 parseEvent 的终态语义处理。
        }
    }

    /** 白名单映射：tool.started→tool.called、plugin_call_output→tool.finished，其余（含 reasoning/message.delta）丢弃。 */
    private RuntimeEvent runtimeEvent(UUID taskId, JsonNode event) {
        String type = event.path("type").asText("");
        if ("tool.started".equals(type)) {
            String tool = event.path("tool").asText("");
            if (tool.isBlank()) {
                return null;
            }
            toolStarts.computeIfAbsent(taskId, ignored -> new ConcurrentHashMap<>()).put(tool, now());
            ObjectNode payload = objectMapper.createObjectNode().put("tool", tool);
            return new RuntimeEvent(taskId, "tool.called", payload.toString(), now());
        }
        if ("plugin_call_output".equals(type)) {
            String tool = event.path("tool").asText("");
            if (tool.isBlank()) {
                return null;
            }
            Instant startedAt = toolStarts.computeIfAbsent(taskId, ignored -> new ConcurrentHashMap<>())
                    .remove(tool);
            long elapsedMs = startedAt == null ? 0
                    : Math.max(0, Duration.between(startedAt, now()).toMillis());
            boolean ok = !"error".equals(event.path("status").asText("success")) && !event.has("error");
            ObjectNode payload = objectMapper.createObjectNode().put("tool", tool)
                    .put("elapsedMs", elapsedMs).put("ok", ok);
            return new RuntimeEvent(taskId, "tool.finished", payload.toString(), now());
        }
        return null;
    }
```

④ `processResponse` 中两处 `SseEvent event = parseEvent(data);` 之后、`data.setLength(0);` 之前各加一行
`reportMiddleEvent(task.id(), data);`（循环内一处 + 流结束尾部一处；此时 task 形参在作用域内）。

⑤ `publish(...)` 中 `requests.remove(task.id(), handle)` 成功分支加 `toolStarts.remove(task.id());`（终态后清理配对表）。

- [x] **步骤 4：运行验证通过**

运行：`mvn -q -pl runtime -am test`
预期：PASS（新用例 + 既有 QwenPawHttpRuntimePortTest 全绿——终态语义未被改动）

- [x] **步骤 5：Commit**

```bash
git add runtime/src/main/java/io/agentteams/runtime/QwenPawHttpRuntimePort.java \
  runtime/src/test/java/io/agentteams/runtime/QwenPawHttpRuntimeEventTest.java
git commit -m "feat(运行时): 拦截 QwenPaw SSE 中间事件并按白名单转换

在既有 SSE 读取循环上以旁路方式拦截中间事件：tool.started 映射为
tool.called，plugin_call_output 按 tool 名配对计时映射为
tool.finished（含 elapsedMs 与 ok），reasoning/message.delta 等其余
事件显式丢弃。旁路回调失败按丢弃处理，终态语义与既有路径零改动。

影响范围：runtime（QwenPawHttpRuntimePort）
测试确认：mvn -pl runtime -am test 全绿（新增白名单/静默两用例）"
```

---

### 任务 5：runtime — GatewayRuntimeAdapter.reportEvent（限速 + 4KB + worker 序列）

**文件：**
- 修改：`runtime/src/main/java/io/agentteams/runtime/GatewayRuntimeAdapter.java`
- 测试：`runtime/src/test/java/io/agentteams/runtime/GatewayRuntimeAdapterEventReportTest.java`（新建，自包含）

- [x] **步骤 1：编写失败的测试**

创建 `runtime/src/test/java/io/agentteams/runtime/GatewayRuntimeAdapterEventReportTest.java`：

```java
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

    @Test
    void reportEventSendsActionWithWorkerLocalSequence() {
        CapturingChannel channel = new CapturingChannel();
        GatewayRuntimeAdapter adapter = new GatewayRuntimeAdapter("agent-1", channel, new FakeRuntime(),
                Clock.fixed(Instant.now(), ZoneOffset.UTC), null, 0);
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
        // 过程上报不得推进任务聚合版本。
        assertEquals(1, channel.sent.get(1).getTaskEventReport().getSequence());
        assertEquals(0, channel.sent.get(0).getMetadata().getExpectedVersion());
    }

    @Test
    void reportEventDropsUnknownTaskAndOversizedPayload() {
        CapturingChannel channel = new CapturingChannel();
        GatewayRuntimeAdapter adapter = new GatewayRuntimeAdapter("agent-1", channel, new FakeRuntime(),
                Clock.fixed(Instant.now(), ZoneOffset.UTC), null, 0);
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
        GatewayRuntimeAdapter adapter = new GatewayRuntimeAdapter("agent-1", channel, new FakeRuntime(),
                Clock.fixed(Instant.now(), ZoneOffset.UTC), null, 1);
        UUID taskId = UUID.randomUUID();
        adapter.acceptAssignment(assignment(taskId));
        channel.sent.clear();
        adapter.reportEvent(taskId, "tool.called", "{}");
        adapter.reportEvent(taskId, "tool.called", "{}");
        assertEquals(1, channel.sent.size());
    }
}
```

- [x] **步骤 2：运行验证失败**

运行：`mvn -q -pl runtime -am test -Dtest=GatewayRuntimeAdapterEventReportTest`
预期：编译错误 `cannot find symbol: method reportEvent` / 构造器缺少 6 参形式（红）

- [x] **步骤 3：实现 reportEvent 与限速接线**

修改 `GatewayRuntimeAdapter.java`：

① 字段区（`assignments` 之后）加：

```java
    /** null 表示不限速；worker 按配置传入（默认 30/s）。 */
    private final RuntimeEventRateLimiter eventLimiter;
```

② 构造器：保留 4 参与 5 参构造，均委托到新 6 参构造（`eventRateLimit <= 0` 表示不限速）：

```java
    /** 5 参构造保持既有签名；eventRateLimit 走默认不限。 */
    public GatewayRuntimeAdapter(String agentId, AgentChannelPort channel, AgentRuntime runtime, Clock clock,
            RuntimeArtifactUploadPort uploads) {
        this(agentId, channel, runtime, clock, uploads, 0);
    }

    public GatewayRuntimeAdapter(String agentId, AgentChannelPort channel, AgentRuntime runtime, Clock clock,
            RuntimeArtifactUploadPort uploads, int eventRateLimit) {
        if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("agentId must not be blank");
        this.agentId = agentId;
        this.channel = Objects.requireNonNull(channel, "channel");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.uploads = uploads;
        this.eventLimiter = eventRateLimit > 0
                ? new RuntimeEventRateLimiter(eventRateLimit, java.time.Duration.ofSeconds(1), clock) : null;
    }
```

③ `AssignmentContext` 内加序列计数（放在 `advanceVersion()` 之后）：

```java
        private long eventSequence;

        private synchronized long nextEventSequence() {
            eventSequence = Math.addExact(eventSequence, 1);
            return eventSequence;
        }
```

④ 公开方法（放在 `heartbeat(...)` 之后）：

```java
    /**
     * Reports a whitelisted middle-of-execution action for the given task.
     * 公理一：best effort——未知任务、限速超额、超大载荷一律丢弃，绝不
     * 威胁任务赋值、聚合版本或 gRPC 通道；因此也不推进 expectedVersion。
     */
    public void reportEvent(UUID taskId, String eventType, String payloadJson) {
        if (eventType == null || eventType.isBlank()) {
            return;
        }
        AssignmentContext context = assignments.get(taskId);
        if (context == null) {
            return;
        }
        byte[] payload = payloadJson == null ? new byte[0] : payloadJson.getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_EVENT_PAYLOAD_BYTES) {
            return;
        }
        if (eventLimiter != null && !eventLimiter.tryAcquire()) {
            return;
        }
        channel.send(AgentMessage.newBuilder().setTaskEventReport(
                io.agentteams.contracts.v1.TaskEventReport.newBuilder()
                        .setMetadata(metadata(taskId, context))
                        .setSequence((int) context.nextEventSequence())
                        .setEventType(eventType)
                        .setPayload(ByteString.copyFrom(payload))
                        .build()).build());
    }
```

⑤ 类顶部常量区加 `private static final int MAX_EVENT_PAYLOAD_BYTES = 4096;`（`metadata(...)` helper 已设置 eventId/agentId/taskId/expectedVersion/occurredAt，直接复用，无需 advanceVersion）。

- [x] **步骤 4：运行验证通过**

运行：`mvn -q -pl runtime -am test`
预期：PASS（新 3 用例 + 既有 GatewayRuntimeAdapterTest 系列全绿）

- [x] **步骤 5：Commit**

```bash
git add runtime/src/main/java/io/agentteams/runtime/GatewayRuntimeAdapter.java \
  runtime/src/test/java/io/agentteams/runtime/GatewayRuntimeAdapterEventReportTest.java
git commit -m "feat(运行时): 适配器新增过程事件上报出口

GatewayRuntimeAdapter.reportEvent 作为 worker 出口闸门：未分配任务
丢弃、载荷超 4096 字节丢弃、滑动窗口限速超额丢弃，并以 assignment
上下文构造 TaskEventReport（worker 本地序列自增），不推进任务聚合
版本，保持中间事件与终态可靠性严格隔离。

影响范围：runtime（GatewayRuntimeAdapter）
测试确认：mvn -pl runtime -am test 全绿（发送/丢弃/限速三用例）"
```

---

### 任务 6：agent-worker — 配置限速环境变量并接线事件汇

**文件：**
- 修改：`agent-worker/src/main/java/io/agentteams/worker/QwenPawWorker.java`（record `WorkerConfiguration` L669 起、`from(...)` L713 起、构造器 L128-145）
- 修改：`agent-worker/src/main/java/io/agentteams/worker/WorkerRuntimeRouter.java`
- 测试：`agent-worker/src/test/java/io/agentteams/worker/QwenPawWorkerEventRateLimitTest.java`（新建）

- [x] **步骤 1：编写失败的配置测试**

创建 `agent-worker/src/test/java/io/agentteams/worker/QwenPawWorkerEventRateLimitTest.java`：

```java
package io.agentteams.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class QwenPawWorkerEventRateLimitTest {

    @Test
    void eventRateLimitDefaultsToThirtyPerSecond() {
        WorkerConfiguration configuration = WorkerConfiguration.from(Map.of("AGENTTEAMS_AGENT_ID", "w1"));
        assertEquals(30, configuration.eventRateLimit());
    }

    @Test
    void eventRateLimitIsConfigurable() {
        WorkerConfiguration configuration = WorkerConfiguration.from(Map.of(
                "AGENTTEAMS_AGENT_ID", "w1", "AGENTTEAMS_EVENT_RATE_LIMIT", "5"));
        assertEquals(5, configuration.eventRateLimit());
    }
}
```

- [x] **步骤 2：运行验证失败**

运行：`mvn -q -pl agent-worker -am test -Dtest=QwenPawWorkerEventRateLimitTest`
预期：编译错误 `cannot find symbol: method eventRateLimit()`（红）

- [x] **步骤 3：实现配置组件与接线**

① `WorkerConfiguration` record：在 `int modelCallMaxConcurrent,` 组件之后插入一行 `int eventRateLimit,`。

② `WorkerConfiguration.from(...)`：在 `integer(environment, "AGENTTEAMS_MODEL_CALL_MAX_CONCURRENT", maxConcurrentTasks),` 之后插入一行
`integer(environment, "AGENTTEAMS_EVENT_RATE_LIMIT", 30),`。

③ `QwenPawWorker` 构造器：把 `this.runtimeAdapter = new GatewayRuntimeAdapter(...)` 的 5 参调用改为 6 参，并在其后、`runtime.start(...)` 之前加接线：

```java
        this.runtimeAdapter = new GatewayRuntimeAdapter(configuration.agentId(), channelPort, runtime, clock,
                artifactUploadPort(configuration, gatewayChannel, clock), configuration.eventRateLimit());
        // runtime SSE 中间事件 → 适配器出口（限速与白名单在两侧兜底）。
        this.runtime.setEventSink(event -> runtimeAdapter.reportEvent(event.taskId(), event.eventType(),
                event.payloadJson()));
```

④ `WorkerRuntimeRouter` 加 override（放在 `start(...)` 之后）：

```java
    @Override
    public void setEventSink(io.agentteams.runtime.RuntimeEventSink eventSink) {
        qwenPaw.setEventSink(eventSink);
    }
```

- [x] **步骤 4：运行验证通过**

运行：`mvn -q -pl agent-worker -am test`
预期：PASS。若既有测试直接 new 了 WorkerConfiguration，按组件顺序在 modelCallMaxConcurrent 之后补 `30`（编译器会逐个指出位置）。

- [x] **步骤 5：Commit**

```bash
git add agent-worker/src/main/java/io/agentteams/worker/QwenPawWorker.java \
  agent-worker/src/main/java/io/agentteams/worker/WorkerRuntimeRouter.java \
  agent-worker/src/test/java/io/agentteams/worker/QwenPawWorkerEventRateLimitTest.java
git commit -m "feat(工作节点): 接线过程事件汇并默认限速 30 每秒

WorkerConfiguration 新增 AGENTTEAMS_EVENT_RATE_LIMIT（默认 30），
构造器把 runtime 的 RuntimeEventSink 指向 GatewayRuntimeAdapter
.reportEvent，路由器把 sink 委托给 qwenpaw 运行时；未配置时行为
与现状一致（不再额外上报）。

影响范围：agent-worker
测试确认：mvn -pl agent-worker -am test 全绿（默认值/覆盖两用例）"
```

---

### 任务 7：agent-gateway — oneof 路由与归属校验（违规丢弃）

**文件：**
- 修改：`agent-gateway/src/main/java/io/agentteams/gateway/GatewayApplicationHandler.java`
- 修改：`agent-gateway/src/main/java/io/agentteams/gateway/InboundEventHandler.java`（metadata() switch L96-108、route() switch L74-87）
- 修改：`agent-gateway/src/main/java/io/agentteams/gateway/ControlPlaneGatewayApplicationHandler.java`
- 测试：`agent-gateway/src/test/java/io/agentteams/gateway/TaskEventReportHandlingTest.java`（新建）

- [x] **步骤 1：编写失败的测试**

创建 `agent-gateway/src/test/java/io/agentteams/gateway/TaskEventReportHandlingTest.java`
（模式沿用 `ControlPlaneGatewayApplicationHandlerTest`：Mockito mock ExecutionEventPort + 本地 connection()/metadata() helper）：

```java
package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.agentteams.application.api.ExecutionEventPort;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.TaskEventReport;
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
    void dropsOversizedPayloadWithoutThrowing() {
        ExecutionEventPort events = mock(ExecutionEventPort.class);
        ControlPlaneGatewayApplicationHandler handler = new ControlPlaneGatewayApplicationHandler(events, clock());
        handler.taskEventReport(connection(), TaskEventReport.newBuilder()
                .setMetadata(metadata()).setEventType("tool.called")
                .setPayload(ByteString.copyFromUtf8("x".repeat(4097))).build());
        verifyNoInteractions(events);
    }

    private Clock clock() {
        return Clock.fixed(AT, ZoneOffset.UTC);
    }
}
```

- [x] **步骤 2：运行验证失败**

运行：`mvn -q -pl agent-gateway -am test -Dtest=TaskEventReportHandlingTest`
预期：编译错误 `cannot find symbol: method taskEventReport`（红）

- [x] **步骤 3：实现接口 default、路由与校验**

① `GatewayApplicationHandler.java`：import 区加 `import io.agentteams.contracts.v1.TaskEventReport;`；接口内（`taskFailed` 之后）加：

```java
    /**
     * Best-effort process report. Default keeps gateway-only deployments
     * silent; implementations must never throw for malformed reports so the
     * stream that carries terminal events stays alive.
     */
    default void taskEventReport(ConnectionRegistry.ConnectionSnapshot connection, TaskEventReport event) {
    }
```

② `InboundEventHandler.java`：`metadata(...)` switch 在 `case CONFIG_APPLIED -> ...` 之前加
`case TASK_EVENT_REPORT -> message.getTaskEventReport().getMetadata();`；
`route(...)` switch 在 `case CONFIG_APPLIED -> ...` 之前加
`case TASK_EVENT_REPORT -> application.taskEventReport(snapshot, message.getTaskEventReport());`。
（两个 switch 的通用 metadata 校验与 recordIfNew 幂等自动生效。）

③ `ControlPlaneGatewayApplicationHandler.java`：import 区加
`import io.agentteams.contracts.v1.TaskEventReport;` 与
`import io.agentteams.application.api.ExecutionEventPort.TaskEventReportCommand;`；
常量区加 `private static final int MAX_EVENT_PAYLOAD_BYTES = ExecutionEventPort.MAX_EVENT_PAYLOAD_BYTES;`；
在 `taskFailed(...)` 之后加 override：

```java
    @Override
    public void taskEventReport(ConnectionRegistry.ConnectionSnapshot connection, TaskEventReport event) {
        try {
            EventMetadata metadata = event.getMetadata();
            UUID taskId = uuid(metadata.getTaskId(), "task_id");
            UUID attemptId = uuid(metadata.getAttemptId(), "attempt_id");
            UUID leaseId = uuid(metadata.getLeaseId(), "lease_id");
            UUID eventId = uuid(metadata.getEventId(), "event_id");
            if (!connection.agentId().equals(metadata.getAgentId())) {
                throw invalid("agent_id does not match connection");
            }
            if (event.getEventType().isBlank()) {
                throw invalid("event_type is required");
            }
            if (event.getPayload().size() > MAX_EVENT_PAYLOAD_BYTES) {
                throw invalid("payload exceeds " + MAX_EVENT_PAYLOAD_BYTES + " bytes");
            }
            executionEvents.taskEventReport(taskId, new TaskEventReportCommand(eventId, attemptId, leaseId,
                    occurredAt(metadata), connection.agentId(), event.getEventType(),
                    event.getPayload().isEmpty() ? null : event.getPayload().toStringUtf8(),
                    event.getSequence(), correlationId(metadata)));
        } catch (GatewayExceptions.InvalidMessage error) {
            // 公理一：过程上报永远 best effort——校验失败丢弃并告警，绝不
            // 用 InvalidMessage 关闭承载终态事件的流。
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                    "Dropping invalid task event report: " + error.getMessage());
        }
    }
```

- [x] **步骤 4：运行验证通过**

运行：`mvn -q -pl agent-gateway -am test`
预期：PASS（新 3 用例 + 既有 InboundEventHandlerTest / ControlPlaneGatewayApplicationHandlerTest 全绿——default 方法未破坏既有实现）

- [x] **步骤 5：Commit**

```bash
git add agent-gateway/src/main/java/io/agentteams/gateway/GatewayApplicationHandler.java \
  agent-gateway/src/main/java/io/agentteams/gateway/InboundEventHandler.java \
  agent-gateway/src/main/java/io/agentteams/gateway/ControlPlaneGatewayApplicationHandler.java \
  agent-gateway/src/test/java/io/agentteams/gateway/TaskEventReportHandlingTest.java
git commit -m "feat(网关): 路由过程上报并做归属校验

AgentMessage oneof 新增 task_event_report 路由分支，复用既有的
通用元数据校验与 recordIfNew 幂等；应用缝 taskEventReport 校验
agent/attempt/lease 归属与载荷上限，校验失败丢弃并告警而不抛
InvalidMessage，保证中间事件永不威胁承载终态事件的连接。

影响范围：agent-gateway
测试确认：mvn -pl agent-gateway -am test 全绿（转发/伪造/超限三用例）"
```

---

### 任务 8：agent-gateway — NatsExecutionEventPublisher 发布 TASK_EVENT

**文件：**
- 修改：`agent-gateway/src/main/java/io/agentteams/gateway/NatsExecutionEventPublisher.java`
- 测试：`agent-gateway/src/test/java/io/agentteams/gateway/NatsExecutionEventPublisherTest.java`（新建，若已存在同名文件则追加用例）

- [x] **步骤 1：编写失败的测试**

```java
package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.application.api.ExecutionEventEnvelope;
import io.agentteams.application.api.ExecutionEventPort.TaskEventReportCommand;
import io.nats.client.JetStream;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NatsExecutionEventPublisherTest {

    @Test
    void publishesTaskEventEnvelopeToExecutionSubject() throws Exception {
        JetStream jetStream = mock(JetStream.class);
        when(jetStream.publish(anyString(), any(byte[].class))).thenReturn(null);
        NatsExecutionEventPublisher publisher = new NatsExecutionEventPublisher(jetStream, new ObjectMapper());
        UUID taskId = UUID.randomUUID();

        publisher.taskEventReport(taskId, new TaskEventReportCommand(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Instant.now(), "worker-1", "tool.called", "{\"tool\":\"web_search\"}",
                1, "corr-1"));

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(jetStream).publish(anyString(), body.capture());
        ExecutionEventEnvelope envelope = new ObjectMapper().readValue(body.getValue(), ExecutionEventEnvelope.class);
        assertThat(envelope.type()).isEqualTo("TASK_EVENT");
        assertThat(envelope.taskId()).isEqualTo(taskId);
        assertThat(envelope.taskEventReport().eventType()).isEqualTo("tool.called");
    }
}
```

（`JetStream` 打桩用 `mock(JetStream.class)`，与既有 `NatsGatewayEventConsumerTest` 一致；publisher 走 `jetStream.publish(String subject, byte[] body)` 重载。）

- [x] **步骤 2：运行验证失败**

运行：`mvn -q -pl agent-gateway -am test -Dtest=NatsExecutionEventPublisherTest`
预期：编译错误 `cannot find symbol: method taskEventReport`（红）

- [x] **步骤 3：实现发布方法**

`NatsExecutionEventPublisher.java` 在 `rejectUnaccepted(...)` 之后加：

```java
    @Override
    public void taskEventReport(UUID taskId, ExecutionEventPort.TaskEventReportCommand command) {
        publish(taskId, command.agentId(), ExecutionEventEnvelope.taskEvent(taskId, command));
    }
```

- [x] **步骤 4：运行验证通过**

运行：`mvn -q -pl agent-gateway -am test`
预期：PASS（envelope JSON 序列化往返包含 taskEventReport 组件）

- [x] **步骤 5：Commit**

```bash
git add agent-gateway/src/main/java/io/agentteams/gateway/NatsExecutionEventPublisher.java \
  agent-gateway/src/test/java/io/agentteams/gateway/NatsExecutionEventPublisherTest.java
git commit -m "feat(网关): 发布 TASK_EVENT 信封到执行事件主题

过程上报经既有 ExecutionEventPort 发布通道发出：envelope type 为
TASK_EVENT，沿 agentExecution 主题投递，复用既有 trace 上下文，
不新增 NATS 主题。

影响范围：agent-gateway
测试确认：mvn -pl agent-gateway -am test 全绿（序列化往返断言）"
```

---

### 任务 9：control-plane — 消费 TASK_EVENT 并转发观测端口

**文件：**
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/outbox/NatsExecutionEventConsumer.java`（分发 L295-303 + withContext 区 L531-559）
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/application/ControlPlaneExecutionEventAdapter.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/outbox/`（既有 consumer 测试文件追加用例；`grep -l "TASK_EVENT\|ExecutionEventEnvelope" control-plane/src/test -r | head -1` 定位）

- [x] **步骤 1：编写失败的测试**

在 `NatsExecutionEventConsumerTest`（`control-plane/src/test/java/io/agentteams/controlplane/outbox/`）追加用例——沿用该文件既有的 `Message message = mock(Message.class)` + `when(message.getData())` 打桩：

```java
    @Test
    void dispatchesTaskEventEnvelopeToExecutionPortAndAcks() throws Exception {
        UUID taskId = UUID.randomUUID();
        TaskEventReportCommand command = new TaskEventReportCommand(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Instant.now(), "worker-1", "tool.called", "{\"tool\":\"web_search\"}",
                2, "corr-1");
        ExecutionEventEnvelope envelope = ExecutionEventEnvelope.taskEvent(taskId, command);
        Message message = mock(Message.class);
        when(message.getData())
                .thenReturn(new ObjectMapper().writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8));
        ExecutionEventPort executionEvents = mock(ExecutionEventPort.class);
        NatsExecutionEventConsumer consumer = new NatsExecutionEventConsumer(
                mock(JetStream.class), executionEvents, new ObjectMapper().findAndRegisterModules(), "test-consumer");

        consumer.process(message);

        ArgumentCaptor<TaskEventReportCommand> captured = ArgumentCaptor.forClass(TaskEventReportCommand.class);
        verify(executionEvents).taskEventReport(eq(taskId), captured.capture());
        assertThat(captured.getValue().eventType()).isEqualTo("tool.called");
        assertThat(captured.getValue().sequence()).isEqualTo(2);
        verify(message).ack();
    }
```

（import 区按需补：`com.fasterxml.jackson.databind.ObjectMapper`、`io.agentteams.application.api.ExecutionEventEnvelope`、`java.time.Instant`、`java.util.UUID`、`org.mockito.ArgumentCaptor`、`org.mockito.ArgumentMatchers.eq`。）

- [x] **步骤 2：运行验证失败**

运行：`mvn -q -pl control-plane -am test -Dtest=NatsExecutionEventConsumerTest#dispatchesTaskEventEnvelopeToExecutionPortAndAcks`
预期：FAIL——`unsupported execution event type: TASK_EVENT`（分发无此分支，消息走 IllegalArgumentException）

- [x] **步骤 3：实现分发分支与适配器转发**

① `NatsExecutionEventConsumer.process` 的类型分发链（`REJECTION` 分支之后）加：

```java
            } else if ("TASK_EVENT".equals(envelope.type())) {
                executionEvents.taskEventReport(envelope.taskId(),
                        withContext(envelope.taskEventReport(), envelope));
```

② `withContext` 私有区追加重载：

```java
    private static io.agentteams.application.api.ExecutionEventPort.TaskEventReportCommand withContext(
            io.agentteams.application.api.ExecutionEventPort.TaskEventReportCommand command,
            ExecutionEventEnvelope envelope) {
        TraceContext context = new TraceContext(envelope.correlationId(), envelope.traceparent(),
                envelope.tracestate());
        return new io.agentteams.application.api.ExecutionEventPort.TaskEventReportCommand(command.eventId(),
                command.attemptId(), command.leaseId(), command.occurredAt(), command.agentId(),
                command.eventType(), command.payloadJson(), command.sequence(), context.correlationId());
    }
```

③ `ControlPlaneExecutionEventAdapter`：import 区加 `import io.agentteams.application.api.ExecutionEventPort.TaskEventReportCommand;`；`rejectUnaccepted(...)` 之后加：

```java
    @Override
    public void taskEventReport(UUID taskId, TaskEventReportCommand command) {
        Objects.requireNonNull(command, "command");
        // runId 与既有 observe(...) 相同：run == attempt。
        observations.observed(taskId, command.attemptId(), command.eventId(), command.occurredAt(),
                command.correlationId(), command.eventType(), command.payloadJson());
    }
```

- [x] **步骤 4：运行验证通过**

运行：`mvn -q -pl control-plane -am test`
预期：PASS

- [x] **步骤 5：Commit**

```bash
git add control-plane/src/main/java/io/agentteams/controlplane/outbox/NatsExecutionEventConsumer.java \
  control-plane/src/main/java/io/agentteams/controlplane/application/ControlPlaneExecutionEventAdapter.java
git commit -m "feat(控制面): 消费 TASK_EVENT 并转发观测端口

执行事件消费者新增 TASK_EVENT 分支，经 withContext 注入链路上下文
后调用 ExecutionEventPort.taskEventReport；控制面适配器把命令映射
为 observations.observed（run == attempt），白名单与范围校验交给
观测适配器（下一任务）。

影响范围：control-plane
测试确认：mvn -pl control-plane -am test 全绿（分发/ack 用例）"
```

---

### 任务 10：control-plane — observed() 二次校验落库 + TaskResponse.source

**文件：**
- 修改：`application-contracts/src/main/java/io/agentteams/application/api/TaskExecutionObservationPort.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/application/ControlPlaneTaskExecutionObservationAdapter.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/api/TaskController.java`（TaskResponse L174-181）
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/application/ControlPlaneTaskExecutionObservationAdapterTest.java`（已存在，追加用例；Mockito 替身模式与类内既有常量见步骤 1）

- [x] **步骤 1：编写失败的测试**

在 `ControlPlaneTaskExecutionObservationAdapterTest`（已存在，`control-plane/src/test/java/io/agentteams/controlplane/application/`）追加用例——沿用该文件的 Mockito 模式：mock `TaskRunObservationRepository`/`TaskProcessEventService`，类内已有 `TASK_ID`/`RUN_ID`/`NOW`/`CONTEXT` 常量：

```java
    @Test
    void observedPersistsWhitelistedToolEventsForRequester() {
        TaskRunObservationRepository runs = mock(TaskRunObservationRepository.class);
        TaskProcessEventService process = mock(TaskProcessEventService.class);
        WebhookDeliveryService webhooks = mock(WebhookDeliveryService.class);
        when(runs.contextForTask(TASK_ID)).thenReturn(Optional.of(CONTEXT));
        when(runs.nextSequence(RUN_ID)).thenReturn(5L);
        ControlPlaneTaskExecutionObservationAdapter adapter = new ControlPlaneTaskExecutionObservationAdapter(
                runs, process, mock(TaskResultManifestService.class), webhooks);

        UUID eventId = UUID.randomUUID();
        adapter.observed(TASK_ID, RUN_ID, eventId, NOW, "corr-1", "tool.called", "{\"tool\":\"web_search\"}");

        var events = ArgumentCaptor.forClass(io.agentteams.application.api.TaskProcessEvent.class);
        verify(process).append(any(), events.capture());
        assertThat(events.getValue().eventType()).isEqualTo("tool.called");
        assertThat(events.getValue().visibility()).isEqualTo(io.agentteams.application.api.TaskEventVisibility.REQUESTER);
        assertThat(events.getValue().eventId()).isEqualTo(eventId);
    }

    @Test
    void observedDropsUnknownEventTypeAndOversizedPayload() {
        TaskRunObservationRepository runs = mock(TaskRunObservationRepository.class);
        TaskProcessEventService process = mock(TaskProcessEventService.class);
        ControlPlaneTaskExecutionObservationAdapter adapter = new ControlPlaneTaskExecutionObservationAdapter(
                runs, process, mock(TaskResultManifestService.class), mock(WebhookDeliveryService.class));

        adapter.observed(TASK_ID, RUN_ID, UUID.randomUUID(), NOW, "corr-1", "reasoning", "{\"text\":\"secret\"}");
        adapter.observed(TASK_ID, RUN_ID, UUID.randomUUID(), NOW, "corr-1", "tool.called", "x".repeat(4097));

        verify(process, never()).append(any(), any());
    }

    @Test
    void observedDropsUnscopedTaskWithoutThrowing() {
        TaskRunObservationRepository runs = mock(TaskRunObservationRepository.class);
        when(runs.contextForTask(TASK_ID)).thenReturn(Optional.empty());
        TaskProcessEventService process = mock(TaskProcessEventService.class);
        ControlPlaneTaskExecutionObservationAdapter adapter = new ControlPlaneTaskExecutionObservationAdapter(
                runs, process, mock(TaskResultManifestService.class), mock(WebhookDeliveryService.class));

        adapter.observed(TASK_ID, UUID.randomUUID(), UUID.randomUUID(), NOW, "corr-1", "tool.called", "{}");

        verify(process, never()).append(any(), any());
    }
```

（该测试类已 import `mock/when/verify/assertThat/Optional/UUID/Instant`；按需补 `import org.mockito.ArgumentCaptor;`、`import static org.mockito.Mockito.never;`、`import io.agentteams.application.api.TaskEventVisibility;`。包私有构造器 `ControlPlaneTaskExecutionObservationAdapter(TaskRunObservationRepository, ...)` 与既有用例一致。）

- [x] **步骤 2：运行验证失败**

运行：`mvn -q -pl control-plane -am test -Dtest=ControlPlaneTaskExecutionObservationAdapterTest`
预期：编译错误 `cannot find symbol: method observed`（红）

- [x] **步骤 3：实现观测端口方法与二次校验**

① `TaskExecutionObservationPort.java`：`failed(...)` 声明之后加 default 方法（default 保证既有 noop/实现零破坏）：

```java
    /**
     * Records a whitelisted middle-of-execution action (tool calls etc.).
     * Implementations must drop invalid input instead of throwing: this
     * stream is best effort and shares the consumer with terminal events.
     */
    default void observed(UUID taskId, UUID runId, UUID eventId, Instant occurredAt, String correlationId,
            String eventType, String payloadJson) {
    }
```

② `ControlPlaneTaskExecutionObservationAdapter.java`：import 区加 `import java.util.Set;`；常量区加
`private static final Set<String> ALLOWED_RUNTIME_EVENT_TYPES = Set.of("tool.called", "tool.finished");` 与
`private static final int MAX_RUNTIME_PAYLOAD_BYTES = 4096;`；在 `failed(...)` 之后加：

```java
    @Override
    @Transactional
    public void observed(UUID taskId, UUID runId, UUID eventId, Instant occurredAt, String correlationId,
            String eventType, String payloadJson) {
        // 公理二：入口二次校验——白名单 + 4KB + 合法 JSON，违规一律丢弃。
        if (eventType == null || !ALLOWED_RUNTIME_EVENT_TYPES.contains(eventType)) {
            return;
        }
        if (payloadJson != null
                && payloadJson.getBytes(StandardCharsets.UTF_8).length > MAX_RUNTIME_PAYLOAD_BYTES) {
            return;
        }
        JsonNode payload = parseRuntimePayload(payloadJson);
        if (payload == null) {
            return;
        }
        try {
            recordProcess(taskId, runId, eventId, occurredAt, correlationId, eventType, payload, "RUNNING");
        } catch (RuntimeException error) {
            // 落库被拒（含敏感词护栏）按丢弃处理，绝不毒化承载终态事件的消费者。
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                    "Dropping runtime event " + eventId + ": " + error.getMessage());
        }
    }

    private static JsonNode parseRuntimePayload(String json) {
        if (json == null || json.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            JsonNode node = JSON.readTree(json);
            return node != null && node.isObject() ? node : null;
        } catch (Exception ignored) {
            return null;
        }
    }
```

（recordProcess 复用既有路径：contextForTask 为空返回 null → 静默；sequence 由 `runs.nextSequence(runId)` 服务端分配；幂等由 `task_process_events` 的 `ON CONFLICT DO NOTHING` 按 eventId 保证。）

③ `TaskController.java` 的 `TaskResponse`：record 尾部追加 `SourceRef source` 组件并更新 `from(...)`：

```java
    public record TaskResponse(UUID id, String title, String description, String phase,
            int priority, String taskType, Instant createdAt, Instant updatedAt, long version,
            SourceRef source) {

        /** 来源会话回链；仅暴露标识符，不暴露会话内容。 */
        public record SourceRef(String conversationId, String messageId) {
        }

        static TaskResponse from(TaskRecord task) {
            return new TaskResponse(task.id(), task.title(), task.description(), task.phase().name(),
                    task.priority(), task.taskType(), task.createdAt(), task.updatedAt(), task.version(),
                    sourceOf(task));
        }

        private static SourceRef sourceOf(TaskRecord task) {
            try {
                JsonNode source = new ObjectMapper().readTree(task.specJson()).path("inputJson").path("source");
                String conversationId = source.path("conversationId").asText("");
                if (conversationId.isBlank()) {
                    return null;
                }
                return new SourceRef(conversationId, source.path("messageId").asText(null));
            } catch (Exception ignored) {
                return null;
            }
        }
    }
```

（`TaskController` 若尚无 Jackson import，补 `import com.fasterxml.jackson.databind.JsonNode;` 与 `import com.fasterxml.jackson.databind.ObjectMapper;`。）

- [x] **步骤 4：运行验证通过**

运行：`mvn -q -pl control-plane -am test`
预期：PASS（新 3 用例 + 既有观测适配器测试全绿）

- [x] **步骤 5：Commit**

```bash
git add application-contracts/src/main/java/io/agentteams/application/api/TaskExecutionObservationPort.java \
  control-plane/src/main/java/io/agentteams/controlplane/application/ControlPlaneTaskExecutionObservationAdapter.java \
  control-plane/src/main/java/io/agentteams/controlplane/api/TaskController.java
git commit -m "feat(控制面): 过程事件入口二次校验落库并暴露任务来源

observed() 作为入口闸门执行白名单（tool.called/tool.finished）、
4096 字节上限与 JSON 合法性校验，复用 recordProcess 既有路径获得
服务端序列与幂等去重，落库被拒一律丢弃；TaskResponse 新增 source
组件，从 spec.inputJson.source 提取会话标识供 Console 回链。

影响范围：application-contracts、control-plane
测试确认：mvn -pl control-plane -am test 全绿（落库/丢弃/未授权三用例）"
```

---

### 任务 11：MCP 脚本 — create_task 携带来源会话标识

**文件：**
- 修改：`scripts/agentteams-task-mcp.py`（TOOL_SCHEMAS create_task L44-73、`tool_create_task` L262-291）
- 修改：`scripts/test_agentteams_task_mcp.py`（追加用例）

- [x] **步骤 1：编写失败的测试**

在既有 TestCase 类中追加两个用例——沿用该文件的本地 `ThreadingHTTPServer` 假服务器模式（`self._use_env(self.env)` + `MCP.call_tool` + `self.server.requests` 捕获请求体，与既有 `test_create_task_posts_spec_and_queues_with_idempotency_keys` 一致）：

```python
    def test_create_task_writes_conversation_source_into_input_json(self):
        self._use_env(self.env)
        payload = MCP.call_tool("create_task", {
            "title": "来源回链", "prompt": "p",
            "source": {"conversation_id": "conv-1", "message_id": "msg-9"},
        })
        self.assertTrue(payload["ok"], payload)
        method, path, headers, body = next(r for r in self.server.requests
                                           if r[0] == "POST" and r[1] == "/api/v1/tasks")
        self.assertEqual(body["spec"]["inputJson"]["source"],
                         {"conversationId": "conv-1", "messageId": "msg-9"})

    def test_create_task_without_source_keeps_input_json_unchanged(self):
        self._use_env(self.env)
        payload = MCP.call_tool("create_task", {"title": "无来源", "prompt": "p"})
        self.assertTrue(payload["ok"], payload)
        method, path, headers, body = next(r for r in self.server.requests
                                           if r[0] == "POST" and r[1] == "/api/v1/tasks")
        self.assertNotIn("source", body["spec"]["inputJson"])
```

- [x] **步骤 2：运行验证失败**

运行：`python3 scripts/test_agentteams_task_mcp.py`
预期：FAIL/ERROR——`tool_create_task` 不接受 source、inputJson 无 source 键

- [x] **步骤 3：实现 source 参数**

① `TOOL_SCHEMAS["create_task"]["inputSchema"]["properties"]` 中 `prompt` 之后加：

```python
                "source": {
                    "type": "object",
                    "description": (
                        "Optional conversation origin of this task. Pass "
                        "conversation_id (and message_id when known) so the "
                        "console task page can link back to the originating "
                        "chat conversation."
                    ),
                    "properties": {
                        "conversation_id": {"type": "string", "description": "Conversation UUID."},
                        "message_id": {"type": "string", "description": "Originating message id."},
                    },
                },
```

② `tool_create_task` 中 prompt 解析与 `spec` 构造之间加：

```python
    source = arguments.get("source") or {}
    conversation_id = _clean_text(source.get("conversation_id"), "source.conversation_id", MAX_TITLE)
    message_id = _clean_text(source.get("message_id"), "source.message_id", MAX_TITLE)
    input_json = {"prompt": prompt}
    if conversation_id:
        origin = {"conversationId": conversation_id}
        if message_id:
            origin["messageId"] = message_id
        input_json["source"] = origin
    spec = {
        "scope": {"tenant": config.tenant, "project": config.project, "team": config.team},
        "taskType": "qwenpaw",
        "inputJson": input_json,
        "requiredCapabilities": [],
    }
```

（原 `"inputJson": {"prompt": prompt},` 一行删除，由上面的 `input_json` 取代。`_clean_text` 为既有 helper；若其 `required` 形参为必填，则传 `required=False`。）

- [x] **步骤 4：运行验证通过**

运行：`python3 scripts/test_agentteams_task_mcp.py`
预期：全部用例 OK（含既有 8 项 + 新 2 项）

- [x] **步骤 5：Commit**

```bash
git add scripts/agentteams-task-mcp.py scripts/test_agentteams_task_mcp.py
git commit -m "feat(任务工具): create_task 支持携带来源会话标识

对话内创建任务时把 conversation_id/message_id 写入
spec.inputJson.source（camelCase），控制面原样持久化，Console
据此渲染来源会话回链；未传 source 时 inputJson 保持原样。

影响范围：scripts（MCP 脚本与契约测试）
测试确认：python3 scripts/test_agentteams_task_mcp.py 全部 OK"
```

---

### 任务 12：console — 右栏面板、DAG 与双流合并时间线

**文件：**
- 修改：`console/src/api/types.ts`（`Task` 类型加 `source`）
- 修改：`control-plane` 无（source 已在任务 10 暴露）
- 创建：`console/src/features/tasks/TaskDag.tsx`
- 创建：`console/src/features/tasks/TaskInfoPanel.tsx`
- 修改：`console/src/features/tasks/TaskDetailPage.tsx`
- 修改：`console/src/styles/global.css`
- 测试：`console/tests/features/TaskInfoPanel.test.tsx`（新建）

- [x] **步骤 1：编写失败的测试**

创建 `console/tests/features/TaskInfoPanel.test.tsx`：

```tsx
import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { TaskInfoPanel } from '../../src/features/tasks/TaskInfoPanel';
import type { TaskProcessEvent } from '../../src/api/types';

vi.mock('../../src/queries/useTaskQueries', () => ({
  useTaskTree: () => ({
    data: [
      { taskId: 'root', parentTaskId: null, sequence: 0, status: 'RUNNING', dependencyIds: [], updatedAt: '2026-09-08T00:00:00Z' },
      { taskId: 'child-a', parentTaskId: 'root', sequence: 1, status: 'PENDING', dependencyIds: [], updatedAt: '2026-09-08T00:01:00Z' },
    ],
  }),
  useTaskDecisions: () => ({
    data: [{ id: 'd1', selectedAction: 'create_task', goalSummary: '创建任务', createdAt: '2026-09-08T00:00:00Z' }],
  }),
  useTaskResult: () => ({
    data: { status: 'SUCCEEDED', summary: '完成', artifacts: [{ name: 'output.md', storageRef: 'tasks/x', contentType: 'text/markdown', sizeBytes: 12, sha256: 'ab' }] },
  }),
}));

function renderPanel(processEvents: TaskProcessEvent[]) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <TaskInfoPanel
        projectId="p1"
        taskId="root"
        runId="r1"
        task={{ id: 'root', title: 'T', phase: 'RUNNING', priority: 5, source: { conversationId: 'conv-1' } } as never}
        processEvents={processEvents}
      />
    </QueryClientProvider>,
  );
}

describe('TaskInfoPanel', () => {
  it('renders dag nodes for the current run', () => {
    renderPanel([]);
    expect(screen.getByTestId('task-dag')).toBeInTheDocument();
    expect(screen.getAllByTestId('task-dag-node').length).toBeGreaterThanOrEqual(2);
  });

  it('renders process events with semantic labels on the events tab', () => {
    renderPanel([{
      eventId: 'e1', taskId: 'root', runId: 'r1', sequence: 1, eventType: 'tool.called',
      visibility: 'REQUESTER', occurredAt: '2026-09-08T00:02:00Z', correlationId: 'c1',
      payload: '{"tool":"web_search"}',
    }]);
    expect(screen.getByText('调用工具')).toBeInTheDocument();
    expect(screen.getByText(/web_search/)).toBeInTheDocument();
  });

  it('links back to the source conversation on the details tab', async () => {
    const user = userEvent.setup();
    renderPanel([]);
    await user.click(screen.getByRole('tab', { name: '详情' }));
    const backlink = screen.getByTestId('source-backlink');
    expect(within(backlink).getByRole('link', { name: /打开来源会话/ })).toHaveAttribute(
      'href',
      expect.stringContaining('conv-1'),
    );
  });

  it('switches to artifacts tab showing manifest entries', async () => {
    const user = userEvent.setup();
    renderPanel([]);
    await user.click(screen.getByRole('tab', { name: '成果物' }));
    expect(screen.getByText('output.md')).toBeInTheDocument();
  });
});
```

- [x] **步骤 2：运行验证失败**

运行：`cd console && npx vitest run tests/features/TaskInfoPanel.test.tsx`
预期：FAIL——模块 `../../src/features/tasks/TaskInfoPanel` 不存在（红）

- [x] **步骤 3：实现 TaskDag 与 TaskInfoPanel**

创建 `console/src/features/tasks/TaskDag.tsx`（SVG 分层布局：根在上，子节点按 sequence 横排，直线连边）：

```tsx
import type { TaskTreeNode } from '../../api/types';

const NODE_WIDTH = 128;
const NODE_HEIGHT = 36;
const H_GAP = 24;
const V_GAP = 48;

type Positioned = { node: TaskTreeNode; x: number; y: number };

/** 分层布局：根为第一层，子节点按 sequence 从左到右排布。 */
function layout(nodes: TaskTreeNode[], rootTaskId: string): Positioned[] {
  const childrenOf = new Map<string, TaskTreeNode[]>();
  nodes.forEach((node) => {
    const parent = node.parentTaskId || rootTaskId;
    childrenOf.set(parent, [...(childrenOf.get(parent) || []), node]);
  });
  const positioned: Positioned[] = [];
  const place = (taskId: string, depth: number, offset: number): number => {
    const children = childrenOf.get(taskId) || [];
    let cursor = offset;
    let centerX = offset;
    children.forEach((child, index) => {
      const childX = place(child.taskId, depth + 1, cursor);
      cursor = childX + NODE_WIDTH + H_GAP;
      if (index === Math.floor((children.length - 1) / 2)) centerX = childX;
    });
    if (!children.length) {
      centerX = offset;
      cursor = offset + NODE_WIDTH + H_GAP;
    }
    positioned.push({ node: nodes.find((n) => n.taskId === taskId) as TaskTreeNode, x: centerX, y: depth * (NODE_HEIGHT + V_GAP) });
    return centerX;
  };
  if (nodes.some((node) => node.taskId === rootTaskId)) {
    place(rootTaskId, 0, 0);
  }
  return positioned;
}

export function TaskDag({ nodes, rootTaskId }: { nodes: TaskTreeNode[]; rootTaskId: string }) {
  if (!nodes.length) {
    return <p className="muted-text">当前运行暂无任务分解。</p>;
  }
  const positioned = layout(nodes, rootTaskId);
  const width = Math.max(...positioned.map((p) => p.x + NODE_WIDTH)) + 8;
  const height = Math.max(...positioned.map((p) => p.y + NODE_HEIGHT)) + 8;
  const byId = new Map(positioned.map((p) => [p.node.taskId, p]));
  return (
    <div className="task-dag" data-testid="task-dag">
      <svg width="100%" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="任务分解图">
        {positioned.map(({ node, x, y }) => {
          const parent = byId.get(node.parentTaskId || rootTaskId);
          if (!parent) return null;
          return (
            <line
              key={`edge-${node.taskId}`}
              x1={parent.x + NODE_WIDTH / 2}
              y1={parent.y + NODE_HEIGHT}
              x2={x + NODE_WIDTH / 2}
              y2={y}
              className="task-dag__edge"
            />
          );
        })}
        {positioned.map(({ node, x, y }) => (
          <g key={node.taskId} transform={`translate(${x}, ${y})`} data-testid="task-dag-node">
            <rect
              width={NODE_WIDTH}
              height={NODE_HEIGHT}
              rx={8}
              className={`task-dag__node task-dag__node--${node.status.toLowerCase()}`}
            />
            <text x={NODE_WIDTH / 2} y={NODE_HEIGHT / 2 + 4} textAnchor="middle" className="task-dag__label">
              {node.taskId === rootTaskId ? '根任务' : node.taskId.slice(0, 8)}
            </text>
          </g>
        ))}
      </svg>
    </div>
  );
}
```

创建 `console/src/features/tasks/TaskInfoPanel.tsx`：

```tsx
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useTaskDecisions, useTaskResult, useTaskTree } from '../../queries/useTaskQueries';
import type { Task, TaskProcessEvent, TaskTreeNode } from '../../api/types';
import { TaskDag } from './TaskDag';

type TabName = 'events' | 'artifacts' | 'decisions' | 'details';
const TABS: Array<{ name: TabName; label: string }> = [
  { name: 'events', label: '事件' },
  { name: 'artifacts', label: '成果物' },
  { name: 'decisions', label: '决策' },
  { name: 'details', label: '详情' },
];

/** 过程事件 → 中文动作（actor 语义化：执行期事件归 worker，生命周期归系统）。 */
const PROCESS_EVENT_LABELS: Record<string, string> = {
  'task.started': '任务开始执行',
  'task.progress': '进度更新',
  'task.planned': '计划已生成',
  'task.checkpoint': '记录检查点',
  'tool.called': '调用工具',
  'tool.finished': '工具执行完成',
  'task.completed': '任务完成',
  'task.failed': '任务失败',
};

function payloadSummary(event: TaskProcessEvent): string {
  if (!event.payload) return '';
  try {
    const data = JSON.parse(event.payload) as Record<string, unknown>;
    if (typeof data.tool === 'string' && event.eventType === 'tool.called') return data.tool;
    if (typeof data.tool === 'string' && event.eventType === 'tool.finished') {
      const ms = typeof data.elapsedMs === 'number' ? `${data.elapsedMs}ms` : '—';
      return `${data.tool} · ${ms} · ${data.ok === false ? '失败' : '成功'}`;
    }
    return event.payload;
  } catch {
    return event.payload;
  }
}

export function TaskInfoPanel({
  projectId,
  taskId,
  runId,
  task,
  processEvents,
}: {
  projectId: string;
  taskId: string;
  runId: string;
  task: Task;
  processEvents: TaskProcessEvent[];
}) {
  const [tab, setTab] = useState<TabName>('events');
  const tree = useTaskTree(projectId, taskId, runId);
  const decisions = useTaskDecisions(projectId, taskId, runId);
  const result = useTaskResult(projectId, taskId, runId);
  const source = task.source;
  return (
    <aside className="info-panel">
      <section className="panel">
        <p className="eyebrow">任务结构</p>
        <h2>分解图</h2>
        <TaskDag nodes={(tree.data || []) as TaskTreeNode[]} rootTaskId={taskId} />
      </section>
      <section className="panel">
        <div className="info-panel__tabs" role="tablist">
          {TABS.map((item) => (
            <button
              key={item.name}
              role="tab"
              aria-selected={tab === item.name}
              className={`button button--ghost ${tab === item.name ? 'is-active' : ''}`}
              onClick={() => setTab(item.name)}
            >
              {item.label}
            </button>
          ))}
        </div>
        {tab === 'events' && (
          <ul className="info-panel__list">
            {processEvents.length === 0 && <li className="muted-text">暂无过程事件。</li>}
            {processEvents.map((event) => (
              <li key={event.eventId}>
                <strong>{PROCESS_EVENT_LABELS[event.eventType] || event.eventType}</strong>
                <span className="muted-text">{payloadSummary(event)}</span>
                <time>{new Date(event.occurredAt).toLocaleTimeString('zh-CN')}</time>
              </li>
            ))}
          </ul>
        )}
        {tab === 'artifacts' && (
          <ul className="info-panel__list">
            {!result.data?.artifacts?.length && <li className="muted-text">尚未产出成果物。</li>}
            {result.data?.artifacts?.map((artifact) => (
              <li key={artifact.name}>
                <strong>{artifact.name}</strong>
                <span className="muted-text">{artifact.contentType} · {artifact.sizeBytes} B</span>
              </li>
            ))}
          </ul>
        )}
        {tab === 'decisions' && (
          <ul className="info-panel__list">
            {!decisions.data?.length && <li className="muted-text">暂无决策记录。</li>}
            {decisions.data?.map((decision) => (
              <li key={decision.id}>
                <strong>{decision.goalSummary}</strong>
                <span className="muted-text">{decision.selectedAction}</span>
              </li>
            ))}
          </ul>
        )}
        {tab === 'details' && (
          <div className="detail-list">
            <span>任务类型<strong>{task.taskType || 'NORMAL'}</strong></span>
            <span>优先级<strong>P{task.priority}</strong></span>
            <span>团队<strong>{task.teamId || '未绑定'}</strong></span>
            <span>创建时间<strong>{new Date(task.createdAt).toLocaleString('zh-CN')}</strong></span>
            <span data-testid="source-backlink">
              来源会话
              <strong>
                {source?.conversationId
                  ? <Link to={`/${projectId}/conversations?sessionId=${source.conversationId}`}>打开来源会话</Link>
                  : '未关联'}
              </strong>
            </span>
          </div>
        )}
      </section>
    </aside>
  );
}
```

- [x] **步骤 4：TaskDetailPage 重排（grid 主列 + 右栏）与双流合并**

① `console/src/api/types.ts` 的 `Task` 类型尾部加一行：

```ts
  source?: { conversationId?: string; messageId?: string } | null;
```

② `TaskDetailPage.tsx`：

- import 区加 `useMemo`、`useTaskProcessEvents`、新组件：

```tsx
import { useMemo, useState } from 'react';
import { useTaskProcessEvents, ... } from '../../queries/useTaskQueries';
import { TaskInfoPanel } from './TaskInfoPanel';
```

- 组件体内（`runs` hook 之后）取 runId 与过程事件流，并定义合并函数与语义标签：

```tsx
  const runId = searchParams.get('runId') || runs.data?.[0]?.id || '';
  const processEvents = useTaskProcessEvents(projectId, taskId, runId);

const PROCESS_EVENT_LABELS: Record<string, string> = {
  'task.started': '任务开始执行',
  'task.progress': '进度更新',
  'task.planned': '计划已生成',
  'task.checkpoint': '记录检查点',
  'tool.called': '调用工具',
  'tool.finished': '工具执行完成',
  'task.completed': '任务完成',
  'task.failed': '任务失败',
};

function processEventSummary(event: { eventType: string; payload?: string | null }): string {
  if (!event.payload) return '';
  try {
    const data = JSON.parse(event.payload) as Record<string, unknown>;
    if (typeof data.tool === 'string' && event.eventType === 'tool.called') return data.tool;
    if (typeof data.tool === 'string' && event.eventType === 'tool.finished') {
      const ms = typeof data.elapsedMs === 'number' ? `${data.elapsedMs}ms` : '—';
      return `${data.tool} · ${ms} · ${data.ok === false ? '失败' : '成功'}`;
    }
    return event.payload;
  } catch {
    return event.payload;
  }
}

export function mergeTaskTimelines(
  lifecycle: Array<{ id: string; title: string; description?: string; time?: string; tone?: string }>,
  process: Array<{ eventId: string; eventType: string; occurredAt: string; payload?: string | null }>,
) {
  const lifecycleItems = lifecycle.map((event) => ({
    id: `lifecycle:${event.id}`,
    title: event.title,
    description: event.description,
    time: event.time,
    tone: event.tone,
  }));
  const processItems = process.map((event) => ({
    id: `process:${event.eventId}`,
    title: PROCESS_EVENT_LABELS[event.eventType] || event.eventType,
    description: processEventSummary(event),
    time: event.occurredAt,
    tone: event.eventType === 'task.failed' ? 'failed' : 'running',
  }));
  return [...lifecycleItems, ...processItems].sort(
    (left, right) => (left.time || '').localeCompare(right.time || ''),
  );
}
```

（`mergeTaskTimelines` 与 `PROCESS_EVENT_LABELS` 定义在文件顶层、组件外，便于测试；`payloadSummary/processEventSummary` 逻辑一致，二选一导出复用，避免重复。）

- 时间线 `<Timeline items={...}>` 改用合并结果（放在组件内）：

```tsx
  const timelineItems = useMemo(
    () =>
      mergeTaskTimelines(
        (events.data || []).map((event) => ({
          id: event.id,
          title: labelType(event.type),
          description: event.message,
          time: event.createdAt,
          tone: event.phase?.toLowerCase(),
        })),
        processEvents.data || [],
      ),
    [events.data, processEvents.data],
  );
```

```tsx
          <Timeline items={timelineItems} />
```

- 布局重排：原 `<div className="content-grid">…生命周期+执行信息…</div>` 改为——主列只保留生命周期时间线，右栏 `TaskInfoPanel` 吸收原「执行信息」键值（详情 tab 已含类型/优先级/团队/创建时间）：

```tsx
      <div className="detail-layout">
        <div className="detail-layout__main">
          <section className="panel">{/* 原生命周期时间线 section 原样移入，Timeline items 改 timelineItems */}</section>
        </div>
        <aside className="detail-layout__aside">
          <TaskInfoPanel
            projectId={projectId}
            taskId={taskId}
            runId={runId}
            task={task.data}
            processEvents={processEvents.data || []}
          />
        </aside>
      </div>
```

（其后的「执行尝试 / TaskExecutionObservability / 执行结果 / 恢复策略」保持全宽不动。`reconnect`/`isError` 提示块随生命周期 section 一起移动，并把 `processEvents.connectionState` 的断线提示一并加入主列时间线 section 顶部。）

③ `console/src/styles/global.css` 追加：

```css
.detail-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 16px;
  align-items: start;
}
.detail-layout__aside {
  position: sticky;
  top: 16px;
  display: grid;
  gap: 16px;
}
.info-panel__tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.info-panel__tabs .is-active {
  border-bottom: 2px solid var(--accent, #2563eb);
}
.info-panel__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 8px;
}
.info-panel__list li {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 4px;
}
.task-dag__node--running { fill: #dbeafe; stroke: #2563eb; }
.task-dag__node--pending { fill: #f1f5f9; stroke: #94a3b8; }
.task-dag__node--succeeded { fill: #dcfce7; stroke: #16a34a; }
.task-dag__node--failed { fill: #fee2e2; stroke: #dc2626; }
.task-dag__edge { stroke: #cbd5e1; }
.task-dag__label { font-size: 12px; fill: #334155; }
@media (max-width: 1080px) {
  .detail-layout { grid-template-columns: 1fr; }
}
```

- [x] **步骤 5：运行验证通过**

运行：`cd console && npx vitest run tests/features/TaskInfoPanel.test.tsx tests/features/TaskPages.test.tsx`
预期：PASS（新 4 用例 + 既有 TaskPages 全绿；若 TaskPages 既有用例断言「执行信息」在主列，按新布局把断言移到右栏）

- [x] **步骤 6：Commit**

```bash
git add console/src/api/types.ts \
  console/src/features/tasks/TaskDag.tsx \
  console/src/features/tasks/TaskInfoPanel.tsx \
  console/src/features/tasks/TaskDetailPage.tsx \
  console/src/styles/global.css \
  console/tests/features/TaskInfoPanel.test.tsx
git commit -m "feat(控制台): 任务详情页新增右栏信息面板与过程时间线

详情页重排为 CSS grid 主列 + 320px 右栏：右栏 TaskInfoPanel 提供
分解图（TaskDag SVG 分层布局）、事件/成果物/决策/详情标签页与来源
会话回链；主列时间线按 occurredAt 归并生命周期流与过程事件流，
语义化中文动作与载荷摘要随事件展示。

影响范围：console
测试确认：npx vitest run（TaskInfoPanel 4 用例 + TaskPages 既有用例全绿）"
```

---

### 任务 13：全量回归与收尾

**文件：** 无新增（只运行验证）

- [x] **步骤 1：Java 全模块回归**

运行：`cd /Users/gecko/code/agentteams-java && mvn -q test`
预期：BUILD SUCCESS，全部模块测试通过（重点确认 agent-gateway / control-plane 的既有 consumer/observation 用例未回归）

- [x] **步骤 2：Console 回归**

运行：`cd console && npx vitest run`
预期：全部通过；`npx tsc -p tsconfig.app.json --noEmit` 无类型错误

- [x] **步骤 3：MCP 契约测试回归**

运行：`python3 scripts/test_agentteams_task_mcp.py`
预期：全部 OK

- [x] **步骤 4：核对规格覆盖并收尾**

对照规格 `docs/superpowers/specs/2026-09-08-task-process-visibility-design.md` 逐节核对：
- 8 环节数据流 → 任务 1-10 每环节一个模块
- 白名单映射表 → 任务 4（runtime 侧）+ 任务 10（控制面侧）
- sequence 双轨 → 任务 5（worker seq）+ 任务 10（服务端 seq）
- 两公理 → 任务 5/7/10 的丢弃路径 + 任务 13 回归
- 右栏面板 → 任务 12
- 二期范围（子任务指派、阻塞恢复）不在本期——TaskDag/标签容器已留扩展点

若有遗漏，补任务后再运行本步骤确认。

- [x] **步骤 5：最终提交（若有零散修正）**

```bash
git status --short
# 确认无未预期文件；如有回归修正，按 chinese-commit-conventions 单独提交：
git add <修正文件>
git commit -m "fix(模块): 修正 xx 回归问题"
```

---

## 计划自检记录

1. **规格覆盖度：** 规格「方案」8 环节 ↔ 任务 1（proto）/2（契约）/3-4（runtime 拦截）/5（worker 出口）/6（worker 接线）/7-8（gateway）/9-10（控制面）/11（来源）/12（console）；「测试策略」7 层 ↔ 各任务测试步骤 + 任务 13 回归；「错误处理」两公理 ↔ 任务 5/7/10 丢弃路径。无遗漏。
2. **占位符扫描：** 任务 8/9/10/11 的测试代码已在自检时按既有测试基建落实（gateway 用 `mock(JetStream.class)`、consumer 用 `mock(Message.class)+getData`、观测适配器用 Mockito 仓库替身、MCP 脚本用本地假服务器），无「照抄既有模式」类占位表述；全部步骤均含完整代码。
3. **类型一致性：** `TaskEventReportCommand(eventId, attemptId, leaseId, occurredAt, agentId, eventType, payloadJson, sequence, correlationId)` 在任务 2 定义、任务 5/7/8/9 使用一致；`RuntimeEvent(taskId, eventType, payloadJson, occurredAt)` 在任务 3 定义、任务 4/6 使用一致；`observed(taskId, runId, eventId, occurredAt, correlationId, eventType, payloadJson)` 在任务 9（调用）与任务 10（实现）签名一致；`reportEvent(taskId, eventType, payloadJson)` 在任务 5（定义）与任务 6（接线）一致。
