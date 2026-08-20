# Agent Gateway gRPC Push Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Add a protocol-safe, reconnectable bidirectional Agent gRPC gateway whose durable ports handle command replay and inbound-event idempotency.

**Architecture:** `AgentChannelService` coordinates `ConnectionRegistry`, `CommandDeliveryService`, `InboundEventHandler`, and injected ports. The registry contains connection metadata only; durable command and inbound-event state stays behind ports that can later be backed by PostgreSQL/outbox adapters.

**Tech Stack:** Java 17, Spring Boot, gRPC Java, generated protobuf contracts, JUnit 5, AssertJ.

### Implementation status (2026-08-21)

Completed in the current branch. `ConnectionRegistry`, command delivery, inbound-event handling, and the gRPC service are implemented with focused unit coverage in `agent-gateway/src/test`. The gateway module test suite and the infrastructure integration suite have passed; the original checklist below is retained as historical design tracking.

---

### Task 1: Establish gateway ports and connection model

**Files:**
- Create: `agent-gateway/src/main/java/io/agentteams/gateway/AgentConnection.java`
- Create: `agent-gateway/src/main/java/io/agentteams/gateway/AgentGatewayPorts.java`
- Create: `agent-gateway/src/main/java/io/agentteams/gateway/ConnectionRegistry.java`

- [ ] Define immutable agent profile, sequenced command, authentication decision, and application handler port contracts.
- [ ] Implement atomic current-connection replacement by agent ID and monotonic acknowledgement tracking.
- [ ] Keep registry data limited to connection/session metadata and stream sink.

### Task 2: Add failing protocol and delivery tests

**Files:**
- Create: `agent-gateway/src/test/java/io/agentteams/gateway/AgentChannelServiceTest.java`
- Create: `agent-gateway/src/test/java/io/agentteams/gateway/CommandDeliveryServiceTest.java`
- Create: `agent-gateway/src/test/java/io/agentteams/gateway/InboundEventHandlerTest.java`

- [ ] Test Hello/Ready negotiation and rejection before registry mutation.
- [ ] Test TaskAssigned persistence and active-stream delivery with an ordered sequence.
- [ ] Test accepted forwarding, duplicate suppression, stale rejection, reconnect replacement, and replay.

### Task 3: Implement command and inbound flows

**Files:**
- Create: `agent-gateway/src/main/java/io/agentteams/gateway/CommandDeliveryService.java`
- Create: `agent-gateway/src/main/java/io/agentteams/gateway/InboundEventHandler.java`

- [ ] Persist commands before sending, replay only durable unacknowledged commands, and close the send race through current-session checks.
- [ ] Update last-seen/ack state, deduplicate inbound IDs through the durable port, and route the five required event kinds to the application handler.

### Task 4: Implement the gRPC service and application entry point

**Files:**
- Create: `agent-gateway/src/main/java/io/agentteams/gateway/AgentChannelService.java`
- Create: `agent-gateway/src/main/java/io/agentteams/gateway/AgentGatewayApplication.java`

- [ ] Enforce Hello-first ordering, authentication, protocol compatibility, Ready response, registration, and replay.
- [ ] Close and remove only the same current connection on stream termination.
- [ ] Provide the Spring Boot main class without introducing a control-plane JDBC dependency.

### Task 5: Verify and self-review

- [ ] Run `mvn -pl agent-gateway -am test` if Maven is available; otherwise record the environment limitation.
- [ ] Run source/static checks available without Java/Maven, inspect the complete diff, and check every requested behavior against tests and ports.
