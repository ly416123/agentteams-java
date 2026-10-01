# Agent 执行过程与结果测评设计

**状态：** 设计已获确认，待用户审阅规格。
**日期：** 2026-10-02
**适用范围：** Agent、Skill、MCP 任务的离线回归和已完成任务的事后分析。

## 1. 目标

建立一套可复现、可解释的 Agent 测评体系，分别回答两个问题：

1. Agent 是否按允许的过程完成了任务？
2. 最终答案或成果物是否满足任务要求，并有足够证据支持？

执行成功不能替代质量合格。测评不得修改 Task、Run 或业务数据；评测证据必须受现有租户、项目、团队权限约束，并过滤凭证和预签名 URL。

## 2. 当前实现与差距

当前有两个可复用入口：

- `scripts/agentteams_eval.py` 按 YAML 用例创建任务、收集结果，并支持 Task 阶段、工具出现/禁止、输出包含文本、制品元数据等确定性断言。
- Control Plane 的 `/api/v1/evaluations` 对既有 Task/Run 生成持久化评测记录；Evidence Bundle 能聚合任务事件、子任务树、决策、工具摘要和结果制品元数据。Console 已有评测列表和详情页。

目前的缺口如下：

- Control Plane 评测只实现阶段、工具出现/禁止、制品元数据和任务类型断言；没有内容、顺序、依赖质量或工具结果断言。
- `required_tool` 由 `tool.called` 摘要判断，不能证明调用成功。Worker 当前完成事件也不足以区分 MCP 成功响应和异常响应。
- Evidence Bundle 虽包含任务树和决策，但现有评分没有使用它们；Agent 元数据为 `unknown`，审批和策略结果为空。
- 评测阶段从结果清单推导 Task 阶段，没有分别保留 Task phase、Execution Run status 和 Result manifest 状态。
- 证据事件最多读取 1000 条，没有显式标记截断或缺失，因此部分评测可能误把不完整证据当成完整证据。
- `semanticRequested=true` 当前会返回 `INCONCLUSIVE`。DeepEval 适配器在 API 之外；Faithfulness 缺少稳定的检索上下文，尚不能作为发布门禁。
- YAML Runner 的 Evidence Bundle 导出没有拉取 Task Tree 和 Decisions，和 Control Plane Evidence Bundle 的完整度不一致。
- L5 已有的真实回归套件覆盖 3 个基础场景；Control Plane 当前持久化样例主要是 Task/Run 成功阶段检查，尚未用过程与结果 rubric 评测多 Agent 集成任务。

## 3. 方案比较与选择

| 方案 | 收益 | 代价 |
|---|---|---|
| 仅做发布前回归门禁 | 易复现，适合比较 Agent/Skill 版本 | 对单次真实任务的故障定位较弱 |
| 仅做任务后质量分析 | 能解释单个任务的过程和交付质量 | 难以持续检测版本回归，语义判断容易漂移 |
| 分阶段共享评测体系 | 用同一套 Case、证据和指标支持回归及事后分析 | 需要统一证据格式，并区分硬门禁与语义建议 |

**选择：** 分阶段共享评测体系。第一阶段实现确定性过程门禁和结果契约；第二阶段增加校准过的语义分析。语义评分在人工样本校准前只作参考，不影响硬门禁结果。

## 4. 设计

### 4.1 评测对象和数据流

保留现有 Evaluation Suite、Case、Run、Result、Report 实体和 API。Runner 负责按版本化 Case 执行回归任务；已有任务可通过 Evaluation API 做事后评估。两种入口最终使用同一套 Evidence Bundle 和指标定义。

数据流：

```text
版本化 Suite/Case
        ├── Runner 创建并运行回归任务
        └── API 关联既有 Task/Execution Run
                    ↓
          权限过滤的 Evidence Bundle
                    ↓
     确定性过程门禁 → 结果契约 → 语义分析（后续）
                    ↓
       Evaluation Run、逐项 Result、Report
                    ↓
         Console 展示评分、过程和证据引用
```

