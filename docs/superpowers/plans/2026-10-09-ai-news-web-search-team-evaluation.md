# AI 新闻与 Web Search 双 MCP 集成评测实施计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `superpowers:executing-plans` 逐任务实现此计划。步骤使用复选框（`- [x]`）语法来跟踪进度。

**目标：** 在 L5 为现有隔离评测 Team 增加 `ai-news` 能力，并通过一个依赖 AI 新闻和官方 Web Search 的多 Agent PDF 任务完成可重复验收。

**架构：** 在 AgentTeams 仓库添加可审阅、可重复部署的 L5 Kubernetes 清单，为 Control Plane 和 Worker 仅开放到 AI 新闻 MCP Pod 的 TCP 8000 访问。将 Executor 的 MCP 绑定扩展为 `agentteams-web-search` 与 `ai-news`，新增独立评测 Case 和离线契约验证；`news` 仓库现有镜像在本地构建为 linux/amd64，再导入 L5 K3s。

**技术栈：** Java 21 / Spring Boot、Python 3.12、MCP Streamable HTTP、Redis、Kubernetes/K3s、Helm、PyYAML、现有确定性评测 Runner。

---

## 文件结构与职责

- 创建 `deploy/l5/ai-news-mcp.yaml`：Redis 持久化、只读新闻配置、AI News MCP Deployment/Service、抓取 CronJob，以及仅允许 Control Plane/Worker 入站访问该 MCP 的 NetworkPolicy。
- 创建 `deploy/helm/l5-ai-news-values.yaml`：以 Helm overlay 将 Control Plane MCP HTTP 主机 allowlist 设为 `agentteams-mcp-tools,ai-news-mcp`，作为现有 L5 Release values 的附加覆盖文件。
- 创建 `eval/cases/ai-news-web-search.yaml`：仅包含新的双 MCP、多 Agent、依赖交接、PDF 正向 Case，不改历史 Case 和报告。
- 创建 `scripts/test_ai_news_web_search_eval_contract.py`：验证 Case scope、两项 Executor 工具、领导编排/PDF 工具及关键确定性断言，避免误删任一 MCP 门禁。
- 创建 `docs/superpowers/plans/2026-10-09-ai-news-web-search-team-evaluation.md`：记录可复现的实现和 L5 验收步骤。
- L5 运行产物写入 `eval/reports/2026-10-09-l5-ai-news-web-search-pdf-final.json`；不得覆盖旧报告。

## 任务 1：定义双 MCP 的离线评测契约

**文件：**
- 创建：`eval/cases/ai-news-web-search.yaml`
- 创建：`scripts/test_ai_news_web_search_eval_contract.py`

- [x] **步骤 1：创建独立 Case**

新增 `ai-news-web-search-pdf-positive`，scope 固定为 `tenant-a / project-a / team-a`，taskType 为 `agentscope`。提示词要求研究子任务分别调用 `search_news` 和 `web_search`，用官方 MCP 文档核对新闻所述技术事实；撰写子任务依赖研究结果；Leader 在依赖成功后汇总为一个 PDF。`requiredMcpTools` 为 `[plan_subtasks]`，`executorMcpTools` 为 `[search_news, web_search]`，`aggregateMcpTools` 为 `[create_task_pdf_artifact]`。不要要求 runner 对 AI News URL 作机器级来源验证，因为现有结构化证据仅覆盖 `web_search`。

- [x] **步骤 2：运行现有 Case Loader 并确认新 Case 可解析**

运行：`python3 -c 'from scripts.agentteams_eval import load_cases; cases=load_cases("eval/cases/ai-news-web-search.yaml"); print([case.case_id for case in cases])'`

预期：输出包含且仅包含 `ai-news-web-search-pdf-positive`。

- [x] **步骤 3：增加契约检查**

