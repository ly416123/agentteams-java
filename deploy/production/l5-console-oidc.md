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

如果 release 已经在 L5 运行并使用平台导入的本地镜像标签，只为修复入口或更新
Console 时不要重新覆盖 `images.*`。使用只包含入口配置的临时 overlay，保留当前
release 的镜像和凭据配置：

```yaml
# /tmp/l5-console-nodeport-values.yaml
console:
  service:
    type: NodePort
    nodePort: 30080
```

```bash
helm upgrade agentteams deploy/helm/agentteams-java \
  --namespace agentteams --reuse-values \
  -f /tmp/l5-console-nodeport-values.yaml \
  --wait --timeout 5m
```

完整 `l5-values.yaml` 适用于首次部署或明确要切换到其中声明的镜像标签时；已有
release 应先核对 `helm get values agentteams -n agentteams`，避免把已导入的本地
Control Plane/Operator 镜像替换为不可拉取的标签。

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