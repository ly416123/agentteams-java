# Production Secret rotation

Operational metrics, tracing configuration, alert actions, and recovery
commands are documented in
[`observability-runbook.md`](observability-runbook.md).

The chart deliberately consumes stable Kubernetes Secret names. An external
secret issuer owns the Secret data; AgentTeams only mounts the Secret and never
generates or stores production private keys.

## Gateway mTLS

Set the Gateway Secret name once and keep it unchanged during certificate
renewal:

```yaml
gateway:
  tls:
    enabled: true
    secretName: agentteams-gateway-mtls
    certificateChainPath: /etc/agentteams/gateway-tls/tls.crt
    privateKeyPath: /etc/agentteams/gateway-tls/tls.key
    trustCertificateCollectionPath: /etc/agentteams/gateway-tls/ca.crt
```

The Secret must contain `tls.crt`, `tls.key`, and `ca.crt`. cert-manager or an
ExternalSecret controller may update those keys in place. The chart and the
Operator add `secret.reloader.stakater.com/reload` to TLS-enabled Deployments;
install [Stakater Reloader](https://github.com/stakater/Reloader) in the target
cluster to turn an update into a rolling restart. Without Reloader the mount is
still updated by Kubernetes, but the running gRPC server keeps its existing TLS
context until the Pod is restarted.

Worker CRs use the same stable-name contract:

```yaml
spec:
  tlsSecret: agentteams-worker-mtls
```

Use a distinct client-certificate Secret per Worker when the CA policy requires
per-Agent identity. The Secret must contain the client `tls.crt`, `tls.key`, and
the issuing `ca.crt`. Do not commit rendered Secret objects or private key
material; provision them through cert-manager, External Secrets, or the
cluster's equivalent secret manager.

## OIDC key rotation

OIDC does not require an application client secret. Keep `issuerUri`,
`jwkSetUri`, and `audience` stable while the identity provider publishes a new
signing key alongside the old one. Spring Security Nimbus refreshes the JWKS
cache when it encounters a new `kid`; remove the old provider key only after
all issued tokens have expired and the cache overlap window has passed.

The API still fails closed: enabling OIDC requires a complete issuer, JWKS, and
audience configuration. Rotate provider keys at the identity provider rather
than placing JWKS private material in Helm values.

## Resource scope contract

OIDC claims are mapped to the same three-part scope for every API resource:
`tenant`, `project`, and `team`. Authenticated requests must carry that scope
inside the resource payload: Agent metadata uses `metadata.scope`, tasks use
`spec.scope`, and ConfigSnapshot/ConfigFile manifests use `scope`. The values
must exactly match the caller's claims; missing or cross-scope access returns
`403`.

## Task Sandbox isolation

Sandbox isolation is opt-in and Task-scoped, not Team-, Worker-, or Kubernetes
Node-scoped. Keep the production values explicit and let the Operator own the
restricted Job lifecycle:

```yaml
sandbox:
  enabled: true
  defaultProfile: NONE
  provider: kubernetes
  runtimeClasses:
    isolated: gvisor
    hardened: kata-qemu
  defaultTtlSeconds: 1800
  maxTtlSeconds: 86400
```

The chart and Operator enforce namespace-scoped CRD/Job permissions, disable
the ServiceAccount token, reject privileged/host namespace/hostPath settings,
and apply a default-deny sandbox NetworkPolicy with DNS only. Before enabling
a non-`NONE` profile, verify that the configured RuntimeClass exists and run
the independent Linux/KVM acceptance for both gVisor and Kata. Do not grant
the Control Plane Docker Socket or arbitrary Pod/Job permissions.

## Rollout verification

After installing or updating the external secret controller, verify that the
stable Secret changed and that the expected workloads rolled:

```bash
kubectl -n agentteams get secret agentteams-gateway-mtls -o jsonpath='{.metadata.resourceVersion}{"\n"}'
kubectl -n agentteams rollout status deployment/agentteams-agentteams-java-gateway
kubectl -n agentteams get deployment -l app.kubernetes.io/name=agentteams-worker
```

For a controlled rotation, update one certificate chain first, wait for the
Gateway and Worker rollouts to become Ready, then rotate the remaining client
certificates. Keep the CA overlap long enough for all active streams to
reconnect.

## Matrix AppService production contract

Keep Matrix outside the task database and use an environment-owned AppService
registration. [`matrix-appservice-registration.example.yaml`](matrix-appservice-registration.example.yaml)
is a non-secret template for the URL, sender namespace, and room namespace
contract. Store `as_token` and `hs_token` in the external Secret referenced by
`controlPlane.matrix.appservice.hsTokenSecret`; never put either token in Helm
values or Git. The homeserver must use durable storage and route AppService
transactions to the Control Plane Service. Verify transaction/event
deduplication and outbound replay after a homeserver or Control Plane restart.

Before rendering a release, run `python scripts/validate-matrix-registration.py`
and replace only the URL, namespaces, and external-secret placeholders through
the deployment pipeline.

Before a production rollout, run the endpoint preflight with environment-owned
URLs. It checks API readiness, OIDC discovery/JWKS reachability, and optionally
the Matrix homeserver without printing credentials:

```bash
export API_HEALTH_URL=https://api.example.com/actuator/health/readiness
export OIDC_ISSUER_URI=https://idp.example.com/realms/agentteams
export OIDC_JWKS_URI=https://idp.example.com/realms/agentteams/protocol/openid-connect/certs
export MATRIX_HOMESERVER_URL=https://matrix.example.com # optional
./scripts/validate-production-endpoints.sh
```

## OIDC acceptance checks

Run these checks with a token issued by the target provider. The token must
contain the configured issuer and audience, the required permission, and the
three scope claims (`tenant`, `project`, `team`). Replace the placeholders with
values from the deployment and use a resource payload whose scope matches the
token:

```bash
export API_URL=https://api.example.com
export TOKEN='eyJ...'
export IDEMPOTENCY_KEY="oidc-acceptance-$(date +%s)"
curl -i -X POST "$API_URL/api/v1/tasks" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
  -H 'Content-Type: application/json' \
  --data '{"title":"oidc-acceptance","spec":{"scope":{"tenant":"tenant-a","project":"project-a","team":"team-a"}}}'
```

Expected results are `201` for a matching permission and scope, `401` when
the bearer token is absent or invalid, and `403` when the permission is
missing or the resource scope differs. During signing-key rotation, publish
the new public key at the provider JWKS endpoint, issue a token with the new
`kid`, and repeat the request before retiring the old key. The API must accept
the new token without a restart and continue rejecting cross-scope requests.

For repeatable execution from the repository, use the acceptance script. The
optional tokens enable the negative cases and the post-rotation check:

```bash
export API_URL=https://api.example.com
export TOKEN='eyJ...'
export SCOPE_TENANT=tenant-a
export SCOPE_PROJECT=project-a
export SCOPE_TEAM=team-a
export TOKEN_NO_PERMISSION='eyJ...'       # optional, expected 403
export TOKEN_CROSS_SCOPE='eyJ...'         # optional, expected 403
export TOKEN_ROTATED='eyJ...'             # optional, expected 201 after JWKS rotation
./scripts/validate-oidc-acceptance.sh
```