检查器调用 `load_cases` 并断言 scope 精确匹配，Case 中有 `phase=SUCCEEDED`、`run_status=SUCCEEDED`、`dependency_success`、`dependency_handoff`、`required_tool(search_news)`、`required_tool(web_search)`、`source_citation` 和恰好一个 durable/downloadable PDF 断言。再检查输入声明两种 Executor MCP 工具及 Leader 的 `plan_subtasks` 和 `create_task_pdf_artifact`。

- [x] **步骤 4：验证契约检查通过**

运行：`python3 scripts/test_ai_news_web_search_eval_contract.py`

预期：退出码 `0`，显示双 MCP Case 契约通过。临时删除一个工具断言时检查器必须失败；恢复文件后再次运行并通过。

- [x] **步骤 5：提交评测用例**

```bash
git add -- eval/cases/ai-news-web-search.yaml scripts/test_ai_news_web_search_eval_contract.py
git commit --only -m 'test(评测): 增加双 MCP PDF 集成用例' -- eval/cases/ai-news-web-search.yaml scripts/test_ai_news_web_search_eval_contract.py
```

## 任务 2：添加可审阅的 L5 AI 新闻 MCP 部署

**文件：**
- 创建：`deploy/l5/ai-news-mcp.yaml`
- 创建：`deploy/helm/l5-ai-news-values.yaml`

- [x] **步骤 1：创建 Kubernetes 清单**

清单放在 `agentteams` namespace，包括：`ai-news-redis` PVC/StatefulSet/Service；从镜像 `/app/config` 只读挂载 `config/sources.yaml` 与 `config/settings.yaml` 的 ConfigMap；`ai-news-mcp` Deployment/ClusterIP Service，端口 8000、MCP 路径 `/mcp`，环境变量 `MCP_HOST=0.0.0.0`、`MCP_PORT=8000`、`REDIS_URL=redis://ai-news-redis:6379/0`，使用 `IfNotPresent` 固定版本镜像并以 `python -m news mcp` 启动；调用 `python -m news run-once --dry-run` 的 CronJob，沿用同一只读配置和 Redis URL。匹配 `app.kubernetes.io/name=ai-news-mcp` Pod 的 ingress NetworkPolicy 只允许 Control Plane 和 Worker 来源 TCP 8000。另建只选择 `agentteams-control-plane` 的 egress NetworkPolicy，目标仅为 AI News Pod TCP 8000，以补充现有 Control Plane egress allowlist；不创建选择 Worker 的 egress NetworkPolicy，避免改变 Worker 现有 Gateway/模型访问能力。Redis 独立 PVC，并用 ingress NetworkPolicy 仅允许 MCP/CronJob Pod 访问 TCP 6379。该服务不配置推送 Webhook 或翻译 API 凭证，清单不包含密码、Token 或 `.env`。

- [x] **步骤 2：将 Control Plane 主机加入 allowlist**

在 `deploy/helm/l5-ai-news-values.yaml` 中定义 `controlPlane.env.AGENTTEAMS_MCP_HTTP_ALLOWED_HOSTS: "agentteams-mcp-tools,ai-news-mcp"`，不覆盖已有 L5 values 的其他键。

- [x] **步骤 3：渲染并静态检查 Helm 与 YAML**

运行：`helm template agentteams deploy/helm/agentteams-java -f deploy/helm/l5-values.yaml -f deploy/helm/l5-ai-news-values.yaml >/tmp/agentteams-l5-rendered.yaml`

运行：`python3 -c 'import yaml; list(yaml.safe_load_all(open("deploy/l5/ai-news-mcp.yaml"))); print("manifest YAML OK")'`

预期：两个命令退出码为 `0`；使用 `helm template agentteams deploy/helm/agentteams-java -f deploy/helm/l5-values.yaml -f deploy/helm/l5-ai-news-values.yaml` 渲染后 CP allowlist 包含两个服务名。清单 NetworkPolicy 的 AI News 目标端口为 `8000/TCP`，Redis 仅接受 MCP/CronJob 来源的 TCP 6379 入站。

- [x] **步骤 4：本地构建 linux/amd64 镜像并检查镜像身份**

