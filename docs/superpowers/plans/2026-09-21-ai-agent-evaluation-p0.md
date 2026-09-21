# AgentTeams AI Agent 评测 P0 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `superpowers:executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 建立一个不依赖生产运行时的评测 Runner，通过 AgentTeams Task API 执行版本化 YAML 用例，采集任务状态、运行结果、过程事件和制品，并输出可用于 CI 门禁的 JSON 报告。

**架构：** `scripts/agentteams_eval.py` 负责 HTTP 客户端、用例加载、任务轮询、数据归一化和确定性断言；`eval/cases/*.yaml` 保存不含凭证的评测用例；`scripts/test_agentteams_eval.py` 使用 Fake Client 验证 Runner，不连接 L5。Runner 只依赖现有公开 API，不改变 Control Plane、Gateway 或 Worker。

**技术栈：** Python 3、标准库 `urllib`、`unittest`、PyYAML、AgentTeams Task/Process/Result/Artifact API。

---

## 文件清单

- 创建：`scripts/agentteams_eval.py`，可测试的 Runner 和 CLI。
- 创建：`scripts/test_agentteams_eval.py`，用例加载、事件归一化、断言和报告测试。
- 创建：`eval/cases/core.yaml`，首批 3 个不含凭证的核心用例。
- 创建：`eval/README.md`，本地、L5 和 CI 使用方式。

### 任务 1：建立评测数据结构和 Fake Client 测试

**文件：**

- 创建：`scripts/test_agentteams_eval.py`
- 创建：`scripts/agentteams_eval.py`

- [x] **步骤 1：编写失败测试**

测试以下行为：

1. YAML 用例能够加载为带 `id`、`task` 和 `assertions` 的对象。
2. `tool.called` 事件能够识别 `web_search` 工具名。
3. `required_tool`、`forbidden_tool` 和 `output_contains` 断言能返回通过或失败原因。
4. 一个成功的 Fake Client 运行生成 `passed=true`、总分 `1.0` 和任务 ID。
5. 任一硬断言失败时报告 `passed=false`。

运行：

```bash
python3 -m unittest scripts/test_agentteams_eval.py -v
```

预期：失败，原因是 `scripts.agentteams_eval` 和 Runner 类型尚不存在。

- [x] **步骤 2：实现最小数据结构和断言函数**

在 `scripts/agentteams_eval.py` 中实现：

```python
@dataclass(frozen=True)
class EvalCase:
    case_id: str
    name: str
    task: dict[str, Any]
    assertions: list[dict[str, Any]]
    limits: dict[str, Any]

@dataclass(frozen=True)
class AssertionResult:
    assertion_type: str
    passed: bool
    message: str
    expected: Any = None
    actual: Any = None
```

实现 `evaluate_observation(case, observation) -> list[AssertionResult]`，按断言类型逐条返回 `AssertionResult`。

支持的断言类型固定为：`phase`、`required_tool`、`forbidden_tool`、`output_contains`、`artifact_integrity`。未知断言类型必须返回失败结果，不得静默跳过。

- [x] **步骤 3：运行单元测试确认通过**

运行：

```bash
python3 -m unittest scripts/test_agentteams_eval.py -v
```

预期：所有数据结构、事件解析和断言测试通过。

- [x] **步骤 4：提交**

```bash
git add scripts/agentteams_eval.py scripts/test_agentteams_eval.py
git commit -m "feat(eval): add deterministic evaluation core"
```

### 任务 2：接入 AgentTeams API 和报告输出

**文件：**

- 修改：`scripts/agentteams_eval.py`
- 修改：`scripts/test_agentteams_eval.py`

- [x] **步骤 1：为 HTTP 客户端补写失败测试**

Fake Transport 必须验证以下请求顺序：

```text
POST /api/v1/tasks
POST /api/v1/tasks/{taskId}/queue
GET  /api/v1/tasks/{taskId}
GET  /api/v1/tasks/{taskId}/runs
GET  /api/v1/tasks/{taskId}/runs/{runId}/process-events
GET  /api/v1/tasks/{taskId}/runs/{runId}/result
```

测试还必须验证每个请求携带 `Authorization: Bearer test-token`，创建和排队请求携带唯一 `Idempotency-Key`。

- [x] **步骤 2：实现 API 客户端和轮询**

实现 `AgentTeamsClient`：

