# Production observability runbook

AgentTeams exposes Prometheus metrics from the Control Plane and Gateway and
can optionally export OpenTelemetry traces over OTLP/HTTP. Tracing is disabled
by default. Enable it only when an OTLP collector is reachable from both
workloads:

```yaml
observability:
  tracing:
    enabled: true
    samplingProbability: 0.1
    otlpEndpoint: http://otel-collector.telemetry.svc:4318/v1/traces
  serviceMonitor:
    enabled: true
    interval: 30s
  prometheusRule:
    enabled: true
```

The W3C `traceparent` format is used for HTTP propagation. The log pattern
includes `correlationId`, `traceId`, and `spanId`; use `task_id`, `attempt_id`,
`agent_id`, and `event_id` from structured application messages to join the
business event to its trace. Do not add raw prompts, tokens, artifact content,
or tenant identifiers as metric labels or span attributes.

Workers are Operator-managed `Worker` resources, so configure the same tracing
values in `spec.env` rather than expecting Helm to render a Worker Deployment:

```yaml
spec:
  env:
    AGENTTEAMS_OBSERVABILITY_TRACING_ENABLED: "true"
    AGENTTEAMS_OBSERVABILITY_TRACING_SAMPLING_PROBABILITY: "0.1"
    AGENTTEAMS_OBSERVABILITY_OTLP_TRACING_ENDPOINT: http://otel-collector.telemetry.svc:4318/v1/traces
    AGENTTEAMS_OBSERVABILITY_SERVICE_NAME: agentteams-agent-worker
```

The Worker creates its SDK only when tracing is enabled and an endpoint is
present. Export runs asynchronously and is best effort; an unavailable
collector must not block task execution.

The Operator uses a namespace-scoped leader-election lease. For reconciliation
availability during pod disruption, configure at least two Operator replicas;
only the elected replica performs reconciliation. The Kind HA overlay enables
two replicas and applies a matching PodDisruptionBudget.

## First response

1. Open the active alert and record its start time, affected namespace, pod,
   task ID, attempt ID, and correlation/trace IDs.
2. Check workload readiness and recent events:

   ```bash
   kubectl -n agentteams get pods -o wide
   kubectl -n agentteams get events --sort-by=.lastTimestamp | tail -n 40
   kubectl -n agentteams rollout status deployment/agentteams-agentteams-java-control-plane
   kubectl -n agentteams rollout status deployment/agentteams-agentteams-java-gateway
   ```

3. Check the relevant metrics before restarting anything:

   ```bash
   kubectl -n agentteams port-forward service/agentteams-agentteams-java-control-plane 18080:8080
   curl -fsS http://127.0.0.1:18080/actuator/prometheus | rg 'agentteams_(outbox|tasks|gateway)'
   ```

4. Preserve diagnostics before remediation:

   ```bash
   kubectl -n agentteams logs deployment/agentteams-agentteams-java-control-plane --since=30m > control-plane.log
   kubectl -n agentteams logs deployment/agentteams-agentteams-java-gateway --since=30m > gateway.log
   kubectl -n agentteams exec postgresql-0 -- psql -U agentteams -d agentteams -c \
     "select event_type,status,attempts,last_error,created_at,updated_at from outbox_events order by updated_at desc limit 100" \
     > outbox-events.txt
   ```

## Alert actions

| Alert | Meaning | First checks | Safe recovery |
|---|---|---|---|
| `AgentTeamsOutboxBacklog` | Events are not draining | NATS health, relay logs, `outbox_events` status | Restore NATS/relay connectivity; do not delete pending rows |
| `AgentTeamsOutboxStalled` | Oldest event has waited over five minutes | `last_error`, attempts, NATS consumers | Fix the dependency, then allow the relay to reclaim and publish |
| `AgentTeamsOutboxDeadLettered` | Retry budget exhausted | Dead-letter payload category and destination | Classify the error, replay only after the cause is fixed |
| `AgentTeamsLeaseRecovery` | A task lease expired | Worker readiness, task attempt and lease rows | Confirm worker reconnect/replay; avoid manual task mutation first |
| `AgentTeamsGatewayCommandDeduplication` | A duplicate command was received | Gateway reconnects, command sequence and delivery rows | Usually informational; investigate if sustained |
| `AgentTeamsGatewayRejectedEvents` | Worker event was rejected | Agent ID, session expiry, lease/attempt identity | Fix identity or stale-session cause; do not bypass validation |
| `AgentTeamsGatewayNatsConsumerErrors` | Gateway could not process NATS events | Gateway logs, NATS consumer state, payload category | Restore consumer dependency and verify replay |
| `AgentTeamsGatewayConnectionChurn` | Agent streams are repeatedly replaced | Worker logs, mTLS/session expiry, pod restarts | Resolve the connection cause before scaling replicas |

## Recovery and escalation

Keep PostgreSQL as the source of truth. NATS, Gateway, Workers, Matrix, and
the OTLP collector are downstream dependencies and must not be repaired by
editing task state directly. Use the recovery scripts from the repository for
validation after an incident:

```bash
export AGENTTEAMS_API_BEARER_TOKEN='eyJ...'
python scripts/run-kind-lease-recovery.py --agent-id "$AGENTTEAMS_AGENT_ID"
python scripts/run-kind-nats-outbox-recovery.py --agent-id "$AGENTTEAMS_AGENT_ID"
python scripts/run-kind-gateway-replay.py --agent-id "$AGENTTEAMS_AGENT_ID"
python scripts/run-kind-postgres-restore.py
python scripts/run-kind-object-reference-integrity.py
```

When API OIDC is enabled, the recovery scripts send this bearer token on
their HTTP calls. In CI the recovery job disables API authentication; in a
local OIDC-enabled Kind cluster, obtain the token from the configured IdP and
do not place it in repository files.

Escalate when a dead-letter event contains a non-retryable business error,
when restored object checksums differ from PostgreSQL metadata, when leases
remain stuck after Worker/Gateway recovery, or when trace export failures
coincide with application request failures. Tracing export is best-effort and
must never block task state transitions.
