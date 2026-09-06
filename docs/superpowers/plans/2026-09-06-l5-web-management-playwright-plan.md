# L5 Web 管理端兼容性修复与 Playwright 验收计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `subagent-driven-development` 或 `executing-plans` 逐任务实现此计划。步骤使用复选框跟踪进度。

**目标：** 修复 L5 HTTP 浏览器中 `crypto.randomUUID is not a function` 导致的项目创建失败，并通过真实 Web 管理端 Playwright 验证项目、组织、人员、Worker、Team、模板和项目部署流程。

**架构：** 在 Console 内增加统一 UUID 生成适配层：优先使用 `crypto.randomUUID`，否则使用 `crypto.getRandomValues` 生成 RFC 4122 v4 UUID；所有幂等键和对话本地标识统一调用该适配层。Playwright 以 L5 NodePort 为 Base URL，通过 Keycloak 真实 OIDC 登录，使用唯一后缀隔离测试数据，并保存失败 Trace/截图。

**技术栈：** React、TypeScript、Vitest、Playwright、Chromium、Keycloak OIDC、L5 K3s。

---

### 任务 1：建立 UUID 兼容性回归测试

**文件：**
- 创建：`console/src/utils/uuid.ts`
- 创建：`console/tests/utils/uuid.test.ts`
- 修改：`console/tests/api/httpClient.test.ts`

- [ ] **步骤 1：编写失败测试**

测试覆盖两种行为：原生 `randomUUID` 可用时直接使用；`randomUUID` 不存在但 `getRandomValues` 可用时仍返回合法 v4 UUID。

```ts
it('falls back to getRandomValues when randomUUID is unavailable', () => {
  vi.stubGlobal('crypto', {
    getRandomValues(bytes: Uint8Array) {
      bytes.fill(0);
      return bytes;
    },
  });
  expect(createUuid()).toBe('00000000-0000-4000-8000-000000000000');
});
```

- [ ] **步骤 2：运行测试确认失败**

运行：`npm --prefix console exec vitest run tests/utils/uuid.test.ts`

预期：FAIL，原因是 `console/src/utils/uuid.ts` 尚不存在。

- [ ] **步骤 3：实现最小 UUID 适配器**

实现 `createUuid()`：先检测 `globalThis.crypto.randomUUID`，再用 `getRandomValues` 填充 16 字节并设置版本位 `0x40`、变体位 `0x80`，最后按 `8-4-4-4-12` 格式输出。

- [ ] **步骤 4：运行测试确认通过**

运行：`npm --prefix console exec vitest run tests/utils/uuid.test.ts tests/api/httpClient.test.ts`

预期：UUID 及 HTTP 幂等键相关测试全部通过。

### 任务 2：替换 Console 内所有直接 UUID 调用

**文件：**
- 修改：`console/src/api/httpClient.ts`
- 修改：`console/src/api/conversations.ts`
- 修改：`console/src/api/scheduledTasks.ts`
- 修改：`console/src/features/conversations/ConversationPage.tsx`
- 测试：`console/tests/api/httpClient.test.ts`、`console/tests/api/conversations.test.ts`

- [ ] **步骤 1：替换调用点**

将所有 `crypto.randomUUID()` 替换为 `createUuid()`，覆盖写请求自动幂等键、会话创建、消息发送、取消、排程操作、对话本地消息和会话 ID。

- [ ] **步骤 2：静态检查调用点**

运行：`rg -n "crypto\\.randomUUID" console/src`

预期：无结果。

- [ ] **步骤 3：运行 Console 回归**

运行：`npm --prefix console test && npm --prefix console run build && npm --prefix console run lint`

预期：Vitest 全部通过，TypeScript/Vite 构建成功，ESLint 无错误。

### 任务 3：L5 Playwright 登录与浏览器兼容性基线

**文件：**
- 修改：`console/playwright.config.ts`（仅在需要时增加 trace/video 配置）
- 修改：`console/tests/e2e/smoke.spec.ts`（增加 HTTP/缺失 randomUUID 回归场景）

- [ ] **步骤 1：准备 L5 环境参数**

