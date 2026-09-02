# Console 静态部署与 CI 契约实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为独立 Console SPA 提供非 root Nginx 静态镜像、可控 Helm 部署、Kind 入口和清晰的 CI/manifest 契约。

**架构：** Node 多阶段构建生成 `console/dist`，运行阶段使用非 root Nginx 和 `try_files` 完成 SPA fallback。Helm 在 `console.enabled` 时渲染 ConfigMap、Deployment 和 Service，公开 runtime config 只允许 API/OIDC 公共字段；Ingress 将 `/api` 送到 Control Plane、其余根路径送到 Console；当 Kind/L5 通过 Console NodePort 直连时，Nginx 对 `/api/v1/conversations`、`/api/v1/manager` 和其他 `/api/` 提供等价的内部反向代理。

**技术栈：** Docker multi-stage、nginx-unprivileged、Helm/Kubernetes YAML、Python 3 标准库与 PyYAML、GitHub Actions、Python unittest。

## 本轮执行记录（2026-08-30）

- `python3 scripts/validate-console-manifests.py` 和 `python3 scripts/validate-kind-manifests.py` 通过。
- Console production build、Lint 和 Prettier 检查通过；L5 NodePort 页面已由 Playwright 实际访问。

本记录不把本机验证扩大解释为生产 Kubernetes 发布或恢复验收。

---

## 文件职责

- 创建 `scripts/test_console_manifests_contract.py`：以失败优先的静态和渲染契约测试锁定部署行为。
- 创建 `deploy/docker/console.Dockerfile`：Node 构建和非 root Nginx 运行镜像。
- 创建 `deploy/docker/console-nginx.conf`：Nginx 8080 监听、静态资源缓存、SPA history fallback，以及 NodePort 直连所需的 API 反向代理。
- 创建 `deploy/helm/agentteams-java/templates/console.yaml`：公开 runtime ConfigMap、Console Deployment 和 Service。
- 修改 `deploy/helm/agentteams-java/values.yaml`：添加 Console 启用开关、镜像和公开配置默认值。
- 修改 `deploy/helm/agentteams-java/values.schema.json`：约束 Console values，拒绝未声明字段和 Secret 型配置键。
- 修改 `deploy/helm/agentteams-java/templates/ingress.yaml`：在 Console 启用时添加 `/`，并保留 API 路由。
- 修改 `deploy/kind-ingress.yaml`：将 `api.agentteams.localhost` 的 `/` 指向 Console、`/api` 指向 Control Plane。
- 修改 `deploy/helm/kind-values.yaml`、`deploy/build-images.sh`、`deploy/install-kind-dev.sh`：确保 Kind 启用并构建/加载 Console，且 Service 就绪后才应用入口。
- 修改 `.github/workflows/ci.yml`：Console 目录存在时执行 npm test/build/lint，目录缺失时清晰跳过，并执行 validator。
- 修改 `.gitignore`、`scripts/validate-kind-manifests.py`：排除本地环境文件并同步 Kind 部署顺序契约。
- 创建 `scripts/validate-console-manifests.py`：独立校验 Docker/Nginx/Helm/Kind/CI 契约；Helm 不可用时明确标记渲染检查未执行。

### 任务 1：契约测试（TDD 红灯）

- [ ] 编写测试：检查目标文件存在，Docker/Nginx 包含 Node 构建、非 root Nginx 和 `try_files`；检查 Helm values/schema/template 的 enabled/image/config、安全默认值和无 Secret 引用；检查 Kind `/api` 与 `/` 路由；检查 CI 的条件 npm 命令和 validator。
- [ ] 运行 `python3 -m unittest scripts/test_console_manifests_contract.py -v`，确认因目标文件不存在而失败。

### 任务 2：Docker/Nginx 最小实现

- [ ] 创建 Dockerfile 和 Nginx 配置，构建阶段仅复制 `console/package*.json` 安装依赖并执行 `npm run build`，运行阶段复制 `dist` 到 Nginx 静态目录并以 UID 101 启动。
- [ ] 运行契约测试，确认 Docker/Nginx 契约通过。

### 任务 3：Helm Console 工作负载

- [ ] 添加 values/schema/template，Console 关闭时不渲染资源，开启时渲染 ConfigMap、Deployment、Service；ConfigMap 只序列化公开配置，容器只挂载配置文件而不引用 Secret。
- [ ] 为只读根文件系统添加 `/tmp` emptyDir，并在渲染契约中检查该挂载。
- [ ] 运行契约测试和 `helm lint deploy/helm/agentteams-java`，确认渲染对象、selector、端口和安全上下文符合契约。

### 任务 4：入口路由与 CI/validator

- [ ] 更新 Helm/Kind Ingress，保证更具体的 `/api` 路由仍指向 Control Plane，根路径指向 Console。
- [ ] 更新 CI 条件检查，并实现 validator 的静态/渲染检查和缺少 `console/` 的明确说明。
- [ ] 在 CI 中先安装 Helm，再显式渲染启用 Console 的 chart，并执行 Console 契约测试。
- [ ] 运行 `python3 scripts/validate-console-manifests.py`、`python3 -m unittest scripts/test_console_manifests_contract.py -v`、`helm lint deploy/helm/agentteams-java`；若 Console 缺失，记录 npm 构建未执行而不是伪造成功。

### 任务 5：收尾提交

- [ ] 检查 `git diff --check`、禁改文件 diff 和工作树状态。
- [ ] 以 `feat(Console): ...` 中文 Conventional Commit 提交全部实现文件。
