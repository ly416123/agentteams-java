# AgentTeams Java Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Build the first Kubernetes-native Java vertical slice: create an Agent, reconcile its Pod, establish a push connection, assign a task, receive an ACK and completion event, and persist an artifact/result.

**Architecture:** PostgreSQL is the source of truth, NATS JetStream carries durable events, gRPC bidirectional streaming provides push delivery, and Java Operator SDK reconciles Kubernetes resources. The first executable slice uses a Fake Agent before real QwenPaw and DeepSeek integration.

**Tech Stack:** Java 17, Maven, Spring Boot 3.x, gRPC Java, Java Operator SDK, Fabric8 Kubernetes Client, JDBC or jOOQ, Flyway, PostgreSQL, NATS JetStream, Kind, Helm, Testcontainers, and OpenTelemetry.

## Scope boundary

This plan delivers only the foundation and push-task vertical slice:

- Agent registration and status.
- Task creation, assignment, acknowledgement, progress, completion, failure, cancellation.
- Durable outbox events and NATS JetStream delivery.
- gRPC bidirectional Agent channel.
- Kubernetes Agent CRD and Pod reconciliation.
- Docker plus Kind development bootstrap.
- Fake Agent end-to-end verification.

The following are deferred to follow-up plans:

- Team and TeamMember domain.
- Matrix and human-in-the-loop rooms.
- MCP server management.
- Web dashboard.
- Multiple production agent runtimes.
- Production authentication, multi-tenancy, HA, and disaster recovery hardening.

## Target repository structure

~~~text
agentteams-java/
├── pom.xml
├── contracts/
│   ├── pom.xml
│   └── src/main/proto/agent_channel.proto
├── domain/
│   ├── pom.xml
│   └── src/main/java/io/agentteams/domain/...
├── control-plane/
│   ├── pom.xml
│   └── src/main/java/io/agentteams/controlplane/...
├── agent-gateway/
│   ├── pom.xml
│   └── src/main/java/io/agentteams/gateway/...
├── operator/
│   ├── pom.xml
│   └── src/main/java/io/agentteams/operator/...
├── integration-tests/
│   ├── pom.xml
│   └── src/test/java/io/agentteams/it/...
├── deploy/
│   ├── helm/agentteams/
│   ├── kind-config.yaml
│   ├── dev-bootstrap.ps1
│   └── dev-bootstrap.sh
├── docs/
│   └── architecture/
└── README.md
~~~

Generated protobuf and gRPC sources are produced during the Maven build and must not be committed as generated source trees.

## Implementation tasks

### Task 1: Create the Maven multi-module skeleton

**Files:**

- Create root pom.xml.
- Create module POMs for contracts, domain, control-plane, agent-gateway, operator, and integration-tests.
- Create .gitignore.
- Create README.md.
- Create an initial Java 17 smoke test under domain/src/test/java.

**Requirements:**

- Use Java 17 and UTF-8 consistently.
- Configure reproducible compiler, Surefire, JaCoCo, and dependency versions in the parent POM.
- Use Spring Boot dependency management only for application modules.
- Add protobuf/gRPC build support to contracts.
- Add Spring Boot, gRPC Netty, Java Operator SDK, Fabric8, Testcontainers, Flyway, PostgreSQL, and NATS dependencies only to modules that need them.
- Keep domain free of Spring, Kubernetes, NATS, and database dependencies.
- Configure Maven profiles for unit tests and integration tests.

**TDD first:**

- Add a Java 17 smoke test proving the domain module is compiled and executed by Maven.
- Run mvn -q test.
- Expected result: all modules compile and the smoke test passes.

**Commit:** build: initialize java multi-module project

### Task 2: Define the push-channel contract

**Files:**

- Create contracts/src/main/proto/agent_channel.proto.
- Create contract compatibility tests under contracts/src/test/java.

**Requirements:**

Define a versioned package io.agentteams.contracts.v1 with:

- ProtocolVersion.
- AgentHello.
- AgentReady.
- TaskAssigned.
- TaskAccepted.
- TaskProgress.
- TaskHeartbeat.
- TaskCompleted.
- TaskFailed.
- ConfigChanged.
- ConfigApplied.
- Ack.
- Error.

Every event must support event_id, agent_id, task_id where applicable, attempt_id where applicable, sequence, and occurred_at.

Use a bidirectional stream:

