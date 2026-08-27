# 批次 C 配额跨实例压力验证实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `subagent-driven-development`（推荐）或 `executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 增加一个不泄露凭据、可复现且可用于 CI/预发布的配额并发压测工具，验证 reservation 并发上限、幂等、超时和释放无泄漏。

**架构：** 脚本通过现有 Gateway Quota gRPC 接口执行 acquire/release；每个请求只使用合成的 tenant/project 和随机种子生成的幂等键。脚本只输出固定字段的 JSON 汇总，失败时不输出响应正文或 scope claim。

**技术栈：** Python 3、标准库 `argparse`/`concurrent.futures`/`json`/`subprocess`，现有 `grpcurl` 和 `quota.proto`。

---

## 文件清单与职责

- 创建：`scripts/run-quota-load.py` —— 参数校验、并发 acquire/release、结果聚合和安全 JSON 输出。
- 创建：`scripts/test_quota_load_contract.py` —— 静态安全契约、参数契约、聚合逻辑和响应脱敏测试。
- 修改：`.github/workflows/ci.yml` —— 在 Kind recovery 的配额基础验收后增加可选短压测步骤，默认使用 4 客户端/200 请求。
- 修改：`docs/superpowers/specs/2026-08-26-observability-scale-closure-design.md` —— 记录仓库侧压力工具完成和 L4/L6 边界。

## 任务 1：配额压力工具

- [x] **步骤 1：编写失败契约测试**

覆盖正整数参数、`projects <= concurrency`、固定 JSON 字段、成功/拒绝/超时/重复计数、最大观察并发和未释放 reservation；测试输入响应正文包含 token 时，输出不得包含该 token。

- [x] **步骤 2：运行测试确认失败**

运行：`python3 -m unittest scripts/test_quota_load_contract.py`。

预期：因 `run-quota-load.py` 不存在而失败。

- [x] **步骤 3：实现最小脚本**

实现 `run-quota-load.py`：

1. 参数固定包含 `--gateway-address`、`--grpcurl`、`--concurrency`、`--requests`、`--projects`、`--timeout`、`--seed`、`--tenant`、`--max-concurrent` 和 `--estimated-tokens`；
2. 用 `random.Random(seed)` 选择合成 project，使用进程唯一 run id 生成 acquire/release key；
3. 并发执行 acquire，成功后在同一线程 finally release；release 失败记入 `unreleased_reservations`；accepted 但缺少 reservation ID 或重复 acquire 返回不同 reservation 时以稳定失败退出；
4. 用 grpcurl JSON 调用现有 `Acquire`/`Release` RPC，只解析 `accepted`、`reservationId` 和稳定 protocol/rejection 字段；
5. 可选地通过 Control Plane URL 创建本次唯一 quota policy，并在压测后校验每个 project 的 current concurrency、daily calls 和 daily tokens；
6. 输出 `success`、`rejected`、`timeout`、`duplicate`、`max_observed_concurrency`、`unreleased_reservations`、`requests`、`projects`、`seed`，不输出请求/响应正文。

- [x] **步骤 4：运行契约测试确认通过**

运行：`python3 -m unittest scripts/test_quota_load_contract.py`。

预期：所有契约测试通过，输出仅包含稳定 JSON 字段。

- [x] **步骤 5：接入 CI 短压测**

在 `.github/workflows/ci.yml` 的 quota recovery 后调用脚本；只在存在 `grpcurl`、Gateway 地址和 quota proto 时运行，失败输出固定分类并由现有 diagnostics 收集数据库摘要。CI 不注入真实凭据。

- [x] **步骤 6：本地 Docker/Colima 与全量验证**

运行：`python3 -m unittest discover -s scripts -p 'test_*.py'`、`source deploy/dev-env.sh && mvn -q -Pintegration-tests verify`、`helm lint deploy/helm/agentteams-java`、`git diff --check`。

- [x] **步骤 7：Commit 并推送主线**

```bash
git add scripts/run-quota-load.py scripts/test_quota_load_contract.py .github/workflows/ci.yml docs/superpowers/specs/2026-08-26-observability-scale-closure-design.md docs/superpowers/plans/2026-08-28-batch-c-quota-load-plan.md
git commit -m "feat(配额): 增加跨实例并发压力验证"
git push origin main
```

## 完成边界

此计划完成代表压力工具和 CI 短压测契约完成，不代表已经完成预发布的 50 客户端/30 分钟长压测。长压测、跨副本重启和生产 SLO 仍需受控环境真实报告。
