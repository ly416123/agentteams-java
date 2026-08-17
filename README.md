# AgentTeams Java

This directory contains the Java 17 Maven foundation for AgentTeams. The
existing Go implementation is outside this project and is not modified by
this build.

## Modules

- `contracts`: protobuf and gRPC contract build support.
- `domain`: framework-independent domain model and tests.
- `control-plane`: Spring Boot application dependencies for business APIs,
  persistence, Flyway, PostgreSQL, and NATS.
- `agent-gateway`: Spring Boot application dependencies for gRPC agent
  connections and NATS integration.
- `runtime`: runtime-neutral Agent SPI with Fake and QwenPaw process adapters.
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
and the Agent acknowledges/reports progress on that stream. Matrix is an
optional human-collaboration adapter; it is not the task state database.

Without Docker/WSL, all pure-Java tests still run. Testcontainers-based
PostgreSQL/NATS/MinIO tests are marked `disabledWithoutDocker` and are skipped
until a container engine is available.

## Local infrastructure

On macOS, the repository provides a Colima/Testcontainers bootstrap script:

```bash
source deploy/dev-env.sh
mvn -q clean test
```

For local Kubernetes work, create the documented Kind cluster and install the
development PostgreSQL and NATS dependencies:

```bash
source deploy/dev-env.sh
kind create cluster --config deploy/kind-config.yaml
kubectl apply -f deploy/kind-dev-infra.yaml
helm lint deploy/helm/agentteams-java
```

`deploy/kind-dev-infra.yaml` is intentionally development-only: PostgreSQL
uses an `emptyDir` volume and the database password is a local test secret.
Production deployments must provide durable PostgreSQL, NATS JetStream and
Secrets through the target cluster or its operators.
