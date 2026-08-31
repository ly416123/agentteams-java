# AgentTeams Java

This directory contains the Java 17 Maven foundation for AgentTeams. The
existing Go implementation is outside this project and is not modified by
this build.

## Modules

- `contracts`: protobuf and gRPC contract build support.
- `domain`: framework-independent domain model and tests.
- `application-contracts`: application ports and DTOs shared by service boundaries.
- `control-plane`: Spring Boot application dependencies for business APIs,
  persistence, Flyway, PostgreSQL, and NATS.
- `agent-gateway`: Spring Boot application dependencies for gRPC agent
  connections and NATS integration.
- `runtime`: runtime-neutral Agent SPI with Fake and QwenPaw HTTP/process adapters.
- `manager`: DeepSeek/OpenAI-compatible structured Manager and permissioned tools.
- `operator`: Java Operator SDK and Fabric8 Kubernetes dependencies.
- `integration-tests`: cross-module tests and Testcontainers dependencies.

## Architecture map

The repository's module responsibilities, dependency layers, core data flows,
deployment topology, and important design patterns are documented in the
[architecture code map](docs/architecture-map.html). The map is a curated
design aid; CI checks that every Maven module has a corresponding map entry so
module additions do not silently make it stale.

## Build

The parent POM configures the Maven compiler for Java 17 (`--release 17`) and
UTF-8. A Java 17 JDK is required to build the project. Unit tests are enabled
by default:

```text
mvn -q test
```

Integration tests use the `integration-tests` profile:

```text
mvn -q -Pintegration-tests verify
```

## Worker Template Registry（当前第一纵切）

Control Plane 已提供模板的 scope 内唯一创建、不可变 revision、Review/Publish
生命周期、幂等实例化和实例升级入口。当前公共 API 为：

```text
POST /api/v1/worker-templates
POST /api/v1/worker-templates/{id}/revisions
POST /api/v1/worker-templates/{id}/revisions/{revision}/review
POST /api/v1/worker-templates/{id}/revisions/{revision}/publish
POST /api/v1/worker-templates/{id}/revisions/{revision}/instances
POST /api/v1/worker-templates/{id}/instances/{instanceId}/upgrade/{revision}
```

写操作使用 `Idempotency-Key`；状态变更使用 `expectedVersion`。模板实例通过
现有 AgentSpec/Worker 服务边界创建，不直接操作 Kubernetes。企业审批、外部
Skill/MCP/Secret 深度校验和 L6 真实验收不属于当前纵切完成条件。

## Public API 与 SDK（v1.0 第一纵切）

公共契约位于 [`openapi/agentteams-public.yaml`](openapi/agentteams-public.yaml)，当前冻结
Project/Task 核心接口、游标分页、Bearer 鉴权、`Idempotency-Key` 和统一错误结构。
Java 17 与 TypeScript 客户端分别位于 [`sdk/java`](sdk/java) 和
[`sdk/typescript`](sdk/typescript)；它们只访问公共 API，不暴露 Kubernetes、Matrix
AppService 或其他内部接口。

## Git development workflow

`main` is the only integration and release baseline. New work must branch from
the latest `origin/main`, and completed work must be merged back before the
next task starts. See the [Git development workflow](docs/development/git-workflow.md)
for worktree, synchronization, and branch-governance rules.

Generated protobuf and gRPC sources are build artifacts and are not committed.

## Kubernetes delivery

The Kubernetes-only delivery artifacts are under `deploy/`:

```text
deploy/docker/                       # multi-stage images for the Java services
deploy/helm/agentteams-java/         # CRDs, RBAC, Deployments and Services
```

The chart expects PostgreSQL, NATS JetStream and the database Secret to be
provided by the cluster (or by an operator-managed dependency). It does not
embed Docker Compose or local polling infrastructure.

The runtime path is push-based: Control Plane writes PostgreSQL plus Outbox,
NATS carries durable events, Gateway delivers assignments over a gRPC stream,
and the Agent acknowledges/reports progress on that stream. Agent execution
events return through the `agent.events.*` JetStream stream and are applied by
the Control Plane. Gateway and Manager depend on application contracts rather
than Control Plane persistence classes. Matrix is an optional
human-collaboration adapter; it is not the task state database.