- `create_task(case)`：从用例的 `task` 生成任务请求。
- `queue_task(task_id, expected_version)`：排队并返回最新任务。
- `wait_for_terminal(task_id, timeout_seconds, poll_seconds)`：只接受 `SUCCEEDED`、`FAILED`、`CANCELLED` 为终态，超时抛出明确异常。
- `get_run(task_id)`：读取 `/runs`，兼容 `id` 和 `runId` 字段。
- `get_observation(task_id, run_id)`：读取 process events 和 result，组成断言输入。

客户端必须把 HTTP 状态码、接口路径和响应截断内容放入 `EvalError`，禁止把 Bearer Token 写入异常或报告。

- [x] **步骤 3：实现 JSON 报告和退出码**

报告结构固定为：

```json
{
  "suite": "core",
  "startedAt": "2026-09-21T00:00:00Z",
  "finishedAt": "2026-09-21T00:00:05Z",
  "passed": true,
  "cases": [
    {
      "caseId": "mcp-web-search-title",
      "taskId": "00000000-0000-0000-0000-000000000001",
      "runId": "00000000-0000-0000-0000-000000000002",
      "passed": true,
      "score": 1.0,
      "assertions": []
    }
  ]
}
```

CLI 参数固定为：`--base-url`、`--token-env`、`--cases`、`--report`、`--timeout-seconds` 和 `--poll-seconds`。任何 case 失败时进程返回 1，网络或配置错误返回 2。

- [x] **步骤 4：运行单元测试和静态检查**

运行：

```bash
python3 -m unittest scripts/test_agentteams_eval.py -v
python3 -m py_compile scripts/agentteams_eval.py scripts/test_agentteams_eval.py
```

预期：测试通过，且不产生编译错误。

- [x] **步骤 5：提交**

```bash
git add scripts/agentteams_eval.py scripts/test_agentteams_eval.py
git commit -m "feat(eval): run cases through task api"
```

### 任务 3：加入核心用例和使用文档

**文件：**

- 创建：`eval/cases/core.yaml`
- 创建：`eval/README.md`
- 修改：`scripts/test_agentteams_eval.py`

- [x] **步骤 1：添加用例格式测试**

测试 `core.yaml` 包含以下 3 个 case，且每个 case 都有任务类型、Prompt 和至少一条硬断言：

```text
mcp-web-search-title
no-tool-simple-answer
artifact-integrity
```

- [x] **步骤 2：添加不含凭证的 YAML 用例**

用例只包含 `tenant`、`project`、`team`、Prompt、断言和限制，不写入 Token、API Key、Cookie、MinIO 凭证或 Keycloak 密码。

- [x] **步骤 3：编写使用文档**

`eval/README.md` 必须包含：

```bash
export AGENTTEAMS_EVAL_TOKEN='短期 Bearer Token'
python3 scripts/agentteams_eval.py \
  --base-url http://127.0.0.1:18081 \
  --token-env AGENTTEAMS_EVAL_TOKEN \
  --cases eval/cases/core.yaml \
  --report /tmp/agentteams-evaluation.json
```

文档明确说明：Token 只能通过环境变量提供，报告可以提交但不能包含 Token；L5 运行需要先建立 Control Plane port-forward。

- [x] **步骤 4：运行本地完整验证**

运行：

```bash
python3 -m unittest scripts/test_agentteams_eval.py -v
python3 -m py_compile scripts/agentteams_eval.py scripts/test_agentteams_eval.py
python3 scripts/agentteams_eval.py --help
```

预期：用例测试、编译检查和 CLI 帮助全部通过。

- [x] **步骤 5：提交**

```bash
git add eval scripts/test_agentteams_eval.py
git commit -m "feat(eval): add core evaluation cases and usage guide"
```

### 任务 4：完成 P0 验收和回归记录

**文件：**

- 修改：`eval/README.md`
- 创建：`eval/reports/.gitkeep`

- [ ] **步骤 1：在 L5 执行核心套件**

使用短期 Token 执行 3 个 case，确认任务真实经过 Worker 和 MCP。报告只保留任务 ID、运行 ID、断言、分数和失败原因。

- [ ] **步骤 2：核对平台证据**

逐个确认：

- `mcp-web-search-title` 有 `tool.called` 和 `tool.finished`。
- `no-tool-simple-answer` 没有工具调用。
- `artifact-integrity` 的制品有 `tasks/` storageRef，下载内容 SHA-256 匹配。

- [x] **步骤 3：运行项目现有 Python 合约测试**

运行：

```bash
python3 -m unittest scripts/test_agentteams_eval.py
python3 scripts/validate-kind-manifests.py
```

预期：评测测试和现有清单校验均通过。

- [ ] **步骤 4：提交验收记录**

```bash
git add eval
git commit -m "test(eval): record p0 evaluation acceptance"
```
