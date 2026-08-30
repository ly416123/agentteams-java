# 首页资源聚合接入实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `superpowers:subagent-driven-development` 或 `superpowers:executing-plans` 逐任务实现此计划。本次按当前会话内联执行，并保留 TDD 红绿验证记录。

**目标：** 接入项目级资源聚合接口，让首页资源卡展示真实任务、Worker 与 Team 统计，同时保持模型用量和资源错误提示行为。

**架构：** 在 `console/src/api/types.ts` 增加与后端契约一致的资源聚合类型，在 `console/src/api/overview.ts` 增加带 `projectId` 的调用。`getOverview` 并行请求资源聚合、dashboard summary、告警和首页辅助列表；资源卡只使用聚合响应，分页列表只用于最近任务，不参与全局统计。

**技术栈：** React、React Query、TypeScript、Vitest、Testing Library。

---

### 任务 1：补充资源聚合 API 契约测试

**文件：**
- 修改：`console/tests/api/contracts.test.ts`
- 修改：`console/src/api/overview.ts`

- [x] **步骤 1：编写失败的测试**

在 API contracts 测试中增加 `getDashboardResources('p-1', client)`，断言调用：

```ts
expect(client.request).toHaveBeenCalledWith('/api/v1/dashboard/resources', {
  query: { projectId: 'p-1' },
});
```

- [x] **步骤 2：运行测试验证失败**

运行：`npm --prefix console test -- tests/api/contracts.test.ts`

预期：FAIL，`getDashboardResources` 尚未导出。

- [x] **步骤 3：编写最少实现代码**

增加 `getDashboardResources`，请求 `/api/v1/dashboard/resources` 并传递 `{ projectId }`。

- [x] **步骤 4：运行测试验证通过**

运行：`npm --prefix console test -- tests/api/contracts.test.ts`

预期：PASS。

### 任务 2：补充 Overview 聚合数据与失败隔离测试

**文件：**
- 修改：`console/tests/features/OverviewPage.test.tsx`
- 修改：`console/src/api/types.ts`
- 修改：`console/src/api/overview.ts`

- [x] **步骤 1：编写失败的测试**

扩展 Overview API 测试，模拟资源接口响应并断言 `getOverview` 返回真实的 `tasks.total/succeeded`、`workers.ready`、`teams.active`，且列表页返回的首屏数据不会覆盖这些值；同时增加资源聚合拒绝时的资源错误字段和 `metricsUnavailable` 断言。页面测试断言四张卡展示 `24`、`15`、`5`、`2`，并断言资源聚合失败时四张卡保留「后端尚未提供聚合统计」和错误展示。

- [x] **步骤 2：运行测试验证失败**

运行：`npm --prefix console test -- tests/features/OverviewPage.test.tsx`

预期：FAIL，当前 `getOverview` 没有请求资源聚合接口，资源字段仍为 `null`，且页面 mock 未覆盖真实资源详情。

- [x] **步骤 3：编写最少实现代码**

增加 `DashboardResources` 类型及 `Overview.errors.resources`；让 `getOverview` 将资源请求放入 `Promise.allSettled`，成功时映射资源字段，失败时记录错误并将资源指标标记为不可用。页面四张卡继续显示资源详情，资源错误挂在各资源卡对应位置；summary 仍单独提供 `usage`。

- [x] **步骤 4：运行测试验证通过**

运行：`npm --prefix console test -- tests/api/contracts.test.ts tests/features/OverviewPage.test.tsx`

预期：PASS。

### 任务 3：完成全量验证并检查变更边界

**文件：**
- 检查：`console/src/api/overview.ts`
- 检查：`console/src/api/types.ts`
- 检查：`console/src/features/overview/OverviewPage.tsx`
- 检查：`console/tests/api/contracts.test.ts`
- 检查：`console/tests/features/OverviewPage.test.tsx`

- [x] **步骤 1：运行前端测试、类型构建、Lint 与格式检查**

运行：

```bash
npm --prefix console test
npm --prefix console run build
npm --prefix console run lint
npm --prefix console run format:check
```

- [x] **步骤 2：核对禁止修改范围**

运行：`git diff --name-only --check`，确认变更文件均在 console、测试或本计划文件中，不包含 `control-plane`。

- [x] **步骤 3：提交**

```bash
git add console/src/api/types.ts console/src/api/overview.ts console/src/features/overview/OverviewPage.tsx console/tests/api/contracts.test.ts console/tests/features/OverviewPage.test.tsx docs/superpowers/plans/2026-08-30-dashboard-resource-aggregation.md
git commit -m "feat(概览): 接入首页资源聚合统计"
```
