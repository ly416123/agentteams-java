# AgentTeams Java Full Roadmap Architecture Design

Date: 2026-08-16

Status: Approved by user; implementation plan expansion in progress

## 1. Purpose

This document extends the approved foundation design into a complete technical roadmap for the Kubernetes-only Java implementation.

The system will be built around:

- Spring Boot Java control plane.
- Java Operator SDK and Fabric8 for Kubernetes reconciliation.
- PostgreSQL as the business source of truth.
- NATS JetStream for durable event delivery.
- gRPC bidirectional streams for Agent push delivery.
- S3-compatible object storage for configuration snapshots and artifacts.
- DeepSeek as the first model provider through an OpenAI-compatible API.
- QwenPaw as the first production Agent runtime.
- Matrix as a human and collaboration adapter, not as the task database.

## 2. Global architecture

~~~text
Dashboard / API Client
        |
        v
Control Plane
  - REST API
  - Task service
  - Scheduler
  - Manager orchestration
  - Outbox relay
  - Authorization
  - Audit
        |
  +-----+-----------+----------------+
  |                 |                |
PostgreSQL     NATS JetStream     S3 / OSS
  |                 |                |
  v                 v                v
Agent Gateway   Async Consumers   Config / Artifacts
  |
  | gRPC bidirectional push
  |
Agent Pods managed by Kubernetes Operator

Matrix Adapter <-> Tuwunel
        |
        +-> Control Plane commands and event projections
Java Operator -> Kubernetes API
~~~

The initial deployment keeps business modules in one Control Plane deployment. Agent Gateway and Operator are separate deployments because they have different lifecycle and scaling characteristics. Matrix Adapter can initially be a Control Plane module and later become an independent deployment.

## 3. Ownership boundaries

### Control Plane

Owns users, projects, agents, teams, tasks, attempts, assignments, leases, permissions, domain transitions, idempotency, audit records, and business events.

It must not own long-lived Agent sockets or directly manipulate runtime-specific files.

### Agent Gateway

Owns active gRPC connections, protocol negotiation, command delivery, acknowledgement tracking, reconnect replay, and connection health.

It does not own task state. PostgreSQL and durable events remain authoritative after a Gateway restart.

### Java Operator

Owns Kubernetes resources derived from CRDs:

- Pods.
- Services.
- ServiceAccounts.
- ConfigMaps.
- Secret references.
- Kubernetes status conditions.

It does not implement task scheduling, Matrix synchronization, object-storage workflows, or model calls.

### Matrix Adapter

Owns Matrix AppService transactions, room mapping, command parsing, outbound message projection, and Matrix identity binding.

Matrix timelines are not used as the authoritative task state.

### Object Storage

Owns large immutable files:

- Config bundles.
- Task specifications.
- Logs.
- Results.
- Artifacts.

PostgreSQL stores metadata, hashes, references, and lifecycle state.

## 4. Common event and command rules

All state-changing commands must include:

- command_id or idempotency key.
- actor_id.
- source.
- correlation_id.
- trace_id.
- expected_version where optimistic locking applies.

All durable events must include:

- event_id.
- event_type.
- aggregate_type.
- aggregate_id.
- occurred_at.
- schema_version.
- correlation_id.
- causation_id.
- redacted payload.

Delivery semantics are at-least-once. Consumers must deduplicate by event_id and enforce aggregate version ordering where required.

## 5. DeepSeek Manager phase

### 5.1 Responsibility

The Manager turns human or system intent into validated control-plane commands. It does not receive Kubernetes credentials and does not modify Agent Pods directly.

Flow:

~~~text
Human request
  -> Matrix Adapter or REST API
  -> Manager context builder
  -> DeepSeek model call
  -> structured intent
  -> policy and permission validation
  -> typed Control Plane tool
  -> domain event
  -> Manager event subscription
  -> user-facing summary
~~~

### 5.2 Module design

Add a manager module with:

- ModelProvider interface.
- DeepSeekProvider.
- OpenAICompatibleProvider.
- PromptTemplateRegistry.
- ContextAssembler.
- StructuredOutputValidator.
- ManagerToolRegistry.
- ManagerSessionService.
- ModelCallAuditService.
- ApprovalPolicyService.