~~~proto
service AgentChannel {
  rpc Connect(stream AgentMessage) returns (stream ServerMessage);
}
~~~

The contract must define explicit protocol version negotiation and message envelopes so new message types can be added without breaking old agents.

**TDD first:**

- Add tests that serialize and deserialize each required envelope.
- Add a compatibility test asserting the protocol major version is rejected when unsupported and minor versions are accepted according to the compatibility rule.
- Run mvn -pl contracts -am test.
- Expected result: generated classes compile and all contract tests pass.

**Commit:** feat: define versioned agent push protocol

### Task 3: Implement domain state machines

**Files:**

- Create domain/src/main/java/io/agentteams/domain/agent/AgentPhase.java.
- Create domain/src/main/java/io/agentteams/domain/task/TaskPhase.java.
- Create domain/src/main/java/io/agentteams/domain/task/Task.java.
- Create domain/src/main/java/io/agentteams/domain/task/TaskAttempt.java.
- Create domain/src/main/java/io/agentteams/domain/task/TaskTransitionService.java.
- Create domain exceptions and transition result types.
- Create tests under domain/src/test/java/io/agentteams/domain.

**Requirements:**

Support these task transitions:

- Draft to Queued.
- Queued to Assigned.
- Assigned to Accepted.
- Accepted to Running.
- Running to Succeeded.
- Running to Failed.
- Queued or Assigned to Cancelled, subject to cancellation policy.

The transition service must:

- Reject illegal transitions with a typed exception.
- Treat the same event ID as idempotent and return a duplicate result.
- Store attempt ID, lease ID, timestamps, and actor/source.
- Use explicit version numbers for optimistic locking.
- Allow heartbeat renewal only for an active lease and matching attempt.
- Preserve failure code and a redacted failure message.

**TDD first:**

- Write tests for every legal transition.
- Write tests for illegal transitions.
- Write duplicate-event tests.
- Write stale-version tests.
- Write lease-renewal and expired-lease tests.
- Run mvn -pl domain -am test.
- Expected result: domain tests pass without requiring external services.

**Commit:** feat: add agent and task domain state machines

### Task 4: Add PostgreSQL schema and repositories

**Files:**

- Create Flyway migration control-plane/src/main/resources/db/migration/V1__foundation.sql.
- Create records and repositories under control-plane/src/main/java/io/agentteams/controlplane/persistence.
- Create Testcontainers integration tests under control-plane/src/test/java.

**Tables:**

- agents.
- tasks.
- task_attempts.
- task_assignments.
- agent_leases.
- outbox_events.
- domain_events.
- artifacts.
- idempotency_keys.

All mutable tables must include an ID, creation time, update time, and version. Outbox rows must include event ID, aggregate type, aggregate ID, event type, payload, status, attempt count, and next-attempt time.

**Requirements:**

- Use one database transaction for aggregate mutation plus domain-event and outbox insertion.
- Use optimistic locking with a typed optimistic-lock failure.
- Use an idempotency key to return the original response for repeated create requests.
- Store JSON payloads as JSONB.
- Add indexes for task phase, agent phase, outbox status, outbox next-attempt time, and active leases.
- Avoid storing provider secrets or DeepSeek keys in the database.

**TDD first:**

- Write a Testcontainers PostgreSQL test that creates an Agent, Task, TaskAttempt, and Outbox event in one transaction.
- Write a test for concurrent version conflict.
- Write a test that repeated idempotency keys return the original task.
- Run mvn -pl control-plane -am -Dtest=FoundationRepositoryIT test.
- Expected result: PostgreSQL starts, migrations run, and all repository tests pass.

**Commit:** feat: persist foundation aggregates and outbox events

### Task 5: Expose the Control Plane Agent and Task APIs

**Files:**

- Create AgentController.
- Create TaskController.
- Create ApiErrorHandler.
- Create AgentService.
- Create TaskService.
- Create IdempotencyService.
- Add controller tests under control-plane/src/test/java.

**Endpoints:**

~~~text
POST /api/v1/agents
GET  /api/v1/agents/{id}
POST /api/v1/tasks
GET  /api/v1/tasks/{id}
POST /api/v1/tasks/{id}/cancel
~~~

**Requirements:**

