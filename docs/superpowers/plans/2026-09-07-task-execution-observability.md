# 任务执行可观测与人工干预实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `subagent-driven-development` 或 `executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在管理后台实现任务执行过程、结果、成果物和人工干预的可视化闭环。

**架构：** 复用控制面已经提供的过程事件、进度、任务树、决策和结果投影接口，在 Console 增加类型安全的 API 客户端、断点续传 SSE Hook 和可组合的执行观测组件。任务状态转换仍由控制面负责，前端只发起带版本号的既有操作。

**技术栈：** React、TypeScript、TanStack Query、浏览器 Fetch Streams、SSE、Vitest、Testing Library、Playwright、Spring Boot 现有控制面接口。

---

### 任务 1：扩展任务执行观测 API 客户端

**文件：**
- 修改：`console/src/api/tasks.ts`
- 修改：`console/src/api/taskEvents.ts`
- 修改：`console/src/api/types.ts`
- 修改：`console/src/queries/useTaskQueries.ts`
- 测试：`console/tests/api/taskEvents.test.ts`

- [x] 编写过程事件、进度、任务树、决策、结果的失败测试。
- [x] 运行测试确认失败原因是缺少类型/API。
- [x] 实现 API 类型、请求函数和过程事件 SSE 解析/重连。
- [x] 运行 API 测试确认通过。

### 任务 2：实现执行观测组件

**文件：**
- 创建：`console/src/features/tasks/TaskExecutionObservability.tsx`
- 修改：`console/src/features/tasks/TaskDetailPage.tsx`
- 修改：`console/src/i18n/labels.ts`
- 测试：`console/tests/features/TaskPages.test.tsx`

- [x] 编写任务详情中进度、过程事件、任务树、决策和成果物展示的失败测试。
- [x] 实现局部加载/错误/空状态及中文标签。
- [x] 将组件接入任务详情页，并根据任务阶段显示暂停/恢复、批准、拒绝、取消、重试。
- [x] 运行页面测试确认通过。

### 任务 3：增强端到端验证

**文件：**
- 修改：`console/tests/e2e/smoke.spec.ts`

- [x] 增加任务执行观察断言：进度、过程事件、结果 Manifest、成果物。
- [x] 增加可干预断言：任务详情展示批准、取消等操作入口。
- [x] 运行 Console lint/build 和 Playwright 用例静态检查。

### 任务 4：收尾验证

**文件：**
- 复核：上述实现文件和测试文件

- [x] 运行控制面相关 Maven 测试。
- [x] 运行 Console 测试、lint、build。
- [x] 运行 `git diff --check`，确认无敏感信息和无关破坏性修改。
- [x] 汇总 L5 环境限制和未完成的端到端项。
