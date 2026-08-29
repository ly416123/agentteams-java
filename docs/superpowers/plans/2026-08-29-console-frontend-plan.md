# AgentTeams 管理端 SPA 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 创建参考阿里云 TeamAgent 信息架构的独立 Console，完成登录、Project、Team、Task、Worker 管理和产品化状态交互。

**架构：** `console/` 是 React SPA，通过 `/api` 访问 AgentTeams API；认证使用 Keycloak OIDC PKCE，数据使用 TanStack Query，SSE 使用独立的 cursor-aware stream client。页面只渲染服务端状态，不在浏览器复制权限或 Task 状态机。

**技术栈：** React、TypeScript、Vite、Ant Design、TanStack Query、React Router、`oidc-client-ts`、Vitest、Testing Library、Playwright、ESLint、Prettier。

---

## 文件清单

- 创建：`console/package.json`、`console/tsconfig.json`、`console/vite.config.ts`、`console/index.html`。
- 创建：`console/src/main.tsx`、`console/src/app/router.tsx`、`console/src/app/AppShell.tsx`、`console/src/app/routes.ts`。
- 创建：`console/src/auth/oidc.ts`、`console/src/auth/AuthProvider.tsx`、`console/src/auth/RequireAuth.tsx`。
- 创建：`console/src/api/httpClient.ts`、`console/src/api/types.ts`、`console/src/api/projects.ts`、`console/src/api/teams.ts`、`console/src/api/tasks.ts`、`console/src/api/workers.ts`、`console/src/api/conversations.ts`。
- 创建：`console/src/queries/*.ts`，封装 Project、Team、Task、Worker 和 Conversation 查询/变更。
- 创建：`console/src/components/StatusBadge.tsx`、`Timeline.tsx`、`ErrorState.tsx`、`EmptyState.tsx`、`VersionConflictModal.tsx`、`ResourceTable.tsx`。
- 创建：`console/src/features/overview/*`、`teams/*`、`tasks/*`、`workers/*`。
- 创建：`console/src/styles/tokens.css`、`console/src/styles/global.css`。
- 创建：`console/tests/fixtures/*`、`console/tests/components/*`、`console/tests/e2e/*`。
- 修改：`deploy/helm/agentteams-java/values.yaml`、`deploy/helm/agentteams-java/templates/console.yaml`、`deploy/kind-ingress.yaml`、`.github/workflows/ci.yml`。

### 任务 1：搭建 Console 工程和基础质量门禁

**文件：**

- 创建：`console/package.json`、`console/tsconfig.json`、`console/vite.config.ts`、`console/index.html`
- 创建：`console/src/main.tsx`
- 创建：`console/tests/smoke.test.tsx`

- [ ] **步骤 1：编写失败测试。** 测试应用渲染产品名称和登录路由。

```tsx
import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { AppShell } from '../src/app/AppShell';

it('renders the AgentTeams console shell', () => {
  render(<MemoryRouter><AppShell /></MemoryRouter>);
  expect(screen.getByText('AgentTeams')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '登录' })).toBeInTheDocument();
});
```

- [ ] **步骤 2：运行测试验证失败。** `cd console && npm test -- --run tests/smoke.test.tsx`，预期 FAIL，原因是 Console 工程不存在。
- [ ] **步骤 3：初始化工程。** 创建 package scripts：`dev`、`build`、`test`、`lint`、`format:check`、`e2e`；启用 TypeScript strict 模式。
- [ ] **步骤 4：实现最小 App。** 创建 `main.tsx`、路由入口和全局样式，使测试只依赖公开 UI 行为。
- [ ] **步骤 5：运行测试验证通过。** `cd console && npm test -- --run tests/smoke.test.tsx`，预期 PASS。
- [ ] **步骤 6：Commit。** `git add console && git commit -m "feat(console): 创建管理端 SPA 工程"`

### 任务 2：实现 OIDC 登录和 Project 上下文

**文件：**

- 创建：`console/src/auth/oidc.ts`、`AuthProvider.tsx`、`RequireAuth.tsx`
- 创建：`console/src/features/login/LoginPage.tsx`
- 创建：`console/src/features/projects/ProjectSwitcher.tsx`
- 创建：`console/tests/auth/AuthProvider.test.tsx`、`ProjectSwitcher.test.tsx`