- All mutating requests accept Idempotency-Key.
- Agent creation starts in Pending.
- Task creation starts in Draft and has an explicit queue operation in the service layer.
- Controller code must not contain transition or persistence logic.
- Return stable error codes for validation failure, not found, conflict, illegal transition, and unavailable dependency.
- Add Actuator liveness and readiness endpoints.
- Readiness must check database and NATS connectivity.
- Never return secrets or internal stack traces.

**TDD first:**

- Add MockMvc tests for successful creation and reads.
- Add tests for validation failure.
- Add tests for repeated idempotency key.
- Add tests for illegal cancellation and optimistic conflict.
- Run mvn -pl control-plane -am test.
- Expected result: API tests pass with container-backed persistence where required.

**Commit:** feat: expose agent and task control plane APIs

### Task 6: Implement the transactional outbox relay and NATS JetStream publisher

**Files:**

- Create OutboxRelay.
- Create NatsEventPublisher.
- Create EventSubjects.
- Create publisher integration tests.

**Subjects:**

~~~text
agent.events.<agentId>
task.events.<taskId>
control.events
deadletter.events
~~~

**Requirements:**

- Claim pending rows with FOR UPDATE SKIP LOCKED.
- Use the outbox event ID as the NATS message ID for deduplication.
- Mark an event published only after a successful JetStream publish acknowledgement.
- Retry failures with bounded exponential backoff.
- Move an event to dead letter after 10 attempts.
- Redact task contents and credentials in dead-letter logs.
- Keep relay state in the database; do not use in-memory scheduling as the source of truth.
- Make relay concurrency configurable.

**TDD first:**

- Use Testcontainers NATS with JetStream enabled.
- Test successful publish and status update.
- Test duplicate publish and consumer deduplication.
- Test retry and dead-letter behavior.
- Run mvn -pl control-plane -am -Dtest=OutboxRelayIT test.
- Expected result: events are durably published and failure handling is deterministic.

**Commit:** feat: relay outbox events through nats jetstream

### Task 7: Implement the Agent Gateway gRPC push service

**Files:**

- Create agent-gateway/src/main/java/io/agentteams/gateway/AgentGatewayApplication.java.
- Create AgentChannelService.
- Create ConnectionRegistry.
- Create CommandDeliveryService.
- Create InboundEventHandler.
- Create gateway tests under agent-gateway/src/test/java.

**Requirements:**

- Register an Agent only after a valid Hello and protocol negotiation.
- Track connection UUID, agent ID, runtime, capabilities, last-seen time, and last acknowledged sequence.
- Deliver assignments through the active bidirectional stream.
- Forward accepted, progress, heartbeat, completed, and failed events to the domain/application layer.
- Ignore duplicate inbound event IDs.
- Reject stale connection messages after reconnect.
- Keep task state in PostgreSQL, not in the connection registry.
- Support replay of unacknowledged commands from the durable event store.
- Use ordered per-Agent sequences.
- Define the first authentication seam so mTLS or signed token validation can be added without changing the domain contract.

**TDD first:**

- Test Hello registration.
- Test Ready status update.
- Test TaskAssigned delivery.
- Test accepted event persistence.
- Test duplicate inbound event handling.
- Test stale connection rejection.
- Test reconnect and replay of unacknowledged commands.
- Run mvn -pl agent-gateway -am test.
- Expected result: a reconnecting Agent does not lose an assignment and duplicate messages are harmless.

**Commit:** feat: add grpc agent gateway and push delivery

### Task 8: Build the Fake Agent and prove the first end-to-end slice

**Files:**

- Create integration-tests/src/test/java/io/agentteams/it/FakeAgent.java.
- Create TaskPushE2ETest.
- Update application services only where required to support queue-to-assignment.

**Requirements:**

The Fake Agent must:

1. Connect to the gRPC Gateway.
2. Send Hello and Ready.
3. Accept a pushed assignment.
4. Send progress and heartbeats.
5. Upload a small artifact to a Testcontainers MinIO instance.
6. Send completion with artifact metadata.
7. Reconnect and prove replay/idempotency.

Queue-to-assignment must:

- Select a Ready Agent matching capability constraints.
- Create a TaskAttempt, Assignment, and Lease.
- Insert a TaskAssigned outbox event.
- Expire and recover leases deterministically in tests.

**TDD first:**