Task-level isolation is layered behind `SandboxRuntimePort`. The default
profile is `NONE`, so existing tasks keep their current path. Explicit
`ISOLATED`/`HARDENED` tasks are fenced by Attempt, persisted in
`task_sandboxes`, and represented in Kubernetes by the namespace-scoped
`TaskSandbox` CRD and an Operator-managed restricted Job. The Control Plane
does not need Docker Socket or Pod/Job RBAC. Helm keeps the feature disabled by
default; gVisor/Kata RuntimeClass validation requires a separate Linux/KVM
environment and is not claimed by the local Fake Provider or Kind static tests.

The runtime module provides `QwenPawHttpRuntimePort` for the official QwenPaw
HTTP/SSE API, `JsonLinesQwenPawProcessPort` for a custom external process
boundary, and `GrpcAgentChannelPort` for the protobuf bidirectional stream.
The HTTP port sends `POST /api/console/chat` with `X-Agent-Id`, parses terminal
`completed`/`failed` SSE events, and treats cancellation as best-effort local
stream cancellation. Configure it with `QwenPawHttpRuntimeConfiguration`; a
remote deployment should provide QwenPaw's Web Auth token as the optional
Bearer token. The JSON Lines adapter is retained for internal/custom process
protocols and is not the protocol used by the official QwenPaw image. These
boundaries are deliberately runtime-specific and do not move QwenPaw files or
sockets into the Control Plane.

The Control Plane task lifecycle is explicit: create a task in `DRAFT`, then
`POST /api/v1/tasks/{id}/queue` with an `Idempotency-Key`. Lifecycle commands
are exposed as `POST /retry`, `/pause`, `/approve`, `/reject`, and `/cancel`
under the task resource; each command requires its own idempotency key and
enforces the corresponding OIDC permission. Task artifacts use direct
object-storage transfer: call
`POST /api/v1/tasks/{taskId}/attempts/{attemptId}/artifacts/uploads`, upload to
the returned pre-signed URL, then call the sibling `/artifacts/complete`
endpoint with the size and SHA-256 checksum. The built-in lease-based
scheduler assigns queued work across replicas and recovers expired leases
after restart. Existing artifacts can be listed or fetched through the task
artifact resource, which returns a fresh short-lived download URL. HTTP API
authentication is disabled by default. To
enable the built-in OIDC JWT validator, provide the issuer, JWKS URI, audience,
and scope claims together; enabling the API without a complete OIDC
configuration fails startup rather than exposing an unauthenticated API:

```yaml
controlPlane:
  security:
    apiEnabled: true
    oidc:
      enabled: true
      issuerUri: https://id.example.com/
      jwkSetUri: https://id.example.com/.well-known/jwks.json
      audience: agentteams-api
      tenantClaim: tenant
      projectClaim: project
      teamClaim: team
      permissionsClaim: permissions
```

The validator checks the JWT signature, issuer, audience, expiry and not-before
claims, then maps `sub`, tenant/project/team claims and permissions into the
existing authorization boundary. JWKS caching and key refresh are delegated
to Spring Security's Nimbus decoder; no OIDC client secret is stored in the
application configuration.

## Project environment and validation gate

The primary local development environment is macOS with Colima and a working
Docker daemon. This project workspace has Docker Engine available locally,
Chrome available for browser validation, and an existing Kind cluster named
`agentteams`. `deploy/dev-env.sh` selects the `colima` Docker context and
exports the Docker endpoint used by Kind and other CLI commands. For Maven
tests, the root `pom.xml` automatically activates the `colima-testcontainers`
profile when the Colima socket exists, and injects the endpoint plus the
container-visible socket override into Surefire/Failsafe. Docker-backed tests
are therefore part of the normal local verification path, not an optional
CI-only substitute.

Before pushing a code or deployment change to GitHub Actions, load the local
environment and complete the Docker-backed verification successfully:

```bash
source deploy/dev-env.sh
docker info
mvn -q -Pintegration-tests verify
```