在临时构建目录中只复制 `/Users/gecko/code/news/Dockerfile`、`pyproject.toml`、`src/`、`config/`，确认不复制 `.env`、`.git/`、`.venv/`，再从该临时目录执行 `docker buildx build --platform linux/amd64 --load -t agentteams-ai-news:2026-10-09-1 .`。确认 `docker image inspect agentteams-ai-news:2026-10-09-1 --format '{{.Os}}/{{.Architecture}}'` 输出 `linux/amd64`；执行 以临时上下文中的 Dockerfile `FROM redis:7.4.2-alpine` 执行 `docker buildx build --platform linux/amd64 --load -t agentteams-redis:7.4.2-alpine <context>`，确认输出架构为 linux/amd64；随后用 `docker save -o /tmp/agentteams-ai-news-l5-20261009.tar agentteams-ai-news:2026-10-09-1 agentteams-redis:7.4.2-alpine` 将两个单架构镜像写入同一 tar。保存两个镜像摘要和 tar SHA-256 作为 L5 核验信息。

- [x] **步骤 5：提交部署清单**

```bash
git add -- deploy/l5/ai-news-mcp.yaml deploy/helm/l5-ai-news-values.yaml
git commit --only -m 'feat(部署): 添加 L5 AI 新闻 MCP 服务' -- deploy/l5/ai-news-mcp.yaml deploy/helm/l5-ai-news-values.yaml
```

## 任务 3：部署并验证 MCP 服务及网络边界

**文件/对象：**
- 使用：`deploy/l5/ai-news-mcp.yaml`
- 更新：L5 上名为 `agentteams` 的 Helm Release Control Plane allowlist
- 不创建 Secret；AI News MCP 和 CronJob 通过专用 Redis Service 访问无认证 Redis，Redis 入站策略仅允许这两个 Pod。

- [x] **步骤 1：部署前核对 L5 资源与目标对象**

读取 L5 节点可用内存、namespace 资源、现有 AI News/Redis 对象、现有 MCP 注册、目标 Team 当前 revision 和 Worker Ready 状态。确认资源余量足以新增 Redis 与 MCP 两个 Pod；如不足，停止并报告实测数据，不杀其他任务或工作负载。

- [x] **步骤 2：传输镜像并导入 K3s**

使用 `scp /tmp/agentteams-ai-news-l5-20261009.tar ly@192.168.122.55:/tmp/agentteams-ai-news-l5-20261009.tar` 传输镜像；在 L5 运行 `sudo -n /usr/local/bin/k3s ctr -n k8s.io images import /tmp/agentteams-ai-news-l5-20261009.tar`，随后运行 `sudo -n /usr/local/bin/k3s crictl images` 并确认 `agentteams-ai-news:2026-10-09-1` 和 `agentteams-redis:7.4.2-alpine` 两个目标镜像都已导入。传输前后运行 `shasum -a 256` / `sha256sum` 比较 tar SHA-256 一致后再清理临时文件。

- [x] **步骤 3：应用 Redis 与 MCP 清单**

运行 `kubectl apply -f deploy/l5/ai-news-mcp.yaml`。确认 Redis Service 没有 NodePort/LoadBalancer，并检查 Redis ingress NetworkPolicy 的来源仅为 AI News MCP 与 CronJob Pod。L5 当前 Helm release 引用的旧 Chart schema 与 release values 不兼容，`helm upgrade --dry-run` 会拒绝升级且不会修改 release；因此对现有 ConfigMap `agentteams-agentteams-java-control-plane` 仅 patch 数据键 `AGENTTEAMS_MCP_HTTP_ALLOWED_HOSTS=agentteams-mcp-tools,ai-news-mcp`，再执行 `kubectl rollout restart deployment/agentteams-agentteams-java-control-plane` 并等待 rollout。`deploy/helm/l5-ai-news-values.yaml` 保留该值供 Chart/schema 对齐后的 Helm 发布使用。

