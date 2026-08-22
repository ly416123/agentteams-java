# Production Secret rotation

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