`source deploy/dev-env.sh` remains necessary for Kind and explicit Docker CLI
commands. Maven/Surefire Testcontainers tests can be run directly with
`mvn -q test`; no `DOCKER_HOST` or `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`
export is required when the Colima socket is present.

If the local Docker daemon or Testcontainers endpoint is unavailable, pure
Java tests may still be useful for diagnosis, but the change must not be
submitted to GitHub CI as locally verified until the Docker-backed command has
passed.

For this workspace, Docker/Kind/browser validation is an execution
requirement. Acceptance commands must fail with a non-zero exit code when
Docker, Kind, kubectl, the required image, or Chrome is unavailable; an
environmental `SKIPPED` result is not accepted as a passing result.

The current local verification baseline (2026-08-30) uses Docker 29.5.2,
Kind cluster `agentteams`, and the installed browser toolchain. The Console
Playwright smoke passed (`1 passed`) and the deployed real QwenPaw conversation
checks passed. A direct system-Chrome connector was not attached in the latest
Codex session, so that connector-specific result remains explicitly
unverified rather than being inferred from the Playwright result.

The independent Ubuntu/KVM L5 host is `ly-MacBookAir7-2` at
`192.168.122.55`. It also passed the real L5 TaskSandbox acceptance: both
`gvisor` and `kata-qemu` profiles reached `READY`,
their generated Jobs/Pods used the expected RuntimeClass, guest and host
kernels were observed, and cleanup completed with
`L5_LINUX_KVM_ACCEPTANCE_OK`. Node-failure recovery and production L6 remain
separate gates.

For every subsequent batch feature, local Docker-backed verification is
required. If the change touches Kubernetes, Operator, Worker, TaskSandbox,
RuntimeClass, images, Helm, runtime routing, lifecycle, or deployment paths,
the same change must also pass the real Ubuntu/KVM L5 acceptance on this host,
including runtime evidence and cleanup confirmation. A local Kind/Fake Provider
result or an unavailable L5 environment cannot be reported as a pass; until
both gates pass, the batch remains `development complete, acceptance pending`
and cannot be integrated into the mainline. L6 remains a separate controlled
environment gate.

## Local infrastructure

On macOS, the repository provides a Colima/Testcontainers bootstrap script:

```bash
source deploy/dev-env.sh
mvn -q clean test
```

For local Kubernetes work, create a clean Kind cluster, install the development
dependencies, load local service images, and install the chart:

```bash
source deploy/dev-env.sh
./deploy/pull-kind-node-image.sh
kind create cluster --config deploy/kind-config.yaml
kubectl apply -f deploy/kind-dev-infra.yaml
kubectl -n agentteams wait --for=condition=available deployment/postgresql deployment/nats deployment/minio --timeout=180s
kubectl -n agentteams wait --for=condition=complete job/nats-stream-bootstrap job/minio-bucket-bootstrap --timeout=120s
./deploy/build-images.sh
kubectl apply -f deploy/helm/agentteams-java/crds/teams.yaml
kubectl apply -f deploy/helm/agentteams-java/crds/workers.yaml
helm lint deploy/helm/agentteams-java
helm upgrade --install agentteams deploy/helm/agentteams-java \
  --namespace agentteams --create-namespace --wait \
  -f deploy/helm/kind-values.yaml
kubectl -n agentteams wait --for=condition=available \
  deployment/agentteams-agentteams-java-control-plane \
  deployment/agentteams-agentteams-java-gateway \
  deployment/agentteams-agentteams-java-operator --timeout=300s
```

To enable a real local OIDC issuer after the Kind foundation is installed,
deploy the development-only Keycloak realm and apply the OIDC Helm overlay:

```bash
./deploy/install-kind-oidc.sh
./scripts/smoke-kind-oidc.sh
./scripts/smoke-kind-oidc-rotation.sh
```

The smoke script obtains tokens from Keycloak and verifies missing/invalid
authentication (`401`), missing permission and cross-scope access (`403`),
and matching permission plus scope (`201`). Development users are
`alice/alice-dev`, `reader/reader-dev`, and `tenant-b-user/tenant-b-dev`.
Keycloak uses temporary development storage and credentials; do not reuse
them outside the local Kind cluster. The rotation smoke creates a temporary
RSA signing provider, verifies a changed JWT `kid`, confirms the new key is
published in JWKS, and checks both the new token and the old token overlap.

