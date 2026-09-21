# AgentTeams AI Agent 评测 P2 实现计划

## 目标

在现有 AgentTeams Runner 和确定性 CI 门禁之上，增加可选的 DeepEval 语义评测适配层。适配层只消费已经采集的 observation，不修改 Control Plane、Gateway、Worker 或 MCP 运行时。

## 边界

- Runner 继续负责任务执行、事件归一化、工具选择和制品完整性等硬断言。
- DeepEval 只负责开放式答案、相关性、忠实性和工具轨迹的语义评分。
- DeepEval 不作为默认生产依赖，不进入离线 CI，不在仓库中保存模型密钥。
- 导出数据只包含输入、输出、工具名和可选的耗时、Token 数；制品下载 URL 和常见凭证字段必须脱敏或排除。

## 任务清单

- [x] 新增 `eval/deepeval_adapter.py`，提供纯 JSON 归一化和可选 `LLMTestCase` 构造。
- [x] 支持 `ToolCall`、`expected_tools`、`token_cost` 和 `completion_time` 字段。
- [x] 新增命令行导出入口，读取指定用例和 observation JSON。
- [x] Runner 增加可选 `--deepeval-export-dir`，直接写出已脱敏的语义评测输入。
- [x] 新增无 DeepEval 依赖的契约测试和 CI 编译检查。
- [x] 更新评测 README，记录隔离安装、密钥边界和运行方式。
- [ ] 用脱敏的真实 AgentTeams 样本校准 Judge Rubric 和阈值。
- [ ] 在独立评测环境中选择模型、运行少量人工抽检，并记录 Judge 与人工结论的一致性。

## 验收标准

1. 未安装 DeepEval 时，现有 CI 契约测试和 Runner 仍能通过。
2. 适配器导出的 JSON 不含 Bearer、API Key、密码、Token 或预签名 URL。
3. 安装 DeepEval 后，可以通过 `build_deepeval_test_case` 生成 `LLMTestCase`，并由调用方显式传入指标和模型配置。
4. 语义评分不能覆盖确定性硬断言的失败结果。