```bash
export AGENTTEAMS_E2E_BASE_URL=http://192.168.1.16:30080
export AGENTTEAMS_E2E_OIDC_PORT=30082
export AGENTTEAMS_E2E_USERNAME=alice
export AGENTTEAMS_E2E_PASSWORD=alice-dev
export AGENTTEAMS_E2E_QUOTA_ADMIN_USERNAME=quota-admin
export AGENTTEAMS_E2E_QUOTA_ADMIN_PASSWORD=quota-admin-dev
```

不把凭据写入仓库、测试报告或截图。

- [ ] **步骤 2：验证登录链路**

运行 Playwright 登录用例，断言 Console 进入 `/<projectId>/overview`、Control Plane 已连接、OIDC 地址为 `192.168.1.16:30082`，并收集 `pageerror` 与 `console.error`。

- [ ] **步骤 3：验证降级浏览器**

在页面初始化脚本中使 `Crypto.prototype.randomUUID` 不可用，保留 `getRandomValues`，执行创建项目或其他 POST 操作，断言不出现 `crypto.randomUUID is not a function`。

### 任务 4：项目、组织和人员管理

**文件：**
- 修改：`console/tests/e2e/smoke.spec.ts` 或新增 `console/tests/e2e/l5-management.spec.ts`

- [ ] **步骤 1：项目管理**

登录 Alice，进入“项目管理”，创建唯一名称 Project，刷新后仍可见；验证当前 Tenant 作用域和重复名称/非法输入提示。

- [ ] **步骤 2：组织管理**

使用 Quota Admin 创建唯一 Organization 和 Tenant，刷新后验证状态、版本和层级关系；重复提交同一幂等键时断言不产生重复资源。

- [ ] **步骤 3：人员管理**

创建内部用户、绑定 Project Membership 和角色，验证启用/停用状态、角色矩阵和列表刷新；用 Reader 账号验证无权访问受保护资源。

### 任务 5：Team、Worker、模板和项目部署

**文件：**
- 修改：`console/tests/e2e/smoke.spec.ts` 或新增 `console/tests/e2e/l5-management.spec.ts`

- [ ] **步骤 1：Team 管理**

创建 Team，选择 Leader/成员，验证详情页、版本发布和中文状态；断言发布不会自动创建 Worker。

- [ ] **步骤 2：Worker 管理**

查看 Worker 列表和详情、状态、操作记录；仅执行可回滚的操作，断言操作结果和版本冲突提示。

- [ ] **步骤 3：模板管理**

创建 Worker Template，创建并发布 Revision，验证默认状态和发布提示；显式实例化 Worker 后等待 Ready，进入 Worker 详情确认来源和操作记录。

- [ ] **步骤 4：项目部署**

创建并发布 AgentSpec，验证停用生命周期；结合已发布模板执行显式部署，检查 Worker Ready、Project/Team scope 和失败时的错误分类。

### 任务 6：负向场景、清理与报告

**文件：**
- 创建或修改：`console/tests/e2e/l5-management.spec.ts`
- 创建：`artifacts/l5-web-playwright-<date>/`（仅测试产物，不提交凭据）

- [ ] **步骤 1：负向验证**

验证跨 Project UUID、无权限用户、过期/缺失授权、重复幂等键、旧 `expectedVersion`、空字段和非法 JSON 均得到预期中文错误。

- [ ] **步骤 2：资源清理**

删除或停用测试创建的可清理资源；不能删除的资源记录 ID、状态和清理原因，不删除已有业务数据。

- [ ] **步骤 3：执行完整 Playwright 套件**

运行：`AGENTTEAMS_E2E_BASE_URL=http://192.168.1.16:30080 AGENTTEAMS_E2E_OIDC_PORT=30082 npm --prefix console run e2e -- --project=chromium`

预期：目标用例全部通过；失败时保留 `test-results` 中的截图、Trace 和页面错误摘要。

- [ ] **步骤 4：提交与发布记录**

```bash
git diff --check
git add console/src console/tests deploy docs
git commit -m "fix(console): 兼容非安全上下文 UUID 生成"
```

只提交本次修复、测试和方案文件，不提交既有工作区修改、凭据、Token 或浏览器缓存。
