# L5 Console NodePort 入口实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:executing-plans 逐任务实现此计划。

**目标：** 为 L5 Console 提供固定的 `30080` NodePort 入口，并完成契约、渲染和远端升级验证。

**架构：** 默认 Console Service 继续使用 ClusterIP；L5 overlay 将其切换为 NodePort，并固定 nodePort 30080。Console Nginx 负责把浏览器请求的 `/api`、会话和文件路径代理到内部服务。

**技术栈：** Helm values/schema/templates、Python unittest、kubectl/k3s、curl。

---

### 任务 1：添加失败契约测试

**文件：**
- 修改：`scripts/test_l5_console_oidc_contract.py`

- [ ] 添加测试读取 `deploy/helm/l5-values.yaml`，断言存在 `console.service.type: NodePort` 和 `console.service.nodePort: 30080`。
- [ ] 添加测试渲染 Helm chart（使用仓库已有的 `helm template` 命令）并断言 Console Service 的 `type`、`nodePort` 和 targetPort。
- [ ] 运行 `python3 -m unittest scripts/test_l5_console_oidc_contract.py -v`，预期新增断言失败，因为当前模板没有读取 `console.service`。

### 任务 2：实现最小 Helm 配置

**文件：**
- 修改：`deploy/helm/agentteams-java/values.yaml`
- 修改：`deploy/helm/agentteams-java/templates/console.yaml`
- 修改：`deploy/helm/agentteams-java/values.schema.json`
- 修改：`deploy/helm/l5-values.yaml`

- [ ] 在默认 values 增加 `console.service.type: ClusterIP` 和 `console.service.nodePort: null`。
- [ ] 在 Console Service 模板渲染 `type`，仅当类型为 NodePort 且 nodePort 非空时渲染 `nodePort`。
- [ ] 在 schema 增加 service 对象约束：type 只能为 ClusterIP/NodePort，nodePort 为 30000-32767 的整数或 null。
- [ ] 在 L5 overlay 设置 type 为 NodePort、nodePort 为 30080。
- [ ] 运行目标契约测试和 `helm lint deploy/helm/agentteams-java -f deploy/helm/kind-values.yaml -f deploy/helm/l5-values.yaml`，预期通过。

### 任务 3：渲染和静态验证

**文件：**
- 无新增文件

- [ ] 渲染 `helm template agentteams deploy/helm/agentteams-java -f deploy/helm/kind-values.yaml -f deploy/helm/kind-oidc-values.yaml -f deploy/helm/l5-values.yaml`。
- [ ] 用 YAML 解析结果确认只有 Console Service 为 NodePort 30080，Control Plane、Gateway、MCP 仍为 ClusterIP。
- [ ] 运行 `git diff --check` 和相关 Python 契约全量测试。

### 任务 4：提交并更新远端 main

**文件：**
- 以上变更文件

- [ ] 提交 `fix(部署): 为 L5 Console 固定 NodePort 入口`。
- [ ] 通过 GitHub Git Data API 将提交内容更新到远端 `main`，记录返回 SHA。
- [ ] 等待 CI 的 verify、kind-oidc、kind-recovery 三个 job 全部成功。

### 任务 5：L5 滚动升级和验收

**文件：**
- 无仓库文件变更

- [ ] 在 `ly@192.168.122.55` 读取当前 Helm revision 和服务状态。
- [ ] 使用最新 chart 执行 `helm upgrade --reuse-values -f deploy/helm/l5-values.yaml --wait --timeout 5m`；若远端没有 helm 二进制，使用与 release 相同的受控 Helm 客户端或现有部署入口，不直接删除资源。
- [ ] 验证 Console Service 为 NodePort 30080、Endpoints Ready、`curl http://192.168.122.55:30080/config.js` 返回 200，OIDC discovery 返回 200，四个核心 Deployment Ready。
- [ ] 执行一次 Helm/Kubernetes 状态复核，确认 MCP Service 仍为 ClusterIP 且 Pod 未发生非预期重启。