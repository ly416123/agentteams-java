# L5 Console NodePort 入口设计

## 目标

让 L5 主机通过 `http://192.168.122.55:30080` 稳定访问 Console，并让 Console 内置 Nginx 继续代理 `/api`、会话和文件请求到集群内的 Control Plane/Manager。当前 L5 的 Pod 已 Ready，但没有 Ingress Controller、Ingress 或 30080 NodePort，导致文档入口不可达。

## 方案

L5 Helm overlay 为 Console Service 增加固定 NodePort：Service 类型为 `NodePort`，服务端口保持 8080，NodePort 使用 30080。Console 容器内的 Nginx 已负责 `/api` 路由，因此不新增 Ingress Controller，也不改变 Control Plane、Gateway、MCP Service 的暴露范围。默认 values 保持 ClusterIP，避免影响 Kind 和 API-only 安装。

## 配置与验证

新增 `console.service.type` 和可选 `console.service.nodePort`，JSON schema 限制类型为 `ClusterIP`/`NodePort`，NodePort 在 30000-32767。`l5-values.yaml` 固定为 NodePort 30080。契约测试检查渲染后的 Service 类型、端口和 L5 overlay，同时保留现有 OIDC 配置检查。升级前用 Helm lint/template，升级后检查 Service、Endpoint、`config.js`、Console/API readiness 和回滚状态。

## 风险控制

NodePort 仅暴露 Console 入口；Control Plane、Gateway、MCP、数据库和对象存储仍为 ClusterIP，Keycloak/MinIO 现有 NodePort 不变。L5 升级使用 `helm upgrade --reuse-values -f deploy/helm/l5-values.yaml --wait`，失败时保留原 release revision 并执行 Helm rollback。