The model provider must be replaceable without changing Manager orchestration or domain services.

### 5.3 Structured intent

The model must return schema-validated objects such as:

~~~json
{
  "intent": "CREATE_TASK",
  "title": "Implement login",
  "description": "Implement and test the login flow",
  "required_capabilities": ["java", "spring"],
  "priority": 50,
  "requires_approval": false
}
~~~

Free-form model output cannot directly trigger an external side effect.

### 5.4 Tool policy

Tools are grouped into task, agent, team, artifact, and reporting operations. Each tool has:

- Input schema.
- Required permission.
- Idempotency policy.
- Audit event type.
- Approval requirement.
- Timeout and retry policy.

The Manager may call only the tools granted to its identity. It cannot call Kubernetes, PostgreSQL, or arbitrary HTTP tools.

### 5.5 Failure handling

- Retry transient provider failures with a bounded budget.
- Retry one time for schema repair.
- Persist all model calls with provider, model, latency, token counts, and redacted request/response hashes.
- Move repeated parsing or policy failures to an approval-required state.
- Never treat a model timeout as task success.
- Enforce prompt and output size limits.

### 5.6 Acceptance criteria

- A DeepSeek request can create a Task through a typed tool.
- Invalid model JSON is rejected without a side effect.
- Repeated tool calls with the same idempotency key create one Task.
- Model credentials never appear in logs or persisted events.
- A model outage leaves existing task state consistent.

## 6. QwenPaw runtime phase

### 6.1 Runtime abstraction

Define a runtime-neutral Agent protocol and runtime SPI:

~~~text
AgentRuntime
  start
  stop
  acceptTask
  cancelTask
  applyConfig
  reportProgress
  collectResult
~~~

Implement:

- FakeRuntime for tests.
- QwenPawRuntime for the first production runtime.
- Future OpenClawRuntime or other runtimes without Control Plane branching.

### 6.2 Agent Pod design

A runtime Pod contains the QwenPaw process and either an embedded Agent channel client or a small sidecar. The first implementation should prefer an embedded client when the QwenPaw integration point is stable; otherwise use a sidecar to keep channel and artifact concerns isolated.

The Agent client handles:

- Hello and protocol negotiation.
- Ready state.
- gRPC reconnect.
- Heartbeats and lease renewal.
- Assignment acknowledgement.
- Progress and completion events.
- Configuration download and apply acknowledgement.
- Artifact upload.

### 6.3 Capability negotiation

At connection time the Agent sends:

- Runtime name and version.
- Protocol version.
- Capability names and versions.
- Maximum concurrent tasks.
- Workspace and artifact limits.
- Configuration version.

Assignment selection must match required capabilities before a lease is created.

### 6.4 Execution isolation

Each task receives an isolated workspace:

~~~text
/workspace/task
/workspace/input
/workspace/output
/workspace/logs
/workspace/artifacts
~~~

The runtime writes a standardized result manifest and uploads large files directly to object storage. The completion event contains metadata and hashes rather than large content.

### 6.5 Acceptance criteria

- QwenPaw can connect, become Ready, accept a task, report progress, and complete.
- Reconnect before completion replays the assignment safely.
- Duplicate completion is ignored.
- Runtime failures produce a typed failure code and do not silently succeed.
- The same QwenPaw image can be managed by the Operator without runtime-specific control-plane code.

## 7. Configuration snapshots and artifacts phase

### 7.1 Versioned configuration

Configuration is immutable and versioned:

~~~text
Create ConfigSnapshot version 42
  -> publish ConfigChanged version 42
  -> Agent downloads manifest using pre-signed URLs
  -> Agent validates hashes
  -> Agent applies into a staging directory
  -> Agent atomically switches active version
  -> Agent sends ConfigApplied version 42
~~~

Add tables:

- config_snapshots.
- config_files.
- config_bindings.
- config_apply_records.

PostgreSQL stores version, manifest, checksum, size, source, actor, and binding metadata. Object storage stores file content.

