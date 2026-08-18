# Kind 本地开发链路补齐设计

Date: 2026-08-18

Status: Approved by user (2026-08-18); implementation in progress

## 1. Purpose

打通终态验收标准「The full path runs from a clean Kind installation」的本地执行链路。当前断点：

1. `deploy/kind-dev-infra.yaml` 缺少 MinIO 部署与 `agentteams-storage` Secret，而 Helm chart 在 `storage.enabled=true` 时要求该 Secret（键 `access-key` / `secret-key`）。
2. 没有镜像构建/加载到 kind 的脚本或文档步骤，helm install 前镜像无从获取。
3. Helm 安装参数无版本化的 dev 覆盖文件，需要手工拼 `--set`。
4. README 的 Kind 步骤不完整，无法照做跑通。

## 2. Scope

只补本地开发设施，不修改应用代码，不引入生产设施声明：

- 修改：`deploy/kind-dev-infra.yaml`（追加 MinIO + Secret + bucket bootstrap Job）
- 新建：`deploy/build-images.sh`（构建三个服务镜像并加载到 kind）
- 新建：`deploy/helm/kind-values.yaml`（kind 环境覆盖值）
- 修改：`README.md`（补全可执行的 Kind 安装与冒烟验证序列）

非目标（后续独立范围）：OIDC 实现、OpenTelemetry、CI 镜像构建推送、生产级 PostgreSQL/NATS/MinIO 声明、Grafana dashboard。

## 3. Design

### 3.1 MinIO 开发设施（追加到 kind-dev-infra.yaml）

沿用文件内 PostgreSQL/NATS 的既有模式（Deployment + Service + emptyDir + bootstrap Job），追加四段：

**Secret `agentteams-storage`**（与 `agentteams-database` 同级）：

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: agentteams-storage
  namespace: agentteams
type: Opaque
stringData:
  access-key: minioadmin
  secret-key: minioadmin
```

**Deployment `minio`**：

- 镜像 `minio/minio:RELEASE.2024-11-07T00-52-20Z`（固定 tag，dev 可复现）
- args：`server /data --console-address :9001`
- 环境变量 `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` 从 `agentteams-storage` Secret 的 `access-key` / `secret-key` 读取
- 端口：`api` 9000、`console` 9001
- 探针：liveness/readiness 均为 `httpGet /minio/health/live`，端口 `api`
- 存储：`emptyDir` 挂载 `/data`（dev 专用，与 PostgreSQL 一致；README 已有警告）
- 标签：`app.kubernetes.io/name: minio`、`app.kubernetes.io/part-of: agentteams`

**Service `minio`**：selector 同 Deployment，端口 9000（API）+ 9001（console）。

**Job `minio-bucket-bootstrap`**（与 `nats-stream-bootstrap` 同模式）：

- 镜像 `minio/mc:RELEASE.2025-07-21T05-28-08Z`（固定 tag；该版本可获取且兼容当前 MinIO 服务）
- `restartPolicy: OnFailure`、`backoffLimit: 6`
- env 从 `agentteams-storage` Secret 读取 `access-key` / `secret-key`
- 命令：使用同一组环境变量轮询执行 `mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"`（每 2 秒重试），随后执行 `mc mb --ignore-existing local/agentteams`

### 3.2 镜像构建/加载脚本（新建 deploy/build-images.sh）

脚本风格与 `dev-env.sh` 一致（bash + set -euo pipefail + 中文提示）：

1. 前置检查：`docker`、`kind` 命令存在；`docker context` 指向可用守护进程（提示先 `source deploy/dev-env.sh`）；`kind get clusters` 包含 `agentteams`。
2. 对三个服务逐一执行：`docker build -f deploy/docker/<svc>.Dockerfile -t <tag> .`，tag 与 `values.yaml` 一致：`ghcr.io/ly416123/agentteams-control-plane:latest`、`ghcr.io/ly416123/agentteams-agent-gateway:latest`、`ghcr.io/ly416123/agentteams-operator:latest`。直接 docker build 保证与生产镜像构建路径一致（Dockerfile 已是多阶段，构建环境无需本机 Maven 依赖缓存管理）。
3. `kind load docker-image <tag> --name agentteams` 逐一加载；helm 的 `imagePullPolicy: IfNotPresent`（默认值）使本地镜像直接生效。
4. 脚本顶部注释说明用法与依赖（colima、kind 集群已创建）。

### 3.3 Helm dev 覆盖文件（新建 deploy/helm/kind-values.yaml）

