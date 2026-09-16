# 旧平台基础配置迁移映射（2026-09-16）

- 来源：OpenAPI 全量导出（output/platform-export-*/detail，gitignore）
- 敏感性：本报告不含 mcp_server_config / soul / agents 正文；草案明细在 output/migration-map-*/detail

## MCP 注册草案（mcp_servers 映射）

| 名称 | 环境标记 | transport | endpoint | credential_ref | 待复核 |
|---|---|---|---|---|---|
| procurement-mcp | prod-candidate | SSE | https://business-oppotunity-imp-api-beijing-vpc.wanlianyida.com/om/supplychain/sse | - | 否 |
| query-user-feedback-for-test-01 | test | SSE | http://corp-map-dev-api-beijing.10000da.vip:80/corp-agent-app/feedback/sse | - | 否 |
| common-mcp-dev | dev | SSE | http://corp-map-dev-api-beijing.10000da.vip:80/corp-agent-app/common/sse | - | 否 |
| strategy-server-dev | dev | SSE | http://corp-map-dev-api-beijing.10000da.vip:80/corp-agent-app/strategy/sse | - | 否 |
| supplier-recommender-dev | dev | SSE | http://corp-map-dev-api-beijing.10000da.vip:80/corp-agent-app/procurement/sse | - | 否 |
| risk-mcp-dev | dev | SSE | http://corp-map-dev-api-beijing.10000da.vip:80/corp-agent-app/risk/sse | - | 否 |
| corp-supplier-recommender | prod-candidate | SSE | http://corp-map-dev-api-beijing.10000da.vip:80/corp-agent-app/procurement/sse | - | 否 |
| sales-mcp-dev | dev | SSE | http://corp-map-dev-api-beijing.10000da.vip:80/corp-agent-app/sales/sse | - | 否 |
| customer-discovery | prod-candidate | STREAMABLE_HTTP | https://imp-api-beijing-vpc.wanlianyida.com/bohub/mcp/customer-discovery | - | 否 |
| data-artifact-dev | dev | SSE | http://corp-map-dev-api-beijing.10000da.vip:80/corp-agent-app/data-artifact/sse | legacy-data-artifact-dev | 否 |
| supplier-recommender | prod-candidate | SSE | https://business-oppotunity-imp-api-beijing-vpc.wanlianyida.com/om/supplychain/sse | - | 否 |
| memory-mcp-dev | dev | SSE | http://corp-map-dev-api-beijing.10000da.vip:80/corp-agent-app/memory/sse | - | 否 |
| feedback-mcp-dev | dev | SSE | http://corp-map-dev-api-beijing.10000da.vip:80/corp-agent-app/feedback/sse | - | 否 |
| order-mcp-dev | dev | SSE | http://corp-map-dev-api-beijing.10000da.vip:80/corp-agent-app/order/sse | - | 否 |
| financial-ask | prod-candidate | STREAMABLE_HTTP | https://dashscope.aliyuncs.com/api/v1/mcps/market-cmapi00073529/mcp | legacy-financial-ask | 否 |
| web-search | prod-candidate | STREAMABLE_HTTP | https://dashscope.aliyuncs.com/api/v1/mcps/WebSearch/mcp | legacy-web-search | 否 |
| memory-mcp10010-uat | uat | SSE | https://imp-api-beijing.wanlianyida.com/corp-agent-app/memory/sse | - | 否 |
| feedback-mcp10004-uat | uat | SSE | https://imp-api-beijing.wanlianyida.com/corp-agent-app/feedback/sse | - | 否 |
| order-mcp-10002-uat | uat | SSE | https://imp-api-beijing.wanlianyida.com/corp-agent-app/order/sse | - | 否 |
| feedback-mcp10004-v2 | variant | SSE | https://wlyd-hw-base-api.10000da.vip/corp-agent-app/feedback/sse | - | 否 |
| order-mcp-10002-v2 | variant | SSE | https://wlyd-hw-base-api.10000da.vip/corp-agent-app/order/sse | - | 否 |
| memory-mcp10010-v1 | variant | SSE | https://wlyd-hw-base-api.10000da.vip/corp-agent-app/memory/sse | - | 否 |

环境标记统计：{'prod-candidate': 6, 'test': 1, 'dev': 9, 'uat': 3, 'variant': 3}

## Skill 注册草案（skills/skill_versions 映射）

