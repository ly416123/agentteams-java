# DeepSeek 本地接入实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在不把 DeepSeek API Key 写入仓库或提交历史的前提下，让 QwenPaw 和 Manager 都使用 `deepseek-v4-flash`，并完成本地 Kind 环境中的真实连通性与任务闭环验证。

**架构：** Manager 继续作为库使用，通过环境变量构造 DeepSeek Provider，并提供仅供本地执行的 Java smoke 入口，不新增 Manager 服务部署。QwenPaw 通过其 HTTP API 配置 DeepSeek Provider、模型和 active model；现有 Worker 继续通过 `QwenPawHttpRuntimePort` 调用 QwenPaw。密钥只从被 Git 忽略的根目录 `apikey` 文件或进程环境读取，脚本不通过命令行参数传递密钥，也不打印响应正文。

**技术栈：** Java 17、Maven、JUnit 5、Bash、curl、jq、kubectl、Kind、QwenPaw HTTP API、DeepSeek OpenAI-compatible API。

---

## 1. 本地密钥安全边界

- [ ] 确认根目录 `apikey` 已被 `.gitignore` 忽略且未被 Git 跟踪。
- [ ] 将本地 `apikey` 权限收紧为仅当前用户可读（`0600`），不读取或输出其内容。
- [ ] 增加脚本侧校验：密钥文件必须存在、只包含一个非空值、权限不能对组或其他用户开放。
- [ ] 用 `git check-ignore`、`git ls-files` 和敏感信息扫描确认密钥没有进入 Git 工作集或 diff。

## 2. Manager DeepSeek 配置与本地 smoke

- [ ] 先编写 `DeepSeekConfigurationTest`，覆盖必填 API Key、默认模型 `deepseek-v4-flash`、环境变量覆盖和非法超时配置。
- [ ] 运行该测试，确认在实现缺失时按预期失败（RED）。
- [ ] 新增 `DeepSeekConfiguration`，集中解析 `DEEPSEEK_API_KEY`、`DEEPSEEK_MODEL`、`DEEPSEEK_TIMEOUT_SECONDS`，并创建现有 `DeepSeekProvider`。
- [ ] 新增 `ManagerSmokeApplication`，通过真实 `DeepSeekProvider` 发起最小请求，仅输出脱敏成功标记和模型名。
- [ ] 新增 `scripts/smoke-deepseek-manager.sh`，从 `apikey` 安全读取密钥、构造 Maven classpath 并执行 Java smoke；脚本不能把密钥放入命令行参数或日志。
- [ ] 运行配置单测和 Manager smoke，确认 DeepSeek 返回非空响应。

## 3. QwenPaw Provider 与 active model 配置

- [ ] 新增 `scripts/configure-local-qwenpaw-deepseek.sh`，通过 QwenPaw API 配置内置 `deepseek` Provider 的 API Key、确保 `deepseek-v4-flash` 模型存在、设置 global active model，并调用 provider test。
- [ ] 脚本启动临时 `kubectl port-forward`，等待 QwenPaw API 可用，失败时输出脱敏诊断信息并清理后台进程。
- [ ] 脚本验证 active model 返回 `deepseek/deepseek-v4-flash`，不打印包含敏感配置的完整 API 响应。
- [ ] 运行脚本并确认 QwenPaw 的 active model 已切换且 provider test 成功。

## 4. QwenPaw 真实任务闭环

- [ ] 新增 `scripts/smoke-kind-qwenpaw-deepseek.sh`，通过 Control Plane 创建带幂等键的最小任务并轮询任务状态。
- [ ] 验证 Worker 能够领取任务、调用 QwenPaw、回传结果；任务最终为 `SUCCEEDED`，失败时保留脱敏的状态和日志线索。
- [ ] 确认 QwenPaw、Worker、Control Plane 三个 Deployment 均 Ready，避免把基础设施未就绪误判为模型问题。

## 5. 文档、回归与交付检查

- [ ] 在 README 增加本地 DeepSeek 配置和 smoke 命令，只记录变量名、文件路径和脱敏示例，不记录真实 Key。
- [ ] 执行 `bash -n`、相关 Java 单测、完整 Maven 测试、脚本静态检查、Kind manifest/Helm 校验和 `git diff --check`。
- [ ] 检查 `git status`、`git check-ignore -v apikey`、`git ls-files apikey` 及 diff，确认没有任何 API Key 泄露。
- [ ] 汇总真实验证结果；若外部 API、集群或契约仍阻塞，记录具体现象、预期和下一步，而不是标记为完成。