- [x] **步骤 4：等待存储、MCP 与抓取 Job Ready**

运行 `kubectl -n agentteams rollout status statefulset/ai-news-redis --timeout=180s` 和 `kubectl -n agentteams rollout status deployment/ai-news-mcp --timeout=180s`；运行 `kubectl -n agentteams create job --from=cronjob/ai-news-fetch ai-news-fetch-initial-20261009`，确认 Job Complete 且 MCP readiness 为 Ready。任何失败都停止后续 registry/Team 更新，并仅收集脱敏日志。

- [x] **步骤 5：验证 MCP 工具与 NetworkPolicy**

从 Control Plane 网络上下文执行 MCP `tools/list`，确认只暴露 `search_news`；从 Worker 网络上下文检查 MCP HTTP 连通性；检查 NetworkPolicy 只放行 Control Plane/Worker 到 `ai-news-mcp` TCP 8000，同时 Redis 不暴露 NodePort/LoadBalancer。

- [x] **步骤 6：记录镜像摘要和服务状态**

记录 Deployment 镜像 digest、Service ClusterIP、Ready 副本、Redis PVC、CronJob 最近成功时间及 NetworkPolicy 对象名，供最终人工验收复核。

## 任务 4：注册 MCP 并发布隔离 Team 新 revision

**对象：** L5 MCP registry、Executor AgentSpec、隔离 Team revision 和两个 Worker。

- [x] **步骤 1：注册 `ai-news` MCP**

使用 L5 正式 MCP 管理 API 创建 tenant/project/team scope 为 `tenant-a / project-a / team-a` 的 `ai-news` 连接；endpoint 为 `http://ai-news-mcp:8000/mcp`，HTTP only，allowlist 仅 `search_news`，timeout `30000ms`。执行 discovery 和连接测试；两项均成功后继续。

- [x] **步骤 2：更新现有 Executor AgentSpec**

只修改现有隔离评测 Team 的 Executor AgentSpec，保留 provider/model 和 `agentteams-web-search`，将 `ai-news` 加入 `mcpRefs` 并将 `skillRefs` 设为空列表。该隔离 Case 不依赖 industry-research Skill；移除它是因为其 `allowedMcp`/`allowedTools` 交集排除 `ai-news/search_news`。不改 Skill 资源或其他 Team。通过正式 API 发布配置并等待对应 Worker 配置 ACK；确认 discovery 工具集同时含 `search_news` 与 `web_search`，且子任务运行时只启用 `search_news` 与 `web_search`。

- [x] **步骤 3：发布隔离 Team 新 revision**

基于现有 Leader/Executor 配置发布同一个隔离 Team 的新 revision 并显式部署。确认 Team active、目标 scope 正确、两个成员仍是原来的 Worker，Ready 且配置 ACK；不创建 Worker、不修改共享 `team-a`。

## 任务 5：运行 L5 双 MCP 整体评测并交付人工检查

**文件：**
- 读取：`eval/cases/ai-news-web-search.yaml`
- 创建：`eval/reports/2026-10-09-l5-ai-news-web-search-pdf-final.json`

- [x] **步骤 1：执行 L5 预检**

在 L5 上使用 `quota-admin` ADMIN 的短期 Token（只放在环境变量，不写命令历史/报告），运行新 Case 的 scope、项目、Team、Ready Worker 预检。不得在聊天或日志中粘贴 Token。

- [x] **步骤 2：运行独立评测 Case**

运行：`PYTHONPATH="$PWD:$PWD/wheels/pypdf-6.19.0-py3-none-any.whl" python3 scripts/run-l5-agentteams-eval.py --cases eval/cases/ai-news-web-search.yaml --case ai-news-web-search-pdf-positive --tenant tenant-a --project project-a --team team-a --team-id 7335a7df-fde4-41de-983c-f5045d1486f9 --report eval/reports/2026-10-09-l5-ai-news-web-search-pdf-final.json`