- [ ] **步骤 1：编写失败测试。** 覆盖未登录跳转、登录回调、Token 内存保存、Project 切换清理 Query 缓存。
- [ ] **步骤 2：运行测试验证失败。** `cd console && npm test -- --run tests/auth`，预期 FAIL。
- [ ] **步骤 3：实现 PKCE 认证。** `oidc-client-ts` 使用 Keycloak issuer、client ID、redirect URI 和 post-logout URI 配置；Access Token 只保存在内存，API Key 不进入前端。
- [ ] **步骤 4：实现 Project 上下文。** `ProjectSwitcher` 从 `GET /api/v1/projects` 加载列表；切换时调用 QueryClient 清理旧 Project 的资源缓存，并更新路由 `/:projectId/*`。
- [ ] **步骤 5：运行测试验证通过。** `cd console && npm test -- --run tests/auth`，预期 PASS。
- [ ] **步骤 6：Commit。** `git add console/src/auth console/src/features/login console/src/features/projects console/tests/auth && git commit -m "feat(console): 增加 OIDC 登录与 Project 上下文"`

### 任务 3：实现 API 客户端、布局和统一状态组件

**文件：**

- 创建：`console/src/api/httpClient.ts`、`types.ts`
- 创建：`console/src/app/AppShell.tsx`、`routes.ts`
- 创建：`console/src/components/StatusBadge.tsx`、`ErrorState.tsx`、`EmptyState.tsx`、`VersionConflictModal.tsx`
- 创建：`console/tests/api/httpClient.test.ts`、`console/tests/components/StateComponents.test.tsx`

- [ ] **步骤 1：编写失败测试。** 验证 `401` 触发登录、`403` 显示无权、`409` 保留表单、`429/503` 显示可重试状态；验证状态颜色由服务端 phase 映射表提供。
- [ ] **步骤 2：运行测试验证失败。** `cd console && npm test -- --run tests/api tests/components`，预期 FAIL。
- [ ] **步骤 3：实现 HTTP 客户端。** 每个写请求自动生成唯一 `Idempotency-Key`，生命周期操作从资源 `version` 读取 `expectedVersion`；统一解析 `{code,message,details}` 错误结构。
- [ ] **步骤 4：实现 AppShell。** 顶栏放 Project 切换器、用户菜单和退出；侧栏放概览、Tasks、Teams、Workers、对话、运行记录；移动窄屏折叠侧栏。
- [ ] **步骤 5：运行测试验证通过。** `cd console && npm test -- --run tests/api tests/components`，预期 PASS。
- [ ] **步骤 6：Commit。** `git add console/src/api console/src/app console/src/components console/tests && git commit -m "feat(console): 增加 API 客户端与控制台布局"`

### 任务 4：实现概览和 Team 管理

**文件：**

- 创建：`console/src/features/overview/OverviewPage.tsx`、`overviewQueries.ts`
- 创建：`console/src/features/teams/TeamListPage.tsx`、`TeamCreatePage.tsx`、`TeamDetailPage.tsx`、`teamQueries.ts`
- 创建：`console/tests/features/overview/OverviewPage.test.tsx`、`teams/TeamPages.test.tsx`

- [ ] **步骤 1：编写失败测试。** 覆盖统计卡片局部失败、Team 搜索、创建分步表单、成员 Agent、策略、版本和部署页签。
- [ ] **步骤 2：运行测试验证失败。** `cd console && npm test -- --run tests/features/overview tests/features/teams`，预期 FAIL。
- [ ] **步骤 3：实现 Overview。** 并行查询 Task、Worker、Team 和告警摘要；使用 skeleton、局部 ErrorState 和空状态；卡片链接到对应列表的筛选视图。
- [ ] **步骤 4：实现 Team 页面。** Team 列表支持搜索、状态筛选、分页和创建；详情使用页签展示基本信息、成员 Agent、调度策略、版本/部署和运行记录；提交时携带作用域和幂等键。
- [ ] **步骤 5：运行测试验证通过。** `cd console && npm test -- --run tests/features/overview tests/features/teams`，预期 PASS。
- [ ] **步骤 6：Commit。** `git add console/src/features/overview console/src/features/teams console/tests/features && git commit -m "feat(console): 增加概览与 Team 管理页面"`

### 任务 5：实现 Task 看板、列表和详情

**文件：**

- 创建：`console/src/features/tasks/TaskPage.tsx`、`TaskBoard.tsx`、`TaskTable.tsx`、`TaskDetailPage.tsx`、`taskQueries.ts`
- 创建：`console/tests/features/tasks/TaskPages.test.tsx`

