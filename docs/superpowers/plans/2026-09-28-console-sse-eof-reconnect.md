# Console SSE 正常 EOF 重连提示修复实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让 Console 将 SSE 回放流的正常 EOF 视为成功轮询，不再错误显示“事件流已断开，正在重连”；真正的网络或 HTTP 异常仍显示重连状态。

**架构：** Control Plane 当前返回的是有限的事件回放流，发送完当前事件后正常关闭连接。Console 的任务事件和过程事件 Hook 保留 EOF 后的后台轮询，但仅在异常路径设置 `reconnecting` 状态。

**技术栈：** React Hooks、TypeScript、Vitest、Testing Library。

---

### 任务 1：覆盖正常 EOF 与异常断开的差异

**文件：**
- 修改：`console/tests/features/TaskPages.test.tsx`
- 参考：`console/src/queries/useTaskQueries.ts`

- [x] **步骤 1：编写失败的回归测试**

在任务页测试中增加一个用例：模拟 `streamTaskEvents` 成功发送一条事件后正常返回，等待一次重连调度后断言页面不显示“事件流已断开，正在重连”。保留现有异常拒绝测试，确保真正异常仍显示重连提示。

- [x] **步骤 2：运行测试验证失败**

运行：`cd console && npm test -- --run tests/features/TaskPages.test.tsx`

预期：新增测试失败，当前实现把正常返回后的 `scheduleReconnect()` 也标记为 `reconnecting`。

### 任务 2：修复两个事件 Hook 的 EOF 状态处理

**文件：**
- 修改：`console/src/queries/useTaskQueries.ts`

- [x] **步骤 1：实现最小修复**

将两个 Hook 的重连调度区分为两条路径：

```ts
const scheduleReconnect = (showReconnectState: boolean) => {
  if (!active) return;
  const delay = taskEventReconnectDelay(retryAttempt);
  retryAttempt += 1;
  if (showReconnectState) {
    setState((current) => ({ ...current, connectionState: 'reconnecting' }));
  }
  reconnectTimer = setTimeout(connect, delay);
};
```

正常 `streamTaskEvents` / `streamTaskProcessEvents` 返回后调用 `scheduleReconnect(false)`；捕获异常后调用 `scheduleReconnect(true)`。正常 EOF 仍按当前逻辑保留已收到事件和游标，后台继续回放新事件。

- [x] **步骤 2：运行回归测试**

运行：`cd console && npm test -- --run tests/features/TaskPages.test.tsx`

预期：任务页测试全部通过，正常 EOF 不出现断线提示，异常拒绝仍出现断线提示。

### 任务 3：完成 Console 验证

**文件：**
- 无新增生产文件。

- [x] **步骤 1：运行 API 流解析测试**

运行：`cd console && npm test -- --run tests/api/taskEvents.test.ts`

预期：SSE 解析、游标和退避时间测试全部通过。

- [x] **步骤 2：运行完整 Console 测试、构建和格式检查**

运行：

```bash
cd console
npm test
npm run build
npm run format:check
```

预期：测试和 TypeScript/Vite 构建成功；如果仓库其他文件已有 Prettier 告警，不修改无关文件，并另行对 `src/queries/useTaskQueries.ts` 与 `tests/features/TaskPages.test.tsx` 执行定向格式检查。

- [x] **步骤 3：检查变更范围并提交**

运行：`git diff --check && git status --short`

确认只包含本次计划涉及的 Console Hook、回归测试和计划文档，再提交：

```bash
git add console/src/queries/useTaskQueries.ts console/tests/features/TaskPages.test.tsx docs/superpowers/plans/2026-09-28-console-sse-eof-reconnect.md
git commit -m "fix(Console): 区分 SSE 正常结束与断线"
```