预期：Case 执行完成且报告生成。报告中的 task/run/subtask ID、双工具成功事件、dependency/handoff、web_search 来源验证、唯一 PDF 完整性均可复核；新闻来源链接人工对照 `search_news` 输出和 PDF 来源列表。

- [x] **步骤 3：根据报告逐项判定**

只有所有必需门禁均为 `PASSED` 才报告整体通过。必需工具缺失为 `FAILED`，证据被截断/缺失为 `INCONCLUSIVE`；不以 Task 成功替代整体评测通过。失败时保留报告和脱敏错误分类，不重跑覆盖原报告。

- [x] **步骤 4：交付 L5 人工验收信息**

提供 Team 名称/ID/revision、Executor 双 MCP 绑定、Worker Ready 状态、评测 Case 和报告路径、Task/Run/PDF 位置及逐项门禁结果。确认测试报告没有 Token、Secret 或预签名 URL。

## L5 最终执行记录（2026-10-09）

- Team：`l5-agentteams-pdf-eval-20261008-1705-6d4a`，ID `7335a7df-fde4-41de-983c-f5045d1486f9`，revision 2；隔离 Executor 同时绑定 `agentteams-web-search` 与 `ai-news`，不绑定 Skill。
- Worker：Leader 和 Executor 均由现有 Team revision 部署成功；Executor 使用启用来源上下文捕获的新镜像，L5 Pod Ready。
- 最终评测：Task `33c272cd-79aa-4038-8eea-4029c9db9127`，Run `28bf6b80-4798-45cf-8e90-a5ea0e93c33b`，报告整体 `PASSED`。任务/运行、依赖成功与交接、`search_news`、`web_search`、来源引用和 PDF 完整性断言均为 `PASSED`。
- PDF：`quarterly-project-report.pdf`，Task File ID `95273c12-3f64-4160-92a9-e36d968f8618`；报告已验证文件名/MIME、下载字节、SHA-256、PDF 签名及页树。L5 可通过该 Task 的 OUTPUT 文件列表人工打开或下载。
- 报告：L5 与脱敏副本均为 `/home/ly/l5-eval-runner-89aad28a/eval/reports/2026-10-09-l5-ai-news-web-search-pdf-final.json` 和 `eval/reports/2026-10-09-l5-ai-news-web-search-pdf-final.json`。此前 rerun3 检出模型把 PDF Task File 元数据另作为 142 字节伪 PDF 写入 Result Manifest；已在 Case 提示中明确 PDF 已由 MCP 上传、最终答案必须输出纯 Markdown，最终运行只保留一个可验证 PDF。
- Runner 评测来源引用时从固定 allowlist origin 下载 `output.md`，禁用重定向，读取上限为 2 MiB + 1 字节，并核对大小、SHA-256、UTF-8 和文本 MIME 后才检查 URL；网络失败不会输出预签名 URL，报告只保留摘要字段，不包含答案正文、URL、下载签名或 Token。
- 用户人工复核待办：在 L5 任务详情中打开 Task ID，确认 OUTPUT 中的 `quarterly-project-report.pdf` 可下载且内容符合预期。此复核完成前，自动化验收通过不代表用户已完成人工审阅。

## 计划自检

- 设计目标中的部署、目录注册、Executor 更新、Team revision、依赖任务、PDF、确定性门禁和人工验收都有对应任务。
- 网络策略同时覆盖 Control Plane MCP discovery 与 Agent Worker 工具运行时连接。
- AI News 服务不配置 Webhook 或翻译凭证；专用 Redis 通过 NetworkPolicy 限制入站，仓库、日志和报告不包含 Token 或外部 MCP 凭证。隔离评测 Executor 移除不兼容的 Skill 绑定，但不修改 Skill 定义或共享 Team。
- 新评测文件和报告均独立，不覆盖此前历史结果。
- 代码和文档变更只在 `main` 分支；每次提交使用路径限定，不包含工作区已有的其他 staged/unstaged 修改。