### 7.2 Artifact lifecycle

Object paths:

~~~text
tasks/{taskId}/spec.json
tasks/{taskId}/attempts/{attemptId}/result.json
tasks/{taskId}/attempts/{attemptId}/logs/*
tasks/{taskId}/attempts/{attemptId}/artifacts/*
~~~

Use pre-signed upload and download URLs. Artifact metadata is inserted idempotently after checksum validation.

### 7.3 Retention

Support:

- Task-level retention policy.
- Failed-task longer retention.
- Temporary-upload cleanup.
- Result immutability.
- Explicit deletion audit.
- Legal or operational hold in a later phase.

### 7.4 Acceptance criteria

- Configuration updates are checksum-validated and acknowledged.
- A failed config apply keeps the previous active version.
- Large artifacts do not pass through the Control Plane process.
- Duplicate artifact completion does not create duplicate metadata.
- Expired temporary uploads are cleaned up safely.

## 8. Team and Team Leader phase

### 8.1 Team CRD

The Team CRD declares desired membership, leader reference, capabilities, and policy. The Operator creates or updates only the Kubernetes resources needed to represent that intent.

Example fields:

~~~text
spec.leaderRef
spec.members[].agentRef
spec.members[].role
spec.members[].capabilities
spec.policy.maxConcurrentTasks
spec.policy.requireApproval
spec.workspaceRef
spec.channelBindingRef
~~~

### 8.2 Runtime state

PostgreSQL owns:

- teams.
- team_memberships.
- team_tasks.
- team_task_assignments.
- team_policies.
- team_events.

CRD status is a projection of infrastructure and effective configuration state, not the task history.

### 8.3 Configuration composition

Effective configuration is:

~~~text
Agent Base Config
  + Team Overlay
  + Task Overlay
  = Effective ConfigSnapshot
~~~

Every effective configuration has its own immutable version and checksum.

### 8.4 Scheduling policy

Initial scheduling uses:

- Capability match.
- Ready state.
- Active lease count.
- Maximum concurrency.
- Priority.
- Team membership.
- Retry and timeout policy.

Later scheduling can add cost, affinity, dedicated workers, and cross-team sharing.

### 8.5 Acceptance criteria

- A Team can declare members and a leader.
- Team overlays do not mutate base Agent configuration.
- A task is assigned only to eligible Team members.
- Removing a member affects future assignments and follows an explicit policy for active attempts.
- Team state remains correct after Operator restart or Control Plane restart.

## 9. Matrix human-collaboration phase

### 9.1 Adapter responsibility

Use Matrix AppService HTTP transactions as the normal inbound path. The adapter validates the transaction, stores an Inbox record, parses commands, and invokes typed Control Plane commands.

It must not infer task state from timeline text.

### 9.2 Room mapping

Add conversation mapping for:

- Project rooms.
- Team rooms.
- Task rooms.
- Manager direct conversations.
- Approval rooms.

A room maps to a project, team, task, or manager session through explicit database records.

### 9.3 Commands

Support commands such as:

~~~text
/start task-123
/cancel task-123
/retry task-123
/pause task-123
/approve task-123
/reject task-123
/status task-123
~~~

Every command binds Matrix user identity to platform permissions, uses an idempotency key, and creates an audit record.

### 9.4 Outbound projection

The adapter consumes domain events and renders user-facing messages for:

- Task created.
- Task assigned.
- Progress changed.
- Approval required.
- Task succeeded.
- Task failed.
- Artifact available.

Matrix send failures are retried independently and never roll back the business transaction.

### 9.5 Acceptance criteria

- Matrix outage does not corrupt task state.
- Duplicate Matrix transactions do not repeat commands.
- Unauthorized users cannot cancel, approve, or assign tasks.
- Task state can be displayed from Control Plane data even when Matrix history is incomplete.
- Outbound messages can be replayed from durable events.

## 10. Security phase

### 10.1 Identity

- Human users: OIDC and JWT.
- Matrix users: verified Matrix identity binding.
- Agents: short-lived session tokens, with mTLS preferred in production.
- Operator: least-privilege Kubernetes ServiceAccount.
- Manager: scoped business tools only.

### 10.2 Authorization

Use tenant, project, team, task, and artifact scopes. Actions include:

~~~text
task:create
task:assign
task:cancel
task:approve
task:read
artifact:read
agent:diagnose
team:manage
~~~

Authorization is enforced in the Control Plane service layer, not only at the HTTP controller.

### 10.3 Secrets and network

- Separate DeepSeek, Matrix, S3, NATS, and Kubernetes credentials.
- Use Kubernetes Secrets in development.
- Use External Secrets or KMS-backed secret management in production.
- Use NetworkPolicies to isolate Agent, Gateway, Control Plane, and infrastructure traffic.
- Use short-lived pre-signed artifact URLs.
- Redact credentials from logs, prompts, events, and audit payloads.

### 10.4 Acceptance criteria

- Every state-changing operation is authorization-checked and audited.
- Worker Pods cannot use cluster-wide Kubernetes credentials.
- Secret rotation does not require source changes.
- An expired Agent session cannot submit task events.
- Artifact links expire and cannot be reused indefinitely.

## 11. Observability, high availability, and recovery phase

### 11.1 Tracing

Trace the complete chain:

~~~text
Matrix event
  -> Manager model call
  -> Task creation
  -> Assignment
  -> gRPC delivery
  -> Agent execution
  -> Artifact upload
  -> Task completion
~~~

Propagate trace_id, correlation_id, event_id, task_id, attempt_id, and agent_id.

### 11.2 Metrics

Required metrics include queue latency, assignment latency, online Agents, heartbeat age, reconnect count, outbox backlog, event retries, task outcome rate, configuration apply latency, artifact failures, and Operator reconcile errors.

### 11.3 High availability

Initial production topology:

- Two Control Plane replicas.
- Two Gateway replicas.
- Operator leader election.
- Database-backed Scheduler lease.
- Database-backed Outbox claiming.
- No task state in process memory.

Later add PostgreSQL HA, NATS clustering, distributed object storage, multi-zone placement, and automated backup restore.

### 11.4 Recovery

Classify failures as retryable, terminal, or unknown. Unknown states remain persisted and visible.

Examples:

- Agent disconnect: reconnect and replay unacknowledged commands.
- Pod deletion: expire lease and retry or fail by policy.
- NATS outage: keep Outbox pending.
- Matrix outage: continue business processing and replay notifications later.
- Artifact failure: retry upload and keep attempt intermediate.
- Config failure: retain previous active version.
- Model failure: retry or require approval.

### 11.5 Acceptance criteria

- A Control Plane restart does not lose task state.
- Gateway restart does not lose assignments.
- NATS restart drains pending Outbox events after recovery.
- PostgreSQL restore can reconstruct task history.
- Dashboards and alerts expose stuck leases, outbox backlog, and failed reconciliations.

## 12. Implementation order

After the foundation vertical slice, implement:

1. DeepSeek Manager and typed tool layer.
2. QwenPaw Runtime Adapter.
3. ConfigSnapshot and Artifact lifecycle.
4. Team CRD, Team scheduling, and Team Leader.
5. Matrix AppService Adapter.
6. OIDC, mTLS, RBAC, NetworkPolicy, and secret rotation.
7. OpenTelemetry, dashboards, alerts, and audit views.
8. HA, backup, disaster recovery, fault injection, and load testing.

Each phase must preserve the same PostgreSQL state model, event envelope, idempotency rules, and gRPC push contract.

## 13. End-state acceptance criteria

The complete platform must satisfy:

- Normal task assignment does not depend on polling or Matrix mentions.
- Agent reconnect does not lose an assignment.
- Duplicate events do not duplicate execution or results.
- Worker Pod deletion follows an explicit recovery policy.
- Task state is reconstructable from PostgreSQL and domain events.
- Matrix downtime does not corrupt business state.
- Configuration is versioned, checksum-validated, and acknowledged.
- Every external state change has an audit record and trace context.
- DeepSeek, QwenPaw, and Matrix are replaceable adapters.
- The full path runs from a clean Kind installation.