### 4.2 证据包

Evidence Bundle 应保留可追溯的稳定引用，并补足以下信息：

- Task phase、Execution Run status、开始/结束时间和 Result manifest 状态分开保存。
- 子任务树包含依赖边、调度状态、重试次数、每个子任务的结果引用和聚合状态。
- 工具事件区分调用开始、成功完成、失败完成；保存工具名、序号、耗时、错误分类，以及脱敏后的结果引用。工具结果正文默认不写入评测数据库。
- Agent 元数据包含 Runtime、Model/provider、AgentSpec、Skill 和 MCP 版本或 digest，版本不可得时明确标记 `unknown`。
- 策略判定和审批决策使用可展示的安全摘要及稳定引用。
- 事件读取达到上限时设置 `evidenceCompleteness=TRUNCATED`，不能把截断证据判成完整通过。
- 继续过滤 Token、API Key、密码、Cookie、Secret、原始凭证和带签名下载 URL。

### 4.3 过程硬门禁

第一阶段支持以下确定性指标：

| 维度 | 通过条件示例 |
|---|---|
| 编排 | 必需拆解工具成功；子任务数和标题满足 Case；依赖边合法且无环 |
| 依赖交接 | 下游执行开始前，上游已成功；下游 Evidence/输入中包含对应上游结果引用 |
| 工具使用 | 必需工具有成功完成事件；禁止工具未调用；调用顺序和次数满足 Case |
| 调度与恢复 | 必需子任务最终成功；重试不超过上限；失败和超时均有终态记录 |
| 策略合规 | 没有违反 MCP、域、权限或审批策略的事件 |
| Task/Run 状态 | 分别核对 Task phase、Run status 和结果清单状态，不将其中一个代替其他状态 |

“被调用”与“成功调用”是不同断言。对必需工具的硬门禁必须依据成功完成事件，而不是仅依据 `tool.called`。

### 4.4 结果质量与制品契约

第一阶段结果评测以可判定契约为主：必需章节或字段、必需内容、引用/来源存在、禁止内容、格式、制品 MIME、扩展名、文件签名、大小、SHA-256 和下载验证。

PDF Case 还应能验证至少一页可解析、页面数范围，以及渲染检查结果。用于发布门禁的视觉布局断言应由固定模板或人工标注定义，不依赖无校准的 LLM 判断。

自由文本事实正确性不能仅由字符串命中判定。没有参考答案或检索证据时返回 `INCONCLUSIVE`，并说明缺少哪类证据。

### 4.5 语义评测

第二阶段由可插拔 Judge 对脱敏后的任务目标、最终输出、检索上下文和结构化 Rubric 评分。首批维度为目标相关性、证据支持度、要求覆盖度和表达清晰度。

- Judge 记录 provider、model、prompt 版本、rubric 版本和分数理由。
- Judge 输入只来自权限允许的 Evidence Bundle；预签名 URL、凭证和未授权 Task 内容不得发送。
- Judge 结果与硬门禁分开显示；硬门禁失败不能被语义高分抵消。
- `Faithfulness` 缺少可追溯检索上下文时必须跳过并标记 `INCONCLUSIVE`。
- 阈值由人工复核的通过/失败样本校准后再决定是否用于发布门禁。

### 4.6 结果状态与展示

Evaluation Report 不以单一均分掩盖失败。至少分别展示：

- `executionGate`：`PASSED`、`FAILED` 或 `INCONCLUSIVE`。
- `resultGate`：`PASSED`、`FAILED` 或 `INCONCLUSIVE`。
- `semanticAdvisory`：未执行、分数及校准状态；第一阶段不参与硬门禁。
- `taskSuccess`：独立展示 Task 是否成功。
- 每项指标的分数、阈值、状态、结论、证据引用和不确定原因。

硬门禁有失败项时整体门禁失败；没有失败但证据不完整时为 `INCONCLUSIVE`；只有全部必需硬门禁通过且证据完整时才通过。Console 详情还应链接到 Task/Run 时间线、子任务和安全制品引用。

