import { expect, test } from '@playwright/test';

/**
 * L5 验收：复杂任务执行全链路。
 *
 * 覆盖：控制台登录 → team-a 多成员团队核对 → 创建带输入 JSON 的复杂任务（工作节点留空，
 * 触发后端团队任务分配）→ 多个成果物登记并直传对象存储（storageRef 非 memory://，
 * 预签名 URL 可下载）→ 任务执行信息与模型 token 消耗可见。
 *
 * 环境变量：AGENTTEAMS_E2E_USERNAME / AGENTTEAMS_E2E_PASSWORD（alice OIDC 凭据）、
 * AGENTTEAMS_E2E_OIDC_PORT（默认 30082）、AGENTTEAMS_E2E_BASE_URL（默认本机 5173）。
 */
test('real OIDC complex team task generates multiple artifacts and records token usage', async ({
  page,
  request,
}) => {
  test.setTimeout(900_000);
  const username = process.env.AGENTTEAMS_E2E_USERNAME;
  const password = process.env.AGENTTEAMS_E2E_PASSWORD;
  const oidcPort = process.env.AGENTTEAMS_E2E_OIDC_PORT || '30082';
  test.skip(!username || !password, 'set AGENTTEAMS_E2E_USERNAME and AGENTTEAMS_E2E_PASSWORD');

  let authorization = '';
  page.on('request', (request) => {
    const value = request.headers().authorization;
    if (value?.startsWith('Bearer ')) authorization = value;
  });

  await page.goto('/console');
  await expect(page).toHaveURL(/\/login$/);
  await page.getByRole('button', { name: '使用组织账号登录' }).click();
  await expect(page).toHaveURL(
    new RegExp(`:${oidcPort}/realms/agentteams/protocol/openid-connect/auth(?:/|\\?)`),
  );
  await page.locator('#username').fill(username!);
  await page.locator('#password').fill(password!);
  await page.locator('#kc-login').click();
  await expect(page).toHaveURL(/:30080\/[^/]+\/overview$/, { timeout: 30_000 });

  const projectOption = page
    .getByLabel('当前项目')
    .locator('option')
    .filter({ hasText: 'project-a' });
  await expect(projectOption).toHaveCount(1, { timeout: 10_000 });
  const projectPath = (await projectOption.getAttribute('value'))!;
  await page.getByLabel('当前项目').selectOption(projectPath);
  await expect(page).toHaveURL(new RegExp(`/${projectPath}/overview$`));

  // 1. team-a 必须已配置多角色成员（1 个负责人 + 至少 2 个执行者），这是团队任务分配的前提。
  await page.goto(`/${projectPath}/teams`);
  const teamLink = page.getByRole('link').filter({ hasText: /team-a/ }).first();
  await expect(teamLink).toBeVisible({ timeout: 10_000 });
  await teamLink.click();
  await expect(page.getByRole('heading', { name: /team-a/ })).toBeVisible();
  await page.getByRole('tab', { name: '成员 Agent' }).click();
  const teamId = new URL(page.url()).pathname.split('/').pop()!;
  await expect(page.locator('.member-row')).toHaveCount(3, { timeout: 15_000 });

  // 2. 成员行不展示角色，改由成员 API 核对角色构成，并记录全部成员 UUID 供分配断言使用。
  const origin = new URL(page.url()).origin;
  await expect.poll(() => authorization).toMatch(/^Bearer /);
  const browserGet = async (url: string) =>
    page.evaluate(async ({ target, token }) => {
      const response = await fetch(target, {
        headers: { Authorization: `Bearer ${token}` },
      });
      return { ok: response.ok, status: response.status, body: await response.text() };
    }, { target: url, token: authorization.slice('Bearer '.length) });
  const membersResponse = await browserGet(
    `${origin}/api/v1/teams/${teamId}/members?projectId=${projectPath.split('/').pop()}`,
  );
  expect(membersResponse.ok, membersResponse.body).toBeTruthy();
  const members = JSON.parse(membersResponse.body) as Array<{
    agentId: string;
    role: string;
    status: string;
  }>;
  expect(
    members.filter((member) => member.role === 'LEADER'),
    'team-a 应恰好 1 个负责人成员',
  ).toHaveLength(1);
  expect(
    members.filter((member) => member.role === 'EXECUTOR').length,
    'team-a 应至少 2 个执行者成员',
  ).toBeGreaterThanOrEqual(2);
  expect(members.every((member) => member.status === 'ACTIVE'), '全部成员应为 ACTIVE').toBeTruthy();
  const memberIds = members.map((member) => member.agentId);

  // 3. 创建复杂任务：输入 JSON 采用 artifacts 交付协议，要求模型产出两个命名交付物。
  await page.goto(`/${projectPath}/tasks/new`);
  await page.reload();
  await expect(page.getByRole('heading', { name: '创建任务' })).toBeVisible();
  const taskTeamOption = page.getByLabel('任务团队').locator('option').filter({ hasText: 'team-a' });
  await expect(taskTeamOption).toHaveCount(1, { timeout: 15_000 });
  await page.getByLabel('任务团队').selectOption((await taskTeamOption.getAttribute('value'))!);
  const taskTitle = `复杂任务 ${Date.now()}`;
  await page.getByLabel('任务标题').fill(taskTitle);
  await page
    .getByLabel('任务说明')
    .fill('验证团队任务分配、多成果物登记与模型 token 消耗记录。');
  await page.getByLabel('任务输入 JSON').fill(
    JSON.stringify({
      prompt:
        '请完成以下复杂任务：为一个"用户登录"功能设计测试方案。'
        + '完成后严格输出一个 JSON 对象（不要包含任何其它文字或代码围栏）：'
        + '{"artifacts":[{"name":"test-plan.md","content":"<测试要点，至少 5 条>"},'
        + '{"name":"test-data.json","content":"<合法的测试数据 JSON 文本>"}]}。'
        + 'artifacts 数组必须恰好包含 2 个对象，每个对象都有 name 与 content 字段。',
    }),
  );
  await page.getByLabel('任务类型').fill('qwenpaw');
  // 工作节点留空：任务仅绑定 team-a，由后端团队调度（TaskAssignmentService）分配成员执行。
  await page.getByRole('button', { name: '创建任务' }).click();
  await expect(page).toHaveURL(new RegExp(`/${projectPath}/tasks/[0-9a-f-]{36}$`), {
    timeout: 30_000,
  });
  const taskId = new URL(page.url()).pathname.split('/').pop()!;
  await expect(page.getByRole('heading', { name: taskTitle })).toBeVisible();

  // 4. 排队执行并等待真实模型调用完成。
  await page.getByRole('button', { name: '排队执行' }).click();
  await expect(page.getByText('操作已提交')).toBeVisible({ timeout: 15_000 });

  await expect
    .poll(
      async () => {
        const response = await browserGet(`${origin}/api/v1/tasks/${taskId}`);
        if (!response.ok) {
          throw new Error(`task query failed: HTTP_${response.status} ${response.body}`);
        }
        const phase = JSON.parse(response.body).phase || '';
        if (phase === 'FAILED') {
          throw new Error(`task execution failed: ${response.body}`);
        }
        return phase;
      },
      { timeout: 420_000, intervals: [10_000] },
    )
    .toBe('SUCCEEDED');
  await page.reload();
  await expect(page.locator('.detail-actions [role="status"]')).toHaveText('已完成', {
    timeout: 15_000,
  });

  // 5. 任务执行信息：执行尝试/分配记录、过程时间线、决策与成果物面板齐备。
  await expect(page.getByRole('heading', { name: '执行尝试' })).toBeVisible();
  await expect(page.getByText('当前任务尚未产生执行尝试。')).toHaveCount(0);
  const attemptArticle = page
    .locator('article.stack-list__item')
    .filter({ hasText: '分配记录' })
    .first();
  await expect(attemptArticle).toBeVisible();

  // 5.1 团队任务分配：执行 API 与详情页都应体现“分配给 team-a 某个就绪成员”。
  const executionResponse = await browserGet(`${origin}/api/v1/tasks/${taskId}/execution`);
  expect(executionResponse.ok, executionResponse.body).toBeTruthy();
  const executions = JSON.parse(executionResponse.body) as Array<{
    assignment?: { id: string; agentId: string } | null;
  }>;
  const assignedAgentId = executions.find((item) => item.assignment?.agentId)?.assignment?.agentId;
  expect(assignedAgentId, '任务应经团队调度产生分配记录').toBeTruthy();
  expect(memberIds, `分配的 ${assignedAgentId} 应属于 team-a 成员`).toContain(assignedAgentId);
  await expect(attemptArticle).toContainText(assignedAgentId!);
  await expect(page.getByRole('heading', { name: '实时执行过程' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '运行结果与成果物' })).toBeVisible();
  const timeline = page.getByTestId('task-process-events');
  await expect(timeline).toContainText('任务启动');
  await expect(timeline).toContainText('任务执行中');
  await expect(timeline).toContainText('任务已完成');

  // 6. 多成果物：Manifest 至少登记 2 个交付物（协议交付物或 output.md + result.json 兜底）。
  const resultSection = page.locator('section').filter({ hasText: '运行结果与成果物' }).last();
  await expect(resultSection.getByText('本次运行没有登记成果物。')).toHaveCount(0);
  const artifactRows = resultSection.locator('table.resource-table tbody tr');
  await expect(artifactRows).not.toHaveCount(0);
  expect(await artifactRows.count()).toBeGreaterThanOrEqual(2);
  const artifactTableText = await artifactRows.allInnerTexts();
  expect(artifactTableText.join('\n')).toContain('result.json');
  // 成果物面板应提供可直接下载的预签名入口。
  await expect(resultSection.getByRole('link', { name: '下载' }).first()).toBeVisible();

  const runLink = page.getByRole('link', { name: '查看运行' }).first();
  const runHref = await runLink.getAttribute('href');
  const runId = new URL(runHref!, new URL(page.url()).origin).searchParams.get('runId')!;
  const resultResponse = await browserGet(`${origin}/api/v1/tasks/${taskId}/runs/${runId}/result`);
  expect(resultResponse.ok).toBeTruthy();
  const resultManifest = JSON.parse(resultResponse.body) as {
    status: string;
    artifacts: Array<{ name: string; storageRef?: string | null; downloadUrl?: string | null }>;
  };
  expect(resultManifest.status).toBe('SUCCEEDED');
  expect(resultManifest.artifacts.length).toBeGreaterThanOrEqual(2);
  const envelope = resultManifest.artifacts.find(
    (artifact: { name: string }) => artifact.name === 'result.json',
  );
  expect(envelope, '任务应登记 result.json 执行信封').toBeTruthy();

  // 6.1 产物直传对象存储：storageRef 应为 MinIO 对象键（非 memory:// 内存捷径），
  // 且 result API 返回可匿名访问的预签名下载 URL。
  const persisted = resultManifest.artifacts.filter(
    (artifact) => artifact.storageRef && !artifact.storageRef.startsWith('memory://'),
  );
  expect(
    persisted.length,
    `产物应直传对象存储而非 memory://：${JSON.stringify(resultManifest.artifacts)}`,
  ).toBeGreaterThanOrEqual(2);
  for (const artifact of persisted) {
    expect(artifact.downloadUrl, `${artifact.name} 应返回预签名下载 URL`).toBeTruthy();
    const download = await request.get(artifact.downloadUrl!);
    expect(download.status(), `${artifact.name} 预签名下载应成功`).toBe(200);
    expect(
      (await download.text()).length,
      `${artifact.name} 下载内容不应为空`,
    ).toBeGreaterThan(0);
  }

  // 7. token 消耗：用量页读取 model_call_audits，本次真实模型调用必须可见。
  await page.goto(`/${projectPath}/usage`);
  await expect(page.getByRole('heading', { name: '用量与费用' })).toBeVisible();
  const metrics = page.getByLabel('使用量指标');
  await expect(metrics).toBeVisible({ timeout: 15_000 });
  const callsMetric = metrics.locator('.metric-card').filter({ hasText: '调用次数' }).first();
  await expect(callsMetric).toBeVisible();
  await expect
    .poll(
      async () => {
        await page.reload();
        await page.getByRole('heading', { name: '用量与费用' }).waitFor();
        const refreshed = page.getByLabel('使用量指标');
        const card = refreshed.locator('.metric-card').filter({ hasText: '调用次数' }).first();
        return Number((await card.innerText().catch(() => '0')).replace(/\D+/g, '')) || 0;
      },
      { timeout: 60_000, intervals: [5_000] },
    )
    .toBeGreaterThanOrEqual(1);
  const usageTable = page.locator('table.resource-table').filter({ hasText: 'Tokens' }).first();
  await expect(usageTable).toBeVisible();
  // 明细表：服务商 / 模型、调用、失败、Tokens、估算成本。
  const tokensCell = usageTable.locator('tbody tr').first().locator('td').nth(3);
  expect(Number(await tokensCell.innerText())).toBeGreaterThan(0);
});