- [ ] **步骤 1：编写失败测试。** 覆盖看板/表格切换、游标分页、状态/Team/Worker/时间筛选、状态时间线、Attempt、事件、制品和生命周期操作。
- [ ] **步骤 2：运行测试验证失败。** `cd console && npm test -- --run tests/features/tasks/TaskPages.test.tsx`，预期 FAIL。
- [ ] **步骤 3：实现 Task 查询。** Query Key 必须包含 Project、筛选条件、视图和 cursor；写操作成功后仅失效受影响 Task 与 Overview 查询。
- [ ] **步骤 4：实现看板和详情。** 看板列映射服务端 phase；详情通过事件 SSE 更新时间线，显示当前 version；高风险操作使用确认弹窗，`409` 使用 VersionConflictModal。
- [ ] **步骤 5：运行测试验证通过。** `cd console && npm test -- --run tests/features/tasks/TaskPages.test.tsx`，预期 PASS。
- [ ] **步骤 6：Commit。** `git add console/src/features/tasks console/tests/features/tasks && git commit -m "feat(console): 增加 Task 看板列表与详情"`

### 任务 6：实现 Worker 管理页面

**文件：**

- 创建：`console/src/features/workers/WorkerListPage.tsx`、`WorkerDetailPage.tsx`、`WorkerOperationPanel.tsx`、`workerQueries.ts`
- 创建：`console/tests/features/workers/WorkerPages.test.tsx`

- [ ] **步骤 1：编写失败测试。** 覆盖 Ready、连接中、异常、Draining、镜像拉取失败和 Worker 未连接时的操作禁用说明。
- [ ] **步骤 2：运行测试验证失败。** `cd console && npm test -- --run tests/features/workers/WorkerPages.test.tsx`，预期 FAIL。
- [ ] **步骤 3：实现列表和详情。** 展示 Agent ID、Runtime、能力、当前 Task、配置版本、镜像版本、最近心跳和操作记录；详情加载 Worker 操作分页数据。
- [ ] **步骤 4：实现生命周期操作。** Drain、Rollout、Terminate、Rollback 使用服务端操作 API，显示异步状态和失败分类；按钮由权限和服务端 phase 双重控制。
- [ ] **步骤 5：运行测试验证通过。** `cd console && npm test -- --run tests/features/workers/WorkerPages.test.tsx`，预期 PASS。
- [ ] **步骤 6：Commit。** `git add console/src/features/workers console/tests/features/workers && git commit -m "feat(console): 增加 Worker 管理页面"`

### 任务 7：构建、路由回退和静态部署

**文件：**

- 创建：`deploy/docker/console.Dockerfile`
- 创建：`deploy/helm/agentteams-java/templates/console.yaml`
- 修改：`deploy/helm/agentteams-java/values.yaml`、`deploy/kind-ingress.yaml`
- 修改：`.github/workflows/ci.yml`
- 创建：`scripts/validate-console-manifests.py`

- [ ] **步骤 1：编写失败契约测试。** 验证 Console Deployment、Service、Ingress、SPA history fallback 和 `/api` 路由存在。
- [ ] **步骤 2：运行测试验证失败。** `python3 scripts/validate-console-manifests.py`，预期 FAIL，原因是 Console manifest 尚不存在。
- [ ] **步骤 3：实现容器和 Helm。** 使用 Node 构建阶段生成静态资源，再由非 root Nginx 运行；Ingress 只把 `/` 指向 Console，把 `/api` 指向 Control Plane/Manager；不把 Secret 编译进静态文件。
- [ ] **步骤 4：运行测试验证通过。** `python3 scripts/validate-console-manifests.py && helm lint deploy/helm/agentteams-java`，预期 PASS。
- [ ] **步骤 5：Commit。** `git add deploy scripts/validate-console-manifests.py .github/workflows/ci.yml && git commit -m "feat(deploy): 增加 Console 静态部署"`

### 任务 8：前端全量验证

- [ ] **步骤 1：运行格式和静态检查。** `cd console && npm run lint && npm run format:check`，预期全部通过。
- [ ] **步骤 2：运行组件测试。** `cd console && npm test -- --run`，预期全部通过。
- [ ] **步骤 3：运行构建。** `cd console && npm run build`，预期生成 `console/dist`。
- [ ] **步骤 4：Commit。** `git commit --allow-empty -m "test(console): 完成管理端 SPA 验证"`