- Start PostgreSQL, NATS JetStream, and MinIO with Testcontainers.
- Start the Control Plane and Gateway.
- Create an Agent.
- Connect the Fake Agent.
- Create and queue a Task.
- Assert pushed assignment, acknowledgement, progress, completion, Succeeded task state, and Artifact row.
- Send duplicate completion and assert no duplicate attempt or artifact.
- Stop and reconnect the Fake Agent before completion and assert replay.
- Run mvn -pl integration-tests -am test.
- Expected result: the complete push path passes without Matrix or polling.

**Commit:** test: verify first agent task push vertical slice

### Task 9: Add the Kubernetes Operator and Agent CRD

**Files:**

- Create operator/src/main/java/io/agentteams/operator/OperatorApplication.java.
- Create AgentReconciler.
- Create AgentResource, AgentSpec, and AgentStatus.
- Add CRD YAML under operator/src/main/resources.
- Add operator tests under operator/src/test/java.
- Add Helm deployment templates under deploy/helm/agentteams/templates.

**CRD requirements:**

The Agent spec must include:

- Runtime.
- Image.
- Resource requests and limits.
- Desired state.
- Capabilities.
- Optional configuration references.

The Agent status must include:

- Conditions: InfrastructureReady, CredentialsReady, ConfigReady, ChannelReady, RuntimeReady, Ready.
- Observed generation.
- Pod name.
- Connection status.
- Last error code and redacted message.

**Reconciler requirements:**

- Create or update only the Pod, ServiceAccount, ConfigMap, and Secret references owned by the Agent.
- Use owner references and finalizers where needed.
- Treat Stopped as a valid desired state.
- Update status conditions on Pod readiness and gateway connection.
- Be idempotent and safe on retries.
- Do not synchronize Matrix, S3, or DeepSeek state from the reconciler.
- Do not put task scheduling logic in the Operator.

**TDD first:**

- Use Fabric8 Kubernetes mock or envtest-style support.
- Test create, update, image change, Stopped, Pod Ready, and deletion behavior.
- Test observed generation and condition transitions.
- Run mvn -pl operator -am test.
- Expected result: reconciliation converges to the same resources after repeated invocations.

**Commit:** feat: add kubernetes agent operator

### Task 10: Add Docker and Kind development bootstrap

**Files:**

- Create deploy/kind-config.yaml.
- Create deploy/dev-bootstrap.ps1.
- Create deploy/dev-bootstrap.sh.
- Create Helm chart under deploy/helm/agentteams.
- Add development values file.
- Add Dockerfiles for control-plane, gateway, and operator as needed.

**Requirements:**

- Use Docker as the primary local dependency boundary.
- Use a small Kind cluster with one control-plane and one worker.
- Map HTTP and gRPC ports for local testing.
- Build local images and load them into Kind.
- Install PostgreSQL, NATS JetStream, and MinIO through the development Helm chart.
- Install the Control Plane, Gateway, and Operator.
- Wait for readiness and print API, gRPC, and artifact endpoints.
- Make the bootstrap script safe to re-run.
- Store the DeepSeek API key only in a Kubernetes Secret or environment variable supplied locally.
- Keep DeepSeek base URL and model name configurable and non-secret.
- Never commit a real key, token, or credential.

**Validation commands:**

~~~powershell
. \deploy\dev-bootstrap.ps1
kubectl get pods -n agentteams
helm list -n agentteams
~~~

The bootstrap must finish with all development workloads Ready and the API health endpoint returning HTTP 200.

**TDD first:**

- Add shell-level validation for missing Docker, Kind, kubectl, and Helm.
- Add a smoke test that creates an Agent and verifies the Operator creates its Pod.
- Run the bootstrap against a clean local Kind cluster.
- Re-run it against the existing cluster and verify convergence.
- Expected result: the environment can be recreated without manual database or message-broker configuration.

**Commit:** build: add docker kind and helm development environment

## Follow-up implementation plans

The following phases are part of the approved roadmap. Each phase is implemented only after the preceding phase has passed its acceptance tests. The full architecture and cross-phase decisions are documented in docs/superpowers/specs/2026-08-16-agentteams-java-full-roadmap-design.md.

### Phase 11: DeepSeek Manager and typed tool layer

**Goal:** Convert human or system intent into validated, auditable Control Plane commands.

**Modules and files:**

