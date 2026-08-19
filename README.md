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
leases after restart. Set `AGENTTEAMS_SECURITY_API_ENABLED=true` only after
providing an `IdentityTokenValidator` implementation for the deployment; this
enables the Bearer-token boundary for `/api/*` without embedding a specific
OIDC provider in the core services.

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

The QwenPaw Worker image is built and loaded by `deploy/build-images.sh`. To
connect one to the real push path, first create an Agent through the Control
Plane API, replace the placeholder UUID in
`deploy/examples/qwenpaw-worker.yaml`, and apply it:

```bash
kubectl apply -f deploy/examples/qwenpaw-worker.yaml
kubectl -n agentteams wait --for=condition=available deployment/qwenpaw-worker --timeout=180s
```

The Operator injects `AGENTTEAMS_AGENT_ID` from `Worker.spec.agentId`; the
Gateway and QwenPaw endpoints remain explicit Worker environment settings.
The Worker calls QwenPaw's HTTP/SSE endpoint and reports accepted, heartbeat,
progress, completion, or failure events over the Gateway gRPC stream.

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

`deploy/kind-dev-infra.yaml` is intentionally development-only: PostgreSQL
uses an `emptyDir` volume and the database password is a local test secret.
Production deployments must provide durable PostgreSQL, NATS JetStream and
Secrets through the target cluster or its operators.
