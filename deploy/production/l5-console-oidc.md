# L5 Console 登录配置

L5 浏览器入口为 `http://192.168.122.55:30080`，Keycloak 的浏览器入口为
`http://192.168.122.55:30082`。L5 必须使用仓库中的
[`deploy/helm/l5-values.yaml`](../helm/l5-values.yaml) 覆盖 OIDC 配置。

## 根因

`deploy/helm/kind-values.yaml` 面向本机 Kind，OIDC issuer 使用浏览器所在机器的
`127.0.0.1:18082` 端口转发。L5 复用该文件会导致浏览器把 Keycloak 请求发到用户
电脑的本地端口，最终显示“登录服务不可用”。

## 部署要求

先让 Keycloak 返回 L5 可访问的 issuer，再执行 Helm 更新：

```bash
kubectl -n agentteams set env deployment/keycloak \
  KC_HOSTNAME=http://192.168.122.55:30082

helm upgrade agentteams deploy/helm/agentteams-java \
  --namespace agentteams --reuse-values \
  -f deploy/helm/l5-values.yaml \
  --wait --timeout 5m
```

`console.config.oidcIssuer`、Control Plane 的 `issuerUri` 和 Manager 的
`issuerUri` 必须保持一致；Control Plane 和 Manager 的 `jwkSetUri` 仍使用集群内的
`http://keycloak:8080/.../certs`，不应改成浏览器 NodePort。

## 验证

```bash
curl -fsS http://192.168.122.55:30080/config.js
curl -fsS http://192.168.122.55:30082/realms/agentteams/.well-known/openid-configuration
kubectl -n agentteams get deploy \
  keycloak agentteams-agentteams-java-console \
  agentteams-agentteams-java-control-plane agentteams-agentteams-java-manager
```

验证结果应满足：Console、Keycloak discovery 返回 HTTP 200，OIDC discovery 的
`issuer` 为 `http://192.168.122.55:30082/realms/agentteams`，四个 Deployment 均为
Ready。不要在 L5 重新直接套用 Kind 的 `127.0.0.1:18082` 配置。