- Add manager module with ModelProvider, DeepSeekProvider, OpenAICompatibleProvider, PromptTemplateRegistry, ContextAssembler, StructuredOutputValidator, ManagerToolRegistry, ManagerSessionService, ModelCallAuditService, and ApprovalPolicyService.
- Add model call, manager session, and approval records to the Control Plane schema.
- Add JSON Schema definitions for task, agent, team, artifact, and reporting intents.
- Add tool permission and idempotency policies.

**Requirements:**

- Use DeepSeek through an OpenAI-compatible endpoint.
- Keep base URL and model configurable; keep the API key in a Kubernetes Secret.
- Reject invalid structured output before any side effect.
- Expose only typed business tools; never expose Kubernetes or arbitrary HTTP tools.
- Retry transient provider errors with a bounded budget.
- Persist provider, model, latency, token counts, and redacted hashes.
- Move repeated parsing or policy failures to approval-required state.

**Tests and acceptance:**

- Contract tests for every structured intent.
- Mock provider tests for timeout, malformed JSON, retry, and policy rejection.
- Integration test proving one idempotent tool call creates one Task.
- Verify credentials never occur in logs, prompts persisted to storage, or events.

**Commit:** feat: add deepseek manager and typed tools

### Phase 12: QwenPaw runtime adapter

**Goal:** Connect the first production Agent runtime without leaking runtime-specific logic into the Control Plane.

**Modules and files:**

- Add runtime SPI with start, stop, acceptTask, cancelTask, applyConfig, reportProgress, and collectResult.
- Add FakeRuntime and QwenPawRuntime implementations.
- Add Agent client library for protocol negotiation, reconnect, heartbeat, lease renewal, artifact upload, and event acknowledgement.
- Add QwenPaw Agent image and Helm values.

**Requirements:**

- Report runtime name, version, protocol version, capabilities, concurrency, workspace limits, and configuration version.
- Use isolated per-task workspace directories.
- Generate a standard result manifest.
- Upload large outputs directly to object storage.
- Preserve old configuration if applying a new snapshot fails.

**Tests and acceptance:**

- FakeRuntime contract tests.
- QwenPaw adapter tests with a process or SDK test double.
- Kind test from Pod creation to AgentReady.
- Reconnect-before-completion test.
- Duplicate completion and typed runtime failure tests.

**Commit:** feat: add qwenpaw runtime adapter

### Phase 13: ConfigSnapshot and artifact lifecycle

**Goal:** Replace directory-wide synchronization with immutable versioned configuration and direct artifact transfer.

**Modules and files:**

- Add ConfigSnapshot, ConfigFile, ConfigBinding, and ConfigApplyRecord domain models.
- Add database migrations and object-storage manifest service.
- Add pre-signed upload and download URL service.
- Add Agent ConfigChanged and ConfigApplied handling.
- Add retention and temporary-upload cleanup jobs.

**Requirements:**

- Build effective configuration from Agent Base Config, Team Overlay, and Task Overlay.
- Store manifest, checksum, size, actor, and version in PostgreSQL.
- Store file content and artifacts in S3-compatible storage.
- Use staging directories and atomic activation.
- Make artifact metadata insertion idempotent.

**Tests and acceptance:**

- Checksum mismatch does not activate a snapshot.
- Failed config apply keeps the prior version.
- Large artifact upload bypasses the Control Plane data path.
- Duplicate artifact completion does not duplicate metadata.
- Expired temporary uploads are cleaned safely.

**Commit:** feat: add versioned config and artifact lifecycle

### Phase 14: Team, Team Leader, and team scheduling

**Goal:** Add declaration of teams while keeping task scheduling in the Control Plane.

**Modules and files:**

- Add Team and TeamMember CRDs.
- Add TeamReconciler and status conditions.
- Add teams, team_memberships, team_tasks, team_task_assignments, team_policies, and team_events tables.
- Add Team overlay configuration composition.
- Add capability, concurrency, priority, and lease-aware scheduler constraints.

**Requirements:**

- Operator manages Team-owned Kubernetes resources only.
- Control Plane owns runtime membership, task assignments, retries, and policy decisions.
- Team overlays must not mutate Agent base configuration.
- Removing a member must have an explicit policy for active attempts.
- Support leader reference and Team Leader runtime without special Control Plane branches.

**Tests and acceptance:**

- CRD reconciliation convergence tests.
- Capability and membership scheduling tests.
- Member removal and active-attempt policy tests.
- Control Plane and Operator restart recovery tests.