| 名称 | 版本 | 来源 | 活跃绑定 |
|---|---|---|---|
| corp_skill_bidding_search | - | worker-binding | 是 |
| corp_skill_supply_recommend | - | worker-binding | 是 |
| customer-discovery | - | worker-binding | 是 |
| data-artifact-delivery | - | worker-binding | 是 |
| industry-research | - | worker-binding | 是 |
| markdown-to-pdf | - | worker-binding | 是 |
| risk-mcp-operations | - | worker-binding | 是 |
| skill-query-user-feedback-for-test-01 | - | worker-binding | 是 |
| skill_supply_discover_supplier | - | worker-binding | 是 |
| skill_supply_recommend | - | worker-binding | 是 |
| strategy-policy-match | - | worker-binding | 是 |
| teamharness-channel | - | worker-binding | 是 |
| teamharness-communication | - | worker-binding | 是 |
| teamharness-file-sharing | - | worker-binding | 是 |
| teamharness-find-skills | - | worker-binding | 是 |
| teamharness-mcporter | - | worker-binding | 是 |
| teamharness-subagent | - | worker-binding | 是 |
| teamharness-task-execution | - | worker-binding | 是 |
| teamharness-task-management | - | worker-binding | 是 |
| teamharness-task-planning | - | worker-binding | 是 |
| render-company-list | 2.2.0 | onboard-yaml | 否 |
| render-customer-insight-report | 2.0.0 | onboard-yaml | 否 |
| resolve-company-identity | 2.0.0 | onboard-yaml | 否 |

## Agent 模板反推（4 个在用）

- `template-bidding-worker@0.0.2`（引用 1 个 worker：bidding-worker）—— soul 1873 字/agents 903 字（指纹 932e7104/add26b80），模型 {'model_name': 'qwen3.8-max', 'model_provider': 'default'}，绑定 MCP 1、skill 1
- `template-risk-management@0.0.2`（引用 1 个 worker：fdfdfd）—— soul 2957 字/agents 6308 字（指纹 7bd6191e/c71fd2a9），模型 {'model_name': 'hunyuan-pro', 'model_provider': 'my-hunyuan-provider'}，绑定 MCP 0、skill 1
- `template-supply-chain-worker@0.0.5`（引用 2 个 worker：supplierdev, supply-chain-worker）—— soul 643 字/agents 293 字（指纹 b1f12fc1/fd1e3c18），模型 {'model_name': 'qwen3.7-max', 'model_provider': 'default'}，绑定 MCP 1、skill 2
- `strategic-expert@0.0.9`（引用 1 个 worker：strategic-expert）—— soul 2436 字/agents 4752 字（指纹 6a5507e7/6df8b596），模型 {'model_name': 'qwen3.7-max', 'model_provider': 'default'}，绑定 MCP 3、skill 3

## Worker → AgentSpec 草案：29 个（明细见 output/migration-map-*/detail）

- workerType：LEADER 6 / EXECUTOR 23；desiredState RUNNING 29 / STOPPED 0
- spec 体积：max 25380 bytes（限 64KB，超限 0 个）

## Model Provider / Model 注册草案

| Provider | 类型 | endpoint | credential_ref |
|---|---|---|---|
| ds-laster | openai-compatible | https://dashscope.aliyuncs.com/compatible-mode/v1 | legacy-modelprov-ds-laster |
| my-hunyuan-provider | openai-compatible | https://hunyuan.tencentcloudapi.com | legacy-modelprov-my-hunyuan-provider |
| default | openai-compatible | https://dashscope.aliyuncs.com/compatible-mode/v1 | legacy-modelprov-default |

| Model | Provider | 来源 | worker 引用 |
|---|---|---|---|
| qwen3.7-max | default | model-catalog | 20 |
| qwen3.8-max | default | model-catalog | 5 |
| deepseek-v4-flash | default | model-catalog | 3 |
| deepseek-v4-pro | default | model-catalog | 0 |
| deepseek-v4-flash-0731 | ds-laster | model-catalog | 0 |
| deepseek-v4-pro-0813 | ds-laster | model-catalog | 0 |
| hunyuan-pro | my-hunyuan-provider | model-catalog | 1 |

## 待办决策

- [ ] MCP 22 个中哪些生产迁移（环境标记已打：uat/variant/test 默认不迁）
- [ ] transport 兼容性复核（非 SSE protocol 标记 STREAMABLE_HTTP 待核）
- [ ] 模板实例反推 vs 控制台模板中心定义（若有出入以控制台为准）
- [ ] model provider 凭据值（api_keys 导出已脱敏，需控制台重取后入 credential store）
- [ ] AgentSpec 发布（publish）时机：需 MCP/skill 引用在新平台可见后执行，当前保持 DRAFT
