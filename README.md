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
`POST /api/v1/tasks/{id}/queue` with an `Idempotency-Key`. The built-in
lease-based scheduler assigns queued work across replicas and recovers expired
leases after restart. HTTP API authentication is disabled by default. To
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

Without Docker/WSL, all pure-Java tests still run. Testcontainers-based
PostgreSQL/NATS/MinIO tests are marked `disabledWithoutDocker` and are skipped
until a container engine is available.

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
Authenticated task resources must also carry a `spec.scope` object containing
the same `tenant`, `project`, and `team` values as the caller's claims. Missing
or cross-scope task create/read/queue/cancel requests are rejected with `403`.

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