**Commit:** feat: add team crd and team scheduling

### Phase 15: Matrix AppService human-collaboration adapter

**Goal:** Provide human-readable collaboration and intervention without making Matrix the task database.

**Modules and files:**

- Add matrix-adapter module or deployment.
- Add AppService transaction endpoint, Matrix identity binder, room mapping, Inbox repository, command parser, outbound event projector, and delivery retry worker.
- Add conversation, matrix_inbox_events, and matrix_outbox_messages tables.

**Requirements:**

- Use AppService HTTP transactions as the normal inbound path.
- Deduplicate Matrix events by event ID and transaction ID.
- Convert commands such as start, cancel, retry, pause, approve, reject, and status into typed Control Plane commands.
- Bind Matrix users to platform identities and permissions.
- Project domain events to rooms asynchronously.
- Matrix send failure must not roll back business state.

**Tests and acceptance:**

- Duplicate transaction tests.
- Identity and RBAC tests.
- Matrix outage and outbound replay tests.
- End-to-end command test from Matrix event to task state.
- Verify incomplete Matrix history does not prevent status reads.

**Commit:** feat: add matrix appservice adapter

### Phase 16: Security and tenant isolation

**Goal:** Secure all state-changing paths and isolate tenants, projects, teams, agents, and artifacts.

**Modules and files:**

- Add OIDC JWT validation and platform identity mapping.
- Add service-layer authorization policies and action/resource matrix.
- Add Agent session-token or mTLS validation.
- Add Kubernetes Secret references and External Secrets integration seam.
- Add NetworkPolicy, ServiceAccount, Role, and RoleBinding Helm templates.
- Add audit events for permissions, assignments, approvals, cancellations, and resource changes.

**Requirements:**

- Separate DeepSeek, Matrix, S3, NATS, and Kubernetes credentials.
- Worker Pods must not receive cluster-wide Kubernetes credentials.
- Artifact URLs must be short-lived.
- Credentials must be redacted from logs, prompts, payloads, and audit details.
- Authorization must be enforced below controllers and above repositories.

**Tests and acceptance:**

- OIDC role and scope tests.
- Expired and revoked Agent session tests.
- Cross-tenant access denial tests.
- Secret rotation without source changes.
- NetworkPolicy and least-privilege manifest tests.

**Commit:** feat: add security and tenant isolation

### Phase 17: Observability, high availability, and recovery

**Goal:** Make the platform diagnosable, restart-safe, and production-operable.

**Modules and files:**

- Add OpenTelemetry tracing and context propagation.
- Add metrics for queue latency, assignments, heartbeats, reconnects, outbox backlog, retries, outcomes, artifacts, and reconciles.
- Add dashboards and alert rules.
- Add Control Plane and Gateway readiness/liveness behavior.
- Add Scheduler database lease and Operator leader election.
- Add backup and restore scripts for PostgreSQL and object metadata.
- Add fault-injection integration tests.

**Requirements:**

- Trace Matrix event, Manager call, task creation, assignment, gRPC delivery, Agent execution, artifact upload, and completion.
- Keep task state out of process memory.
- Recover pending Outbox events after NATS restart.
- Recover assignments after Gateway restart.
- Define retryable, terminal, and unknown failure classes.
- Document RPO, RTO, restore validation, and rollback strategy.

**Tests and acceptance:**

- Control Plane restart recovery.
- Gateway restart and assignment replay.
- NATS outage and Outbox drain.
- PostgreSQL restore reconstruction test.
- Stuck lease and Outbox backlog alerts.
- Kind smoke, fault injection, and basic load tests.

**Commit:** feat: add observability ha and recovery controls

## Verification checklist

Before claiming the foundation is complete:

- mvn test passes from the repository root.
- The Fake Agent end-to-end test passes with PostgreSQL, NATS JetStream, and MinIO containers.
- A Kind deployment creates an Agent Pod through the Operator.
- Reconnecting an Agent does not lose an assignment.
- Duplicate inbound events do not create duplicate task attempts or artifacts.
- The Docker and Kind bootstrap can be run twice without manual repair.
- No Matrix polling or directory-wide file polling is present in the foundation path.
- No DeepSeek API key or other credential is present in Git.
- The design preserves a clear seam for real QwenPaw and DeepSeek integration.
