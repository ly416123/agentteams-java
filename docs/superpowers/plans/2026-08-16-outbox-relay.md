# Transactional Outbox Relay and NATS Publisher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish transactional outbox events to NATS JetStream with database-backed claiming, retry, deduplication, and dead-letter handling.

**Architecture:** PostgreSQL owns relay state. A short transaction claims due rows with `FOR UPDATE SKIP LOCKED` and a recoverable `IN_FLIGHT` lease; a worker publishes an `EventEnvelope` to JetStream and persists `PUBLISHED`, retry, or `DEAD_LETTER` afterward. NATS is an external delivery adapter and never becomes the source of relay state.

**Tech Stack:** Java 17, Spring Boot JDBC, PostgreSQL/Flyway, jnats 2.20.5, JUnit 5, AssertJ, Testcontainers PostgreSQL and NATS.

### Implementation status (2026-08-21)

已完成。事件主题与信封、JetStream 发布、数据库 Claim、重试/死信、敏感信息脱敏和 Spring 定时装配均已实现，并通过单元测试和 `OutboxRelayIT`。

### Implementation status (2026-08-21)

Completed in the current branch. Event subjects/envelopes, JetStream publishing with event-id message IDs, transactional claiming, retry/dead-letter transitions, redacted dead-letter logging, and relay wiring are implemented. Coverage is provided by `OutboxRelayTest`, `OutboxRelayIT`, and the publisher/repository tests; the checklist below remains as historical implementation tracking.

---

### Task 1: Define event subjects and envelope contract

**Files:**
- Create: `control-plane/src/main/java/io/agentteams/controlplane/outbox/EventSubjects.java`
- Create: `control-plane/src/main/java/io/agentteams/controlplane/outbox/EventEnvelope.java`
- Test: `control-plane/src/test/java/io/agentteams/controlplane/outbox/EventSubjectsTest.java`
- Test: `control-plane/src/test/java/io/agentteams/controlplane/outbox/EventEnvelopeTest.java`

- [x] Write tests for agent, task, control, and dead-letter subjects and for all seven envelope fields.
- [x] Run the focused tests and confirm failure because the new types do not exist.
- [x] Implement immutable records/constants with stable lowercase field names for JSON serialization.
- [x] Run the focused tests and confirm they pass.

### Task 2: Add publisher seam and NATS implementation

**Files:**
- Create: `control-plane/src/main/java/io/agentteams/controlplane/outbox/EventPublisher.java`
- Create: `control-plane/src/main/java/io/agentteams/controlplane/outbox/NatsEventPublisher.java`
- Test: `control-plane/src/test/java/io/agentteams/controlplane/outbox/NatsEventPublisherTest.java`

- [x] Write a fake JetStream test seam proving subject, envelope bytes, and `event_id` message ID are passed to JetStream and that an acknowledgement is required.
- [x] Run the test and confirm the expected missing-implementation failure.
- [x] Implement the publisher with Jackson serialization and jnats `PublishOptions.messageId`.
- [x] Run the focused test and confirm it passes.

### Task 3: Make outbox claiming and state transitions transactional

**Files:**
- Modify: `control-plane/src/main/resources/db/migration/V1__foundation.sql`
- Modify: `control-plane/src/main/java/io/agentteams/controlplane/persistence/OutboxEventRepository.java`
- Test: `control-plane/src/test/java/io/agentteams/controlplane/persistence/OutboxEventRepositoryTest.java`

- [x] Write tests for SQL claim semantics, success, retry scheduling, tenth-attempt dead-lettering, and expired in-flight reclaim.
- [x] Run the tests to observe the expected failure.
- [x] Add a claim lease/status representation and repository methods that execute claim/update operations using JDBC transactions supplied by `FoundationPersistenceService`.
- [x] Keep the existing transactional insert behavior unchanged and run repository tests again.

### Task 4: Implement relay concurrency, retry, and redacted dead-letter logging

**Files:**
- Create: `control-plane/src/main/java/io/agentteams/controlplane/outbox/OutboxRelayProperties.java`
- Create: `control-plane/src/main/java/io/agentteams/controlplane/outbox/OutboxRelay.java`
- Modify: `control-plane/src/main/java/io/agentteams/controlplane/ControlPlaneConfiguration.java`
- Modify: `control-plane/src/main/resources/application.yml`
- Test: `control-plane/src/test/java/io/agentteams/controlplane/outbox/OutboxRelayTest.java`

- [x] Write tests proving configurable worker count, acknowledgement-before-published ordering, bounded exponential retry, dead-letter after attempt 10, and redaction of task content/credentials.
- [x] Run them and verify the expected failure.
- [x] Implement a scheduled relay with a fixed worker pool, database claims, publisher calls, and persisted outcomes. Use a clock and sleeper abstraction so retry tests remain deterministic.
- [x] Wire properties and a conditional Spring bean without coupling the relay to HTTP controllers or domain services.
- [x] Run the focused tests and confirm they pass.

### Task 5: Add NATS JetStream integration coverage

**Files:**
- Create: `control-plane/src/test/java/io/agentteams/controlplane/outbox/OutboxRelayIT.java`
- Modify: `control-plane/pom.xml` only if the NATS Testcontainers dependency is required by the chosen test image/API.

- [x] Write Testcontainers tests for successful publication/status, duplicate message-ID deduplication, retry after a transient publish failure, and dead-letter after ten failures.
- [x] Ensure tests use `@Testcontainers(disabledWithoutDocker = true)` and do not require Docker at compile time.
- [x] Run static inspection and the requested command `mvn -pl control-plane -am -Dtest=OutboxRelayIT test`; report that execution is unavailable if Maven/Java/Docker are absent.

### Task 6: Final verification

**Files:**
- Inspect only all changed files under `agentteams-java`.

- [x] Run `rg` checks for required subjects, SQL locking, message ID, attempt limit, and secret redaction patterns.
- [x] Review `git diff --check` and the full diff for scope violations.
- [x] Report exact verification results and environment limitations without claiming unavailable tests passed.