### 4.7 代表性 Case：多 Agent 搜索与 PDF 汇总

以 L5 最近的真实流程作为首个集成 Case：主 Agent 调用 `plan_subtasks`；研究子任务加载 `industry-research` Skill 并使用 `web_search` 搜索 `www.w3.org`；报告子任务依赖研究结果；Leader 生成可下载 PDF。

预期断言包括：拆解成功、依赖边存在、上游结果交接成功、Skill 加载和搜索工具成功完成、搜索结果包含真实 W3C 页面引用、汇总满足报告结构要求、恰有一个可下载且签名有效的 PDF OUTPUT。

该 Case 还应覆盖负向变体：缺少依赖结果、调用了工具但工具失败、产生无效 PDF、没有覆盖任务要求、证据被截断。负向变体必须分别得到确定失败或 `INCONCLUSIVE`，不能因 Task `SUCCEEDED` 而通过。

## 5. 分阶段落地

### 阶段一：确定性评测闭环

1. 统一 YAML Runner 与 Control Plane 的 Evidence Bundle 字段和 Case 断言语义。
2. 为 Task phase、Run status、工具成功事件、工具顺序、依赖交接、重试、策略和 PDF 契约补充确定性指标。
3. 在 Evaluation API 中保存过程门禁和结果门禁的分项结果；Evidence 不完整时显式标记。
4. 将多 Agent 搜索与 PDF 集成 Case 纳入版本化 Suite，并在 L5 真实执行。
5. 在 Console 展示门禁维度、过程引用和失败原因。

### 阶段二：语义质量分析

1. 为工具结果建立安全、可追溯的检索上下文引用。
2. 接入 DeepEval 或等价 Judge 适配器，但不让其覆盖确定性硬门禁。
3. 收集人工复核样本，记录意见分歧、分数和 Rubric 版本。
4. 只有校准结果稳定后，才讨论是否把某一语义指标加入发布门禁。

## 6. 验收标准

- 同一个 Task/Run 使用相同 Case 和 evaluator 版本重复评估，逐项结果稳定且可追溯。
- Task 成功但必需工具失败时，过程门禁失败。
- 缺少上游依赖结果、关键过程事件或超出事件上限时，结果失败或评估不确定，不能通过。
- 制品评测验证耐久引用、内容类型、大小、SHA-256 和用户可下载内容；PDF Case 额外验证 `%PDF-` 签名和可解析页面。
- 任务满足过程门禁但答案缺少可验证依据时，过程状态可通过，结果质量为 `INCONCLUSIVE`。
- Judge 未配置或检索上下文不足时，语义结果为未执行/不确定，不阻断确定性评测，也不产生虚假的语义通过。
- 报告不包含凭证、预签名 URL 或未授权原始结果；每个失败项都能定位到安全证据引用。
- 真实 L5 集成 Case 完成正向和负向执行，并保留脱敏报告和可重放的 Suite/Case 版本。

## 7. 非目标

- 第一阶段不自动修改 Agent、Skill、MCP、权限或任务策略。
- 第一阶段不训练或微调模型。
- 不把无参考证据的 LLM 自评作为事实准确性证明。
- 不将模型消费成本、Token 用量或延迟混入质量分数；这些维度可链接现有 Usage 和 Trace 数据，之后单独扩展。

## 8. 已知实施依赖

- 真实 MCP 工具完成事件需要提供可靠的成功/失败语义，不能继续把任意完成事件统一标成 `ok=true`。
- Task Tree、Run 和 Result Manifest 的范围权限要在 Evidence Bundle 组装时统一校验。
- LLM Judge 的检索上下文、敏感信息处理、人工校准样本和模型使用成本需要在阶段二开始前落实。
- Evaluation Suite/Case 当前由仓库 YAML Runner 与数据库记录分别管理；阶段一需明确导入或同步契约，避免两套规则漂移。