The local Matrix path uses Tuwunel with a file-based AppService registration
and a development-only shared token:

```bash
./deploy/install-kind-matrix.sh
./scripts/smoke-kind-matrix.sh
```

The smoke verifies Tuwunel health, AppService shared-token rejection, accepted
transactions, transaction-level duplicate acknowledgement, and real local
user/room messages that create and mutate scoped tasks through `!agentteams
start/status/approve/pause/cancel/reject/retry`. It also verifies pause/resume
state persistence, approval spec persistence, permission rejection, and a
duplicate direct AppService mutation. The local Tuwunel data, registration
token, and users are development-only and must not be reused elsewhere. Matrix
task actions use scoped permissions and optimistic versions; approval also
updates the task spec and team approval link.

The Kind path was verified on 2026-08-18 with a two-node `v1.36.1` cluster.
Docker Hub was not reachable from the environment, so the pinned node image
and dependency images were preloaded into Kind; the application manifests keep
their canonical image names and `IfNotPresent` policy. The MinIO server uses
`RELEASE.2024-11-07T00-52-20Z`, while the compatible fixed `mc` bootstrap image
uses `RELEASE.2025-07-21T05-28-08Z`. The service Dockerfiles copy
`deploy/docker/maven-settings.xml` so Maven builds use the configured public
mirror when Maven Central is unavailable.

The `kind-recovery` CI job creates the same two-node topology, builds and loads
the service images, creates a real QwenPaw Worker, and runs
`scripts/run-kind-lease-recovery.py`. Prometheus uses Kubernetes Pod discovery
instead of a load-balanced Service target, and
`scripts/validate-kind-prometheus.py` verifies that both Control Plane and
Gateway replicas are being scraped.

The Operator smoke path was also verified with a temporary `Worker` CR:
Operator-created Deployment and Service reached Ready, replica changes updated
Worker status, and deleting the CR removed both child resources. Worker images
are generated with `imagePullPolicy: IfNotPresent` so locally loaded Kind images
do not trigger a registry pull.

The QwenPaw Worker image is built and loaded by `deploy/build-images.sh`.
`deploy/install-kind-dev.sh` now also runs
`deploy/bootstrap-kind-qwenpaw-worker.sh`, which idempotently registers a
development Agent, injects the returned UUID, applies the Worker CR, and waits
for the Worker to become Ready. To repeat that step manually:

```bash
./deploy/bootstrap-kind-qwenpaw-worker.sh
```

To add a second real Worker for Team scheduling tests, reuse the same
idempotent bootstrap with a distinct Worker name and idempotency key:

```bash
AGENTTEAMS_WORKER_NAME=qwenpaw-worker-team-2 \
AGENTTEAMS_WORKER_AGENT_NAME=qwenpaw-kind-worker-2 \
AGENTTEAMS_WORKER_IDEMPOTENCY_KEY=qwenpaw-kind-worker-2-v1 \
  ./deploy/bootstrap-kind-qwenpaw-worker.sh
```

The Operator injects `AGENTTEAMS_AGENT_ID` from `Worker.spec.agentId`; the
Gateway and QwenPaw endpoints remain explicit Worker environment settings.
The Worker calls QwenPaw's HTTP/SSE endpoint and reports accepted, heartbeat,
progress, completion, or failure events over the Gateway gRPC stream.
The development QwenPaw container starts without an active LLM provider; a
provider/model and its credentials must be configured through QwenPaw before a
real model task can complete. Worker readiness only proves the Agent channel
is connected, and the bootstrap script intentionally does not invent external
credentials.

### AgentScope 灰度与回滚

AgentScope 的运行时选择采用默认关闭、确定性分桶和显式 allowlist。Helm
会渲染 `<release>-agentteams-java-agent-runtime` ConfigMap，供兼容的 Worker
以 `envFrom` 方式读取；ConfigMap 不包含任何模型凭证。默认值为：