基于 `values.yaml` 的覆盖，仅含 kind 环境需要改动的键：

```yaml
storage:
  enabled: true
  endpoint: http://minio:9000
  bucket: agentteams
  existingSecret: agentteams-storage
observability:
  serviceMonitor:
    enabled: false
```

Kind 默认不安装 Prometheus Operator，因此不能渲染 ServiceMonitor；接入观测设施时再单独开启。

其余沿用默认值：`database.jdbcUrl` 指向 `postgresql:5432`、`agentteams-database` Secret、scheduler 开启、`security.apiEnabled: false`（本地无 OIDC 实现，保持关闭）、NATS/Outbox 默认开启。

### 3.4 README 更新

将现有「Local infrastructure」一节的 Kind 部分替换为完整可执行序列：

```bash
source deploy/dev-env.sh
kind create cluster --config deploy/kind-config.yaml
kubectl apply -f deploy/kind-dev-infra.yaml
kubectl -n agentteams wait --for=condition=complete job/nats-stream-bootstrap --timeout=120s
kubectl -n agentteams wait --for=condition=complete job/minio-bucket-bootstrap --timeout=120s
./deploy/build-images.sh
helm install agentteams deploy/helm/agentteams-java -n agentteams -f deploy/helm/kind-values.yaml
kubectl -n agentteams wait --for=condition=available \
  deployment/agentteams-agentteams-java-control-plane \
  deployment/agentteams-agentteams-java-gateway \
  deployment/agentteams-agentteams-java-operator --timeout=300s
```

冒烟验证步骤（控制面 API 冒烟，请求体字段已核实自 `AgentController.CreateAgentRequest` 与 `TaskController.CreateTaskRequest`，三个端点均要求 `Idempotency-Key` 请求头）：

```bash
kubectl -n agentteams port-forward svc/agentteams-agentteams-java-control-plane 8080:8080 &
PORT_FORWARD_PID=$!
trap 'kill "$PORT_FORWARD_PID" 2>/dev/null || true' EXIT
until curl -fsS localhost:8080/actuator/health >/dev/null; do sleep 2; done
# 1. 健康检查
curl -fsS localhost:8080/actuator/health
# 2. 注册一个 Agent（capabilities 为 JSON 对象）
AGENT_ID=$(curl -fsS -X POST localhost:8080/api/v1/agents \
  -H 'Idempotency-Key: smoke-agent-1' -H 'Content-Type: application/json' \
  -d '{"name":"smoke-agent","runtime":"fake","capabilities":{"java":"17"}}' | jq -r '.id')
# 3. 创建任务（DRAFT）并入队
TASK_ID=$(curl -fsS -X POST localhost:8080/api/v1/tasks \
  -H 'Idempotency-Key: smoke-task-1' -H 'Content-Type: application/json' \
  -d '{"title":"smoke","description":"kind smoke task","spec":{}}' | jq -r '.id')
curl -fsS -X POST "localhost:8080/api/v1/tasks/${TASK_ID}/queue" -H 'Idempotency-Key: smoke-queue-1'
# 4. 查询任务状态
curl -fsS "localhost:8080/api/v1/tasks/${TASK_ID}"
```

冒烟验证的判定标准：健康检查通过、Agent 创建返回 201、任务创建返回 201、入队返回 phase=`QUEUED`、查询可读回任务。任务停留在 `QUEUED` 是预期行为——API 注册的 Agent 不会建立 gRPC 连接变为 READY，完整推送链路由集成测试 `TaskPushE2ETest`（Testcontainers + FakeAgent）覆盖，不属于本冒烟范围。

保留「生产部署必须自备持久 PostgreSQL/NATS/Secret」与 kind-dev-infra 为 dev-only 的现有警告。

### 3.5 Verification

- `helm lint deploy/helm/agentteams-java`
- `helm template agentteams deploy/helm/agentteams-java -f deploy/helm/kind-values.yaml` 渲染检查（storage 环境变量指向 minio:9000）
- `bash -n deploy/build-images.sh` 语法检查
- 在本机实际执行完整序列：apply kind-dev-infra → 两个 bootstrap Job 完成 → 构建并加载镜像 → helm install → 三个实际 Deployment ready → 冒烟 API 通过

## 4. Out of scope

- 应用代码改动（Java/SQL/proto）
- CI 镜像构建推送与 kind 冒烟 Job
- OIDC 实现、OpenTelemetry、Grafana dashboard
- 生产级基础设施声明（持久存储、External Secrets 等）