```yaml
agentRuntime:
  default: QWENPAW
  agentScope:
    enabled: false
    rolloutPercentage: 0
    agentAllowlist: []
    teamAllowlist: []
    tenantAllowlist: []
```

灰度前必须先确认 Worker 在 Hello 中声明 `sandbox-assignment-v1`（若任务
带 Sandbox），再逐步提高 `rolloutPercentage` 或配置 allowlist。回滚只需将
`agentScope.enabled` 设为 `false`、`rolloutPercentage` 设为 `0`，并滚动更新
Worker；缺少租户/Team/Agent 稳定标识时策略会 fail-closed 到 QwenPaw。
当前默认 Worker 仍保持 QwenPaw 执行路径，AgentScope Harness 的生产接线需
在兼容 Worker 镜像和真实模型配置完成后再开启。

The chart resource names include both the Helm release and chart name. The
Control Plane API can be exposed for a smoke check with:

```bash
kubectl -n agentteams port-forward \
  svc/agentteams-agentteams-java-control-plane 8080:8080 &
PORT_FORWARD_PID=$!
trap 'kill "$PORT_FORWARD_PID" 2>/dev/null || true' EXIT
until curl -fsS localhost:8080/actuator/health >/dev/null; do sleep 2; done
AGENT_ID=$(curl -fsS -X POST http://localhost:8080/api/v1/agents \
  -H 'Idempotency-Key: smoke-agent-1' -H 'Content-Type: application/json' \
  -d '{"name":"smoke-agent","runtime":"fake","capabilities":{"java":"17"}}' \
  | jq -r '.id')
TASK_ID=$(curl -fsS -X POST http://localhost:8080/api/v1/tasks \
  -H 'Idempotency-Key: smoke-task-1' -H 'Content-Type: application/json' \
  -d '{"title":"smoke","description":"kind smoke task","spec":{}}' \
  | jq -r '.id')
curl -fsS -X POST "http://localhost:8080/api/v1/tasks/${TASK_ID}/queue" \
  -H 'Idempotency-Key: smoke-queue-1'
curl -fsS "http://localhost:8080/api/v1/tasks/${TASK_ID}"
```

This smoke check expects the task to remain `QUEUED`: registering an Agent via
the API does not establish a gRPC connection. The complete push path is
covered by the infrastructure integration test.

For a local real-model check, keep the DeepSeek key in the ignored root file
`apikey` with mode `0600`. The following commands configure the QwenPaw
`deepseek` provider and active model, then verify Manager and the full task
path. The scripts never print the key or send it as a command-line argument:

```bash
chmod 600 apikey
./scripts/smoke-deepseek-manager.sh
./scripts/configure-local-qwenpaw-deepseek.sh
./scripts/smoke-kind-qwenpaw-deepseek.sh
```

The default model is `deepseek-v4-flash`; use `DEEPSEEK_MODEL` only for a
deliberate local override. A successful task smoke prints
`QWENPAW_DEEPSEEK_TASK_OK` with `phase=SUCCEEDED` and
`output=QWENPAW_DEEPSEEK_SMOKE_OK`; the script verifies that marker in the
Worker log instead of relying on the phase alone. On 2026-08-21, the Manager
smoke, QwenPaw Provider test, three independent real tasks, and repeated
Idempotency-Key creation were verified in the local Kind cluster.

When OIDC API authentication is enabled, the task smoke honors
`AGENTTEAMS_API_TOKEN` first. If it is unset, the script port-forwards the
local Keycloak service and obtains a development `alice/alice-dev` token,
without printing the token or the complete task response. Override the local
Keycloak port with `KIND_KEYCLOAK_LOCAL_PORT` when needed.

For the real Manager Conversation path, deploy the Manager with the QwenPaw
Service endpoint and matching NetworkPolicy port, then run the authenticated
acceptance script. It verifies real DeepSeek SSE deltas, terminal completion,
cursor replay, idempotent message replay, and cancellation; it never treats a
missing Docker/Kind/image/model dependency as a skip:

```bash
helm upgrade --install agentteams deploy/helm/agentteams-java \
  --namespace agentteams --create-namespace --wait \
  -f deploy/helm/kind-values.yaml -f deploy/helm/kind-oidc-values.yaml \
  --set-string manager.conversation.qwenpawEndpoint=http://qwenpaw:8088 \
  --set-string manager.conversation.qwenpawAgentId=default \
  --set manager.conversation.qwenpawEgressPort=8088
kubectl -n agentteams port-forward svc/agentteams-agentteams-java-manager 18084:8080
AGENTTEAMS_API_BEARER_TOKEN="<Keycloak token>" \
  python3 scripts/run-kind-qwenpaw-conversation-acceptance.py \
  --base-url http://127.0.0.1:18084 --image agentteams-manager
```

也可以直接运行 `./scripts/smoke-kind-console-real-conversation.sh`；脚本会
自动建立 Manager/Keycloak port-forward、获取本地 alice 测试令牌，并在结束时
清理转发进程，不会输出令牌或 API Key。

Conversation 会话、用户消息和事件已由 Manager 的 PostgreSQL repository 持久化，
Console 页面加载时会读取 `/api/v1/conversations/{sessionId}/history`，再接续 SSE。
可用下面的验收脚本真实滚动重启 Manager，并验证同一会话历史与消息幂等重放保持一致：

```bash
python3 scripts/run-kind-conversation-restart-acceptance.py \
  --image ghcr.io/ly416123/agentteams-manager:latest
```

会话创建时会从已验证的 OIDC 身份持久化 `tenantId` 与 `subject`，读取、消息和取消
操作会再次校验租户、项目和 Team 范围；服务端响应带有会话 `version`，消息和取消
请求可通过 `expectedVersion` 防止并发覆盖。旧版本数据库会由 Manager Flyway
迁移增加可为空的归属字段和版本初值；无法追溯归属的历史会话不会被当作通配资源放行。

消息发送采用数据库 reservation：同一会话幂等键在跨 Manager 副本场景下只允许一个
副本调用 Worker，消息状态会记录为 `RESERVED`、`COMPLETED`、`FAILED` 或
`RECOVERY_REQUIRED`。Manager 重启发现未完成 reservation 时会 fail-closed，返回
`CONVERSATION_RECOVERY_REQUIRED`，不会对无法确认是否已被 QwenPaw 接收的请求自动重发。
QwenPaw SSE 的 `id:` 会作为可选的上游事件身份保存，并通过唯一约束去重；对外 SSE
仍使用 Manager 的持久化 replay cursor。历史事件没有上游 ID 时不会伪造 exactly-once 语义。

当前重启验收覆盖的是已完成消息的历史恢复与幂等重放。QwenPaw 运行中的请求仍
依赖上游提供稳定的 operation/event cursor 才能实现真正 resume；在该协议补齐前，
不能把 Manager 重启后的 in-flight 请求宣称为 exactly-once 或可自动续接。

在本机 OIDC 集群中验收真实 Worker 的项目配额闭环，可执行：

```bash
AGENTTEAMS_AGENT_ID="$(kubectl -n agentteams get worker qwenpaw-worker -o jsonpath='{.spec.agentId}')" \
  ./scripts/run-kind-oidc-worker-quota-admission.sh
```

该脚本使用 Keycloak 的本地 `quota-admin` 测试用户，绑定 `tenant-a/project-a`
项目成员关系，开启目标 Worker 的远程配额并等待滚动更新，然后验证真实任务的
配额 acquire/release、调用次数和 token 计数。它不会输出令牌或 API Key。

The browser remains connected only to the AgentTeams Console/Conversation API;
QwenPaw stays an internal service dependency. The Console Playwright check
exercises the browser page and the real Conversation acceptance exercises the
deployed Docker/Kind service path. Direct system-Chrome automation is an
additional connector-specific check and must be reported separately when that
connector is not attached.

Team CRD scheduling can be smoke-tested with two existing READY Agent UUIDs.
The script creates a temporary Team CR, applies the stable `namespace/name`
Team ID to three tasks, and expects one task to be `ASSIGNED` while the other
two remain `QUEUED` under `maxConcurrentTasks: 1`. It removes only the
temporary Team CR when it exits and never prints the CRD body or credentials:

```bash
AGENTTEAMS_TEAM_AGENT_IDS="<ready-agent-uuid-1>,<ready-agent-uuid-2>" \
  ./scripts/smoke-kind-team-scheduling.sh
```

The Team CRD must be applied before Helm on an existing cluster because Helm
does not upgrade CRDs automatically:

```bash
kubectl apply -f deploy/helm/agentteams-java/crds/teams.yaml
```

`deploy/install-kind-dev.sh` performs this step automatically. The Team
informer requires the Control Plane service account to have only
`get/list/watch` access to `teams.agentteams.io`; the chart enables this with
`controlPlane.teamSync.enabled=true` and scopes it to the `agentteams`
namespace by default. The deterministic PostgreSQL-backed acceptance test is
`TeamSchedulingInfrastructureIT`; the Kind smoke additionally proves the
informer and Helm/RBAC wiring. A cluster with only one READY Agent cannot run
this smoke without adding a second real Agent.

The chart isolates workload identities: Control Plane, Gateway, and Operator
use separate ServiceAccounts. Gateway pods do not mount a Kubernetes API token;
only the Operator receives permissions to manage Worker child resources, while
Control Plane retains read-only Team sync access. Do not reuse the Operator
account for Gateway when overriding chart values.

The Operator RBAC is namespace-scoped: Helm creates a `Role` and `RoleBinding`
in the release namespace, and the Operator watches only that namespace through
`AGENTTEAMS_OPERATOR_NAMESPACE`. Team sync is also restricted to a read-only
Role in the release namespace. Deployments that manage multiple namespaces must
install one chart release per namespace; no shared ClusterRole or cluster-wide
Operator informer is granted.

When OIDC API authentication is enabled, the Control Plane maps API routes to
the permissions carried in the configured claim (`task:read`, `task:create`,
`task:cancel`, `agent:read`, `agent:write`, `config:read`, and `config:write`).
Missing permissions return `403`, while missing or invalid tokens return
`401`; unknown API routes do not receive an implicit write permission.
Authenticated Agent resources must carry `metadata.scope`, while Task resources
must carry `spec.scope`; ConfigSnapshot and ConfigFile resources carry the same
`scope` object in their manifest. Each object must contain the same `tenant`,
`project`, and `team` values as the caller's claims. Missing or cross-scope
create/read/update requests are rejected with `403`.

For a local Gateway↔Worker mTLS check, use the development-only bootstrap
script. It creates a temporary 30-day CA and certificates under
`.local/kind-mtls/` (ignored by Git), creates the two Kubernetes Secrets,
enables Gateway TLS, and patches the selected Worker CRs with the client
certificate mount:

```bash
AGENTTEAMS_MTLS_WORKERS=qwenpaw-worker-team-2,qwenpaw-worker-team-3 \
  ./deploy/bootstrap-kind-mtls.sh
```

The script waits for the Gateway, Deployment, and Worker status to converge.
The local validation used two distinct READY Agents and printed
`TEAM_SCHEDULING_OK`. This is a Kind development path: production should use
per-Agent certificates issued and rotated by an external CA or cert-manager;
the repository does not commit generated keys or certificate material. For the
production Secret contract, stable-name rotation, optional Stakater Reloader
rollouts, and OIDC JWKS key overlap, see
[`deploy/production/README.md`](deploy/production/README.md).

`deploy/kind-dev-infra.yaml` is intentionally development-only: PostgreSQL
uses an `emptyDir` volume and the database password is a local test secret.
Production deployments must provide durable PostgreSQL, NATS JetStream and
Secrets through the target cluster or its operators.

For a production starting point, copy
[`deploy/helm/agentteams-java/values-production.example.yaml`](deploy/helm/agentteams-java/values-production.example.yaml)
to an environment-owned values file, replace the release tag and endpoint
placeholders, and provision the referenced Secrets through cert-manager,
External Secrets, or the platform secret manager. The repository validates the
example contract with `python scripts/validate-production-values.py`; it never
contains production credentials.
