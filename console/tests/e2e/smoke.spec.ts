import { expect, test } from '@playwright/test';

test('public console entry exposes the login action', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('link', { name: '登录' })).toBeVisible();
});

test('public console CTA opens the login flow when unauthenticated', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('link', { name: '进入控制台' }).click();
  await expect(page).toHaveURL(/\/login$/);
});

test('real OIDC login through the console entry reaches a project without a login loop', async ({
  page,
}) => {
  const username = process.env.AGENTTEAMS_E2E_USERNAME;
  const password = process.env.AGENTTEAMS_E2E_PASSWORD;
  const oidcPort = process.env.AGENTTEAMS_E2E_OIDC_PORT || '18082';
  test.skip(!username || !password, 'set AGENTTEAMS_E2E_USERNAME and AGENTTEAMS_E2E_PASSWORD');

  await page.goto('/');
  await page.getByRole('link', { name: '进入控制台' }).click();
  await expect(page).toHaveURL(/\/login$/);
  await page.getByRole('button', { name: '使用组织账号登录' }).click();
  await expect(page).toHaveURL(
    new RegExp(`:${oidcPort}/realms/agentteams/protocol/openid-connect/auth(?:/|\\?)`),
  );

  await page.locator('#username').fill(username!);
  await page.locator('#password').fill(password!);
  await page.locator('#kc-login').click();

  await expect(page).toHaveURL(/:30080\/[^/]+\/overview$/, { timeout: 30_000 });
  await expect(page.getByText('Control Plane 已连接')).toBeVisible();
  await expect(page).not.toHaveURL(/\/login$/);
});

test('real OIDC browser sessions isolate users and tenant scopes', async ({ browser }) => {
  const username = process.env.AGENTTEAMS_E2E_USERNAME;
  const password = process.env.AGENTTEAMS_E2E_PASSWORD;
  const readerUsername = process.env.AGENTTEAMS_E2E_READER_USERNAME;
  const readerPassword = process.env.AGENTTEAMS_E2E_READER_PASSWORD;
  const tenantBUsername = process.env.AGENTTEAMS_E2E_TENANT_B_USERNAME;
  const tenantBPassword = process.env.AGENTTEAMS_E2E_TENANT_B_PASSWORD;
  const oidcPort = process.env.AGENTTEAMS_E2E_OIDC_PORT || '18082';
  test.skip(
    !username ||
      !password ||
      !readerUsername ||
      !readerPassword ||
      !tenantBUsername ||
      !tenantBPassword,
    'set Alice, Reader, and Tenant-B non-production OIDC credentials',
  );

  async function login(page: import('@playwright/test').Page, user: string, secret: string) {
    await page.goto('/console');
    await expect(page).toHaveURL(/\/login$/);
    await page.getByRole('button', { name: '使用组织账号登录' }).click();
    await expect(page).toHaveURL(
      new RegExp(`:${oidcPort}/realms/agentteams/protocol/openid-connect/auth(?:/|\\?)`),
    );
    await page.locator('#username').fill(user);
    await page.locator('#password').fill(secret);
    await page.locator('#kc-login').click();
  }

  const alice = await browser.newPage();
  await login(alice, username!, password!);
  await expect(alice).toHaveURL(/:30080\/[^/]+\/overview$/, { timeout: 30_000 });
  await expect(alice.getByText('Control Plane 已连接')).toBeVisible();
  const projectPath = new URL(alice.url()).pathname.split('/')[1];
  await alice.goto(`/${projectPath}/memory`);
  await expect(alice.getByRole('heading', { name: 'Memory 治理' })).toBeVisible();
  await alice.goto(`/${projectPath}/sandboxes`);
  await expect(alice.getByRole('heading', { name: 'Sandbox 运维' })).toBeVisible();

  const reader = await browser.newPage();
  await login(reader, readerUsername!, readerPassword!);
  await expect(reader).toHaveURL(/\/console$/, { timeout: 30_000 });
  await expect(reader.getByText('暂无可访问的 Project')).toBeVisible();
  await reader.goto(`/${projectPath}/memory`);
  await expect(reader.getByRole('heading', { name: '无权访问' })).toBeVisible();
  await reader.goto(`/${projectPath}/sandboxes`);
  await expect(reader.getByRole('heading', { name: '无权访问' })).toBeVisible();

  const tenantB = await browser.newPage();
  await login(tenantB, tenantBUsername!, tenantBPassword!);
  await expect(tenantB).toHaveURL(/\/console$/, { timeout: 30_000 });
  await expect(tenantB.getByText('暂无可访问的 Project')).toBeVisible();

  await Promise.all([alice.close(), reader.close(), tenantB.close()]);
});

test('real OIDC identity page manages internal user status lifecycle', async ({ page }) => {
  const username = process.env.AGENTTEAMS_E2E_QUOTA_ADMIN_USERNAME;
  const password = process.env.AGENTTEAMS_E2E_QUOTA_ADMIN_PASSWORD;
  const oidcPort = process.env.AGENTTEAMS_E2E_OIDC_PORT || '18082';
  test.skip(!username || !password, 'set non-production quota-admin OIDC credentials');

  await page.goto('/console');
  await expect(page).toHaveURL(/\/login$/);
  await page.getByRole('button', { name: '使用组织账号登录' }).click();
  await expect(page).toHaveURL(
    new RegExp(`:${oidcPort}/realms/agentteams/protocol/openid-connect/auth(?:/|\\?)`),
  );
  await page.locator('#username').fill(username!);
  await page.locator('#password').fill(password!);
  await page.locator('#kc-login').click();

  await expect(page).toHaveURL(/:30080\/(?:console|[^/]+\/overview)$/, { timeout: 30_000 });
  await page.goto('/settings/identity');
  await expect(page.getByRole('heading', { name: '身份与权限管理' })).toBeVisible();
  const subject = `e2e-internal-${Date.now()}`;
  const displayName = `E2E Internal ${Date.now()}`;
  await page.getByLabel('内部用户 Subject').fill(subject);
  await page.getByLabel('内部用户名称').fill(displayName);
  await page.getByRole('button', { name: '创建内部用户' }).click();
  await expect(page.getByText('内部用户已创建')).toBeVisible({ timeout: 10_000 });
  await page.reload();
  await expect(page.getByRole('heading', { name: '身份与权限管理' })).toBeVisible();

  const userRow = page.locator('[aria-label="已登记内部用户"] .stack-list__item').filter({
    hasText: `${displayName} · ${subject}`,
  });
  await expect(userRow).toContainText('活跃');
  await userRow.getByRole('button', { name: `停用内部用户 ${displayName}` }).click();
  await expect(page.getByText('内部用户已停用')).toBeVisible({ timeout: 10_000 });
  await expect(userRow).toContainText('已禁用');

  await userRow.getByRole('button', { name: `重新激活内部用户 ${displayName}` }).click();
  await expect(page.getByText('内部用户已重新激活')).toBeVisible({ timeout: 10_000 });
  await expect(userRow).toContainText('活跃');
});

test('real OIDC artifact page reads project metadata and retention policy', async ({ page }) => {
  const username = process.env.AGENTTEAMS_E2E_USERNAME;
  const password = process.env.AGENTTEAMS_E2E_PASSWORD;
  const oidcPort = process.env.AGENTTEAMS_E2E_OIDC_PORT || '18082';
  test.skip(!username || !password, 'set Alice non-production OIDC credentials');

  await page.goto('/console');
  await expect(page).toHaveURL(/\/login$/);
  await page.getByRole('button', { name: '使用组织账号登录' }).click();
  await expect(page).toHaveURL(
    new RegExp(':' + oidcPort + '/realms/agentteams/protocol/openid-connect/auth(?:/|\\?)'),
  );
  await page.locator('#username').fill(username!);
  await page.locator('#password').fill(password!);
  await page.locator('#kc-login').click();

  await expect(page).toHaveURL(/:30080\/[^/]+\/overview$/, { timeout: 30_000 });
  const projectPath = new URL(page.url()).pathname.split('/')[1];
  await page.goto('/' + projectPath + '/artifacts');
  await expect(page.getByRole('heading', { name: '制品', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: '制品保留策略', exact: true })).toBeVisible();
  await expect(page.getByLabel('成功任务保留（秒）')).toBeVisible();
  await expect(page.getByLabel('失败任务保留（秒）')).toBeVisible();
  await expect(page.getByLabel('临时上传保留（秒）')).toBeVisible();
  await expect(page.getByRole('button', { name: '保存保留策略' })).toBeEnabled();
  await expect(page.getByText(/SHA-256|暂无产物/).first()).toBeVisible();
  await expect(page.getByRole('link', { name: '下载' })).toHaveCount(0);
});

test('real OIDC conversation recovers history and supports cancellation', async ({ page }) => {
  const username = process.env.AGENTTEAMS_E2E_USERNAME;
  const password = process.env.AGENTTEAMS_E2E_PASSWORD;
  const oidcPort = process.env.AGENTTEAMS_E2E_OIDC_PORT || '18082';
  test.skip(!username || !password, 'set Alice non-production OIDC credentials');

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
  const projectPath = new URL(page.url()).pathname.split('/')[1];
  await page.goto(`/${projectPath}/conversations/new`);
  await expect(page.getByRole('heading', { name: '选择对话团队' })).toBeVisible();
  const team = page.getByRole('button', { name: /team-a|E2E Team/ }).first();
  await expect(team).toBeVisible({ timeout: 10_000 });
  await team.click();

  await expect(page.getByRole('heading', { name: '工作节点对话' })).toBeVisible();
  const firstMessage = `E2E conversation ${Date.now()}`;
  const transcript = page.getByRole('region', { name: '对话记录' });
  await page.getByRole('textbox', { name: '输入消息' }).fill(firstMessage);
  const sendButton = page.getByRole('button', { name: '发送' });
  await expect(sendButton).toBeEnabled({ timeout: 10_000 });
  await sendButton.click();
  await expect(transcript).toContainText(firstMessage, { timeout: 10_000 });
  await expect(transcript).toContainText('CONVERSATION_MOCK_DELTA', { timeout: 10_000 });

  await page.reload();
  await expect(page.getByRole('heading', { name: '工作节点对话' })).toBeVisible();
  await expect(page.getByRole('region', { name: '对话记录' })).toContainText(firstMessage);
  await expect(page.getByRole('region', { name: '对话记录' })).toContainText(
    'CONVERSATION_MOCK_DELTA',
  );

  await page.getByRole('button', { name: '取消会话' }).click();
  await expect(page.getByRole('button', { name: '确认取消会话' })).toBeVisible();
  await page.getByRole('button', { name: '确认取消会话' }).click();
  await expect(page.getByText('会话已取消')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByRole('textbox', { name: '输入消息' })).toBeDisabled();
  await expect(page.getByRole('button', { name: '发送' })).toBeDisabled();
});

test('real OIDC task page creates, inspects and cancels a normal task', async ({ page }) => {
  const username = process.env.AGENTTEAMS_E2E_QUOTA_ADMIN_USERNAME;
  const password = process.env.AGENTTEAMS_E2E_QUOTA_ADMIN_PASSWORD;
  const oidcPort = process.env.AGENTTEAMS_E2E_OIDC_PORT || '18082';
  test.skip(!username || !password, 'set non-production quota-admin OIDC credentials');

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
  // The development OIDC fixture grants quota-admin the project-a/team-a scope;
  // select project-a by its display name, then keep using its stable UUID route.
  const projectOption = page
    .getByLabel('当前项目')
    .locator('option')
    .filter({ hasText: 'project-a' });
  await expect(projectOption).toHaveCount(1, { timeout: 10_000 });
  const projectPath = (await projectOption.getAttribute('value'))!;
  await page.getByLabel('当前项目').selectOption(projectPath);
  await expect(page).toHaveURL(new RegExp(`/${projectPath}/overview$`));
  // The task API requires the Team internal name to match the OIDC team scope.
  // Reuse the deterministic team-a fixture when present; otherwise provision it
  // explicitly so the test does not depend on historical random Teams.
  await page.goto(`/${projectPath}/teams`);
  await expect(page.getByRole('heading', { name: '团队' })).toBeVisible();
  const teamA = page
    .getByRole('link')
    .filter({ hasText: /team-a/ })
    .first();
  if ((await teamA.count()) === 0) {
    await page.getByRole('link', { name: '创建团队' }).first().click();
    await expect(page.getByRole('heading', { name: '创建团队' })).toBeVisible();
    await page.getByLabel('显示名称').fill('team-a');
    await page.getByRole('button', { name: '下一步' }).click();
    await page.getByRole('button', { name: '下一步' }).click();
    await page.getByRole('button', { name: '下一步' }).click();
    await page.getByRole('button', { name: '创建团队' }).click();
    await expect(page).toHaveURL(new RegExp(`/${projectPath}/teams/[^/]+$`));
  }

  await page.goto(`/${projectPath}/tasks/new`);
  // The create flow may have populated the previous Teams query cache; reload
  // the create page so the selector reflects the committed Team list.
  await page.reload();
  await expect(page.getByRole('heading', { name: '创建任务' })).toBeVisible();
  const teamOption = page
    .getByLabel('任务团队')
    .locator('option')
    .filter({ hasText: /team-a/ });
  await expect(teamOption).toHaveCount(1, { timeout: 10_000 });
  await page.getByLabel('任务团队').selectOption((await teamOption.getAttribute('value'))!);

  const title = `E2E Task ${Date.now()}`;
  await page.getByLabel('任务标题').fill(title);
  await page
    .getByLabel('任务说明')
    .fill('Create and cancel a development task through the Console.');
  await page.getByRole('button', { name: '创建任务' }).click();
  await expect(page).toHaveURL(new RegExp(`/${projectPath}/tasks/[^/]+$`));
  await expect(page.getByRole('heading', { name: title })).toBeVisible({ timeout: 10_000 });
  await expect(page.getByText('当前任务尚未产生执行尝试。')).toBeVisible();
  await expect(page.getByText('当前任务尚未发生租约恢复。')).toBeVisible();

  await page.getByRole('button', { name: '取消任务' }).click();
  await expect(page.getByRole('button', { name: '确认取消任务' })).toBeVisible();
  await page.getByRole('button', { name: '确认取消任务' }).click();
  await expect(page.getByText('操作已提交')).toBeVisible({ timeout: 10_000 });
  await page.reload();
  await expect(page.getByRole('heading', { name: title })).toBeVisible();
  await expect(page.locator('[role="status"]').filter({ hasText: '已取消' })).toBeVisible();
});

test('real OIDC alert page exposes failed delivery and retry action', async ({ page }) => {
  const username = process.env.AGENTTEAMS_E2E_USERNAME;
  const password = process.env.AGENTTEAMS_E2E_PASSWORD;
  const oidcPort = process.env.AGENTTEAMS_E2E_OIDC_PORT || '18082';
  test.skip(!username || !password, 'set Alice non-production OIDC credentials');

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
  const projectPath = new URL(page.url()).pathname.split('/')[1];
  await page.goto(`/${projectPath}/alerts`);
  await expect(page.getByRole('heading', { name: '告警中心' })).toBeVisible();
  const failedRow = page.locator('tbody tr').filter({ hasText: 'FAILED' }).first();
  await expect(failedRow).toBeVisible();
  await expect(failedRow.getByRole('button', { name: '立即重试' })).toBeVisible();
  await failedRow.getByRole('button', { name: '立即重试' }).click();
  await expect(failedRow.getByRole('button', { name: '立即重试' })).toBeVisible({
    timeout: 10_000,
  });
});

test('real OIDC Skill page uploads a package through object storage and completes it', async ({
  page,
}) => {
  const username = process.env.AGENTTEAMS_E2E_QUOTA_ADMIN_USERNAME;
  const password = process.env.AGENTTEAMS_E2E_QUOTA_ADMIN_PASSWORD;
  const oidcPort = process.env.AGENTTEAMS_E2E_OIDC_PORT || '18082';
  test.skip(!username || !password, 'set non-production quota-admin OIDC credentials');

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
  const projectPath = new URL(page.url()).pathname.split('/')[1];
  await page.goto(`/${projectPath}/skills`);
  await expect(page.getByRole('heading', { name: 'Skills' })).toBeVisible();

  const suffix = Date.now();
  const displayName = `E2E Skill ${suffix}`;
  await page.getByLabel('内部名称').fill(`e2e-skill-${suffix}`);
  await page.getByLabel('显示名称').fill(displayName);
  await page.getByLabel('描述').fill('Real object storage upload acceptance');
  await page.getByRole('button', { name: '创建 Skill' }).click();
  await expect(page.getByText('Skill 已创建')).toBeVisible();
  // Skill 列表刷新是异步的；重新加载确保版本选择器读取已提交的目录数据。
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Skills' })).toBeVisible();

  const skillOption = page.locator('#skill-version-skill option').filter({ hasText: displayName });
  await expect(skillOption).toHaveCount(1);
  await page
    .locator('#skill-version-skill')
    .selectOption((await skillOption.getAttribute('value'))!);
  await page.getByLabel('版本').fill('1.0.0');
  await page.getByLabel('Digest').fill(`sha256:${'a'.repeat(64)}`);
  await page.getByLabel('Manifest JSON').fill(
    JSON.stringify({
      name: `e2e-skill-${suffix}`,
      description: 'Real object storage upload acceptance',
      entry: 'manifest.json',
      sizeBytes: 0,
    }),
  );
  await page.getByRole('button', { name: '创建版本' }).click();
  await expect(page.getByText(/package NOT_STARTED/)).toBeVisible();

  const packageBytes = Buffer.from(
    'H4sIAAAAAAAC/+3NQQ6CMBSE4bfmFD0BeZIC52m0mFIoxuL9LWxI3EtM/L/NTGYzs0th8Hmtx7wk+Q4tOmv3LD5TtemPvu0XtbYVo3KCV17ds9zLf4oh3UyOYZrMw12ju3vjG18JAAAAAAAAAAAAAAAAAOC3vQEopbFmACgAAA==',
    'base64',
  );
  await page.getByLabel('Skill package').setInputFiles({
    name: `e2e-skill-${suffix}.tar.gz`,
    mimeType: 'application/gzip',
    buffer: packageBytes,
  });
  await page.getByRole('button', { name: '上传制品' }).click();
  await expect(page.getByText('制品上传已完成')).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText(/package COMPLETED/)).toBeVisible();
});

test('real OIDC organization page creates and version-updates an organization and tenant', async ({
  page,
}) => {
  const username = process.env.AGENTTEAMS_E2E_QUOTA_ADMIN_USERNAME;
  const password = process.env.AGENTTEAMS_E2E_QUOTA_ADMIN_PASSWORD;
  const oidcPort = process.env.AGENTTEAMS_E2E_OIDC_PORT || '18082';
  test.skip(!username || !password, 'set non-production quota-admin OIDC credentials');

  await page.goto('/console');
  await expect(page).toHaveURL(/\/login$/);
  await page.getByRole('button', { name: '使用组织账号登录' }).click();
  await expect(page).toHaveURL(
    new RegExp(`:${oidcPort}/realms/agentteams/protocol/openid-connect/auth(?:/|\\?)`),
  );
  await page.locator('#username').fill(username!);
  await page.locator('#password').fill(password!);
  await page.locator('#kc-login').click();

  await expect(page).toHaveURL(/:30080\/(?:console|[^/]+\/overview)$/, { timeout: 30_000 });
  await page.goto('/settings/organizations');
  await expect(page.getByRole('heading', { name: '组织与租户' })).toBeVisible();

  const suffix = Date.now();
  const organizationName = `E2E Organization ${suffix}`;
  const tenantName = `E2E Tenant ${suffix}`;
  await page.getByLabel('组织名称').fill(organizationName);
  await page.getByRole('button', { name: '创建组织' }).click();
  await expect(page.getByText('组织已创建')).toBeVisible();

  const organization = page.locator('article').filter({ hasText: organizationName });
  await expect(organization).toBeVisible();
  await page.getByLabel('租户名称').fill(tenantName);
  await page.getByRole('button', { name: '创建租户' }).click();
  await expect(page.getByText('租户已创建')).toBeVisible();
  await expect(organization).toContainText(tenantName);

  await organization.getByRole('button', { name: '暂停租户' }).click();
  await expect(page.getByText('租户状态已更新')).toBeVisible();
  await expect(organization).toContainText('已暂停');
  await organization.getByRole('button', { name: '恢复租户' }).click();
  await expect(page.getByText('租户状态已更新')).toBeVisible();
  await expect(organization).toContainText('活跃');

  await organization.getByRole('button', { name: '暂停组织' }).click();
  await expect(page.getByText('组织状态已更新')).toBeVisible();
  await expect(organization).toContainText('已暂停');
  await organization.getByRole('button', { name: '恢复组织' }).click();
  await expect(page.getByText('组织状态已更新')).toBeVisible();
  await expect(organization).toContainText('活跃');
});

test('real OIDC identity page completes external-user provisioning lifecycle', async ({ page }) => {
  const username = process.env.AGENTTEAMS_E2E_QUOTA_ADMIN_USERNAME;
  const password = process.env.AGENTTEAMS_E2E_QUOTA_ADMIN_PASSWORD;
  const oidcPort = process.env.AGENTTEAMS_E2E_OIDC_PORT || '18082';
  test.skip(!username || !password, 'set non-production quota-admin OIDC credentials');

  await page.goto('/console');
  await expect(page).toHaveURL(/\/login$/);
  await page.getByRole('button', { name: '使用组织账号登录' }).click();
  await expect(page).toHaveURL(
    new RegExp(`:${oidcPort}/realms/agentteams/protocol/openid-connect/auth(?:/|\\?)`),
  );
  await page.locator('#username').fill(username!);
  await page.locator('#password').fill(password!);
  await page.locator('#kc-login').click();

  await expect(page).toHaveURL(/\/(?:console|[^/]+\/overview)$/, { timeout: 30_000 });
  const suffix = Date.now();
  const organizationName = `E2E Provisioning Organization ${suffix}`;
  const integrationName = `E2E Provisioning Integration ${suffix}`;
  await page.goto('/settings/organizations');
  await page.getByLabel('组织名称').fill(organizationName);
  await page.getByRole('button', { name: '创建组织' }).click();
  await expect(page.getByText('组织已创建')).toBeVisible();

  await page.goto('/settings/integrations');
  await page.getByLabel('Organization').selectOption({ label: organizationName });
  await page.getByLabel('Integration 名称').fill(integrationName);
  await page.getByRole('button', { name: '创建 Integration' }).click();
  await expect(page.getByText('Integration 已创建')).toBeVisible();
  const integration = page.locator('article').filter({ hasText: integrationName });
  await expect(integration).toBeVisible();
  const integrationId = await integration.locator('p.muted-text').innerText();

  await page.goto('/settings/identity');
  await expect(page.getByRole('heading', { name: '外部用户生命周期' })).toBeVisible();
  await page.getByLabel('生命周期 Integration ID').fill(integrationId);
  await page.getByLabel('生命周期外部组织 ID').fill(`external-org-${suffix}`);
  await page.getByLabel('生命周期外部用户 ID').fill(`external-user-${suffix}`);
  await page.getByLabel('生命周期用户名称').fill('E2E Alice');

  await page.getByRole('button', { name: '初始化外部用户' }).click();
  await expect(page.getByText('外部用户已初始化')).toBeVisible();
  await page.getByLabel('生命周期用户名称').fill('E2E Alice Updated');
  await page.getByRole('button', { name: '更新外部用户' }).click();
  await expect(page.getByText('外部用户已更新')).toBeVisible();
  await page.getByRole('button', { name: '查询 Membership' }).click();
  await expect(page.getByText('Membership 查询完成')).toBeVisible();
  await page.getByRole('button', { name: '停用外部用户' }).click();
  await expect(page.getByText('外部用户已停用')).toBeVisible();
});

test('real OIDC integrations page completes credential reference lifecycle', async ({ page }) => {
  const username = process.env.AGENTTEAMS_E2E_QUOTA_ADMIN_USERNAME;
  const password = process.env.AGENTTEAMS_E2E_QUOTA_ADMIN_PASSWORD;
  const oidcPort = process.env.AGENTTEAMS_E2E_OIDC_PORT || '18082';
  test.skip(!username || !password, 'set non-production quota-admin OIDC credentials');

  await page.goto('/console');
  await expect(page).toHaveURL(/\/login$/);
  await page.getByRole('button', { name: '使用组织账号登录' }).click();
  await expect(page).toHaveURL(
    new RegExp(`:${oidcPort}/realms/agentteams/protocol/openid-connect/auth(?:/|\\?)`),
  );
  await page.locator('#username').fill(username!);
  await page.locator('#password').fill(password!);
  await page.locator('#kc-login').click();
  await expect(page).toHaveURL(/\/(?:console|[^/]+\/overview)$/, { timeout: 30_000 });

  const suffix = Date.now();
  const organizationName = `E2E Credential Organization ${suffix}`;
  const integrationName = `E2E Credential Integration ${suffix}`;
  await page.goto('/settings/organizations');
  await page.getByLabel('组织名称').fill(organizationName);
  await page.getByRole('button', { name: '创建组织' }).click();
  await expect(page.getByText('组织已创建')).toBeVisible();

  await page.goto('/settings/integrations');
  await page.getByLabel('Organization').selectOption({ label: organizationName });
  await page.getByLabel('Integration 名称').fill(integrationName);
  await page.getByRole('button', { name: '创建 Integration' }).click();
  await expect(page.getByText('Integration 已创建')).toBeVisible();
  await page.getByLabel('Credential Label').fill('primary');
  await page
    .getByLabel('Credential Ref', { exact: true })
    .fill(`secret://dev/credential-${suffix}`);
  await page.getByRole('button', { name: '登记 Credential Ref' }).click();
  await expect(page.getByText('Credential Ref 已登记')).toBeVisible();

  const integration = page.locator('article').filter({ hasText: integrationName });
  await expect(integration.getByText(/primary · AKIA-.* · ACTIVE/)).toBeVisible();
  await page.getByLabel('轮换 Credential Ref').fill(`secret://dev/credential-rotated-${suffix}`);
  await integration.getByRole('button', { name: '轮换' }).click();
  await expect(page.getByText('Credential 已轮换')).toBeVisible();

  page.on('dialog', (dialog) => void dialog.accept());
  await integration.getByRole('button', { name: '撤销' }).click();
  await expect(page.getByText('Credential 已撤销')).toBeVisible();
  await expect(integration.getByText(/primary · AKIA-.* · REVOKED/)).toBeVisible();
});

test('real OIDC role page exposes the scoped project authorization matrix', async ({ page }) => {
  const username = process.env.AGENTTEAMS_E2E_USERNAME;
  const password = process.env.AGENTTEAMS_E2E_PASSWORD;
  const oidcPort = process.env.AGENTTEAMS_E2E_OIDC_PORT || '18082';
  test.skip(!username || !password, 'set Alice non-production OIDC credentials');

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
  await page.goto('/settings/roles');
  await expect(page.getByRole('heading', { name: '角色与权限' })).toBeVisible();
  await expect(page.getByText('有效权限矩阵')).toBeVisible();
  await expect(page.getByRole('heading', { name: '所有者' })).toBeVisible();
  await expect(page.getByText('PROJECT_READ').first()).toBeVisible();
});

test('real OIDC project page creates a project in the current tenant', async ({ page }) => {
  const username = process.env.AGENTTEAMS_E2E_USERNAME;
  const password = process.env.AGENTTEAMS_E2E_PASSWORD;
  const oidcPort = process.env.AGENTTEAMS_E2E_OIDC_PORT || '18082';
  test.skip(!username || !password, 'set Alice non-production OIDC credentials');

  // L5 is served over HTTP. Exercise the compatibility path explicitly so a
  // browser without the secure-context-only randomUUID API remains supported.
  await page.addInitScript(() => {
    Object.defineProperty(Crypto.prototype, 'randomUUID', {
      configurable: true,
      value: undefined,
    });
  });

  await page.goto('/console');
  await expect.poll(() => page.evaluate(() => typeof globalThis.crypto?.randomUUID)).toBe('undefined');
  await expect(page).toHaveURL(/\/login$/);
  await page.getByRole('button', { name: '使用组织账号登录' }).click();
  await expect(page).toHaveURL(
    new RegExp(`:${oidcPort}/realms/agentteams/protocol/openid-connect/auth(?:/|\\?)`),
  );
  await page.locator('#username').fill(username!);
  await page.locator('#password').fill(password!);
  await page.locator('#kc-login').click();

  await expect(page).toHaveURL(/:30080\/[^/]+\/overview$/, { timeout: 30_000 });
  await page.goto('/settings/projects');
  await expect(page.getByRole('heading', { name: '项目管理' })).toBeVisible();

  const name = `E2E Project ${Date.now()}`;
  await page.getByLabel('项目名称').fill(name);
  await page.getByRole('button', { name: '创建项目' }).click();
  await expect(page.getByText('项目已创建')).toBeVisible();
  await expect(page.getByText(name)).toBeVisible();
});

test('real OIDC Team page creates a Team in the current project scope', async ({ page }) => {
  const username = process.env.AGENTTEAMS_E2E_USERNAME;
  const password = process.env.AGENTTEAMS_E2E_PASSWORD;
  const oidcPort = process.env.AGENTTEAMS_E2E_OIDC_PORT || '18082';
  test.skip(!username || !password, 'set Alice non-production OIDC credentials');

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
  const projectPath = new URL(page.url()).pathname.split('/')[1];
  await page.goto(`/${projectPath}/teams`);
  await expect(page.getByRole('heading', { name: '团队' })).toBeVisible();
  await page.getByRole('link', { name: '创建团队' }).first().click();
  await expect(page.getByRole('heading', { name: '创建团队' })).toBeVisible();

  const displayName = `E2E Team ${Date.now()}`;
  await page.getByLabel('显示名称').fill(displayName);
  await page.getByRole('button', { name: '下一步' }).click();
  await page.getByRole('button', { name: '下一步' }).click();
  await page.getByRole('button', { name: '下一步' }).click();
  await page.getByRole('button', { name: '创建团队' }).click();

  await expect(page).toHaveURL(new RegExp(`/${projectPath}/teams/[^/]+$`));
  await expect(page.getByRole('heading', { name: displayName })).toBeVisible();
  await page.getByRole('tab', { name: '版本与部署' }).click();
  await expect(page.getByRole('heading', { name: '部署', exact: true })).toBeVisible();
  await expect(page.getByText('暂无部署')).toBeVisible();

  await page.goto('/00000000-0000-0000-0000-000000000026/teams');
  await expect(page.getByRole('heading', { name: '无权访问' })).toBeVisible();
});

test('real OIDC template flow provisions a Worker only after explicit instantiation', async ({
  page,
}) => {
  const username = process.env.AGENTTEAMS_E2E_QUOTA_ADMIN_USERNAME;
  const password = process.env.AGENTTEAMS_E2E_QUOTA_ADMIN_PASSWORD;
  const oidcPort = process.env.AGENTTEAMS_E2E_OIDC_PORT || '18082';
  test.skip(!username || !password, 'set non-production quota-admin OIDC credentials');

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
  const projectPath = new URL(page.url()).pathname.split('/')[1];
  const suffix = Date.now();
  const displayName = `E2E Worker Template ${suffix}`;
  const templateName = `e2e-worker-template-${suffix}`;

  await page.goto(`/${projectPath}/templates`);
  await expect(page.getByRole('heading', { name: '工作节点模板' })).toBeVisible();
  await page.getByLabel('内部名称').fill(templateName);
  await page.getByLabel('显示名称').fill(displayName);
  await page.getByRole('button', { name: '创建模板' }).click();
  await expect(page.getByText('工作节点模板已创建')).toBeVisible();

  const templateOption = page.locator('#revision-template option').filter({ hasText: displayName });
  await expect(templateOption).toHaveCount(1);
  await page
    .locator('#revision-template')
    .selectOption((await templateOption.getAttribute('value'))!);
  await page.getByLabel('工作节点规格 JSON').fill(
    JSON.stringify({
      runtime: 'qwenpaw',
      modelProvider: 'deepseek',
      modelName: 'deepseek-chat',
    }),
  );
  await page.getByRole('button', { name: '创建版本' }).click();
  await expect(page.getByText(/版本 \d+ 已创建/)).toBeVisible();
  await page.getByRole('button', { name: '发布此版本' }).click();
  await expect(page.getByText(/版本 \d+ 已发布/)).toBeVisible();

  const templateCard = page.locator('article').filter({ hasText: displayName });
  await expect(templateCard.getByRole('button', { name: '显式实例化工作节点' })).toBeVisible();
  await templateCard.getByRole('button', { name: '显式实例化工作节点' }).click();
  await expect(page.getByText(/已创建实例 .*，等待工作节点就绪/)).toBeVisible({ timeout: 30_000 });

  await page.goto(`/${projectPath}/workers`);
  await expect(page.getByRole('heading', { name: '工作节点' })).toBeVisible();
  const workerLink = page.getByRole('link', { name: /template-worker-/ }).first();
  await expect(workerLink).toBeVisible({ timeout: 30_000 });
  await workerLink.click();
  await expect(page.getByRole('heading', { name: /template-worker-/ })).toBeVisible();
  await expect(page.getByRole('heading', { name: '操作记录' })).toBeVisible();
});

test('real OIDC AgentSpec page creates publishes and deactivates independently', async ({
  page,
}) => {
  const username =
    process.env.AGENTTEAMS_E2E_AGENT_SPEC_USERNAME ||
    process.env.AGENTTEAMS_E2E_QUOTA_ADMIN_USERNAME;
  const password =
    process.env.AGENTTEAMS_E2E_AGENT_SPEC_PASSWORD ||
    process.env.AGENTTEAMS_E2E_QUOTA_ADMIN_PASSWORD;
  const oidcPort = process.env.AGENTTEAMS_E2E_OIDC_PORT || '18082';
  test.skip(!username || !password, 'set non-production AgentSpec or quota-admin OIDC credentials');

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
  const projectPath = new URL(page.url()).pathname.split('/')[1];
  await page.goto(`/${projectPath}/agentspecs`);
  await expect(page.getByRole('heading', { name: '智能体规格', exact: true })).toBeVisible();

  const name = `E2E AgentSpec ${Date.now()}`;
  await page.getByLabel('内部名称').fill(name);
  await page.getByLabel('运行时').fill('qwenpaw');
  await page.getByLabel('模型服务商').fill('deepseek');
  await page.getByLabel('模型名称').fill('deepseek-chat');
  await page.getByLabel('规格 JSON').fill('{"mode":"safe"}');
  await page.getByRole('button', { name: '创建智能体规格' }).click();
  await expect(page.getByText('智能体规格已创建')).toBeVisible();

  const card = page.locator('article').filter({ hasText: name });
  await expect(card).toBeVisible();
  await expect(card.getByText('草稿')).toBeVisible();
  await card.getByRole('button', { name: '发布' }).click();
  await expect(page.getByText('智能体规格已发布')).toBeVisible();
  await expect(card.getByText('已发布')).toBeVisible();
  await card.getByRole('button', { name: '停用' }).click();
  await expect(page.getByText('智能体规格已停用')).toBeVisible();
  await expect(card.getByText('已禁用')).toBeVisible();
});

test('real OIDC team leader worker conversation task execution and artifact queries', async ({
  page,
}) => {
  test.setTimeout(600_000);
  const username = process.env.AGENTTEAMS_E2E_QUOTA_ADMIN_USERNAME;
  const password = process.env.AGENTTEAMS_E2E_QUOTA_ADMIN_PASSWORD;
  const oidcPort = process.env.AGENTTEAMS_E2E_OIDC_PORT || '18082';
  test.skip(!username || !password, 'set non-production quota-admin OIDC credentials');

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

  // 1. Reuse a ready LEADER worker when the environment already has one;
  // otherwise create and explicitly instantiate one from the management UI.
  const suffix = Date.now();
  let leaderId = '';
  let reusableLeaderFound = false;
  let leaderDetailVerified = false;
  await page.goto(`/${projectPath}/workers`);
  await page.getByPlaceholder('搜索工作节点').fill('template-worker-');
  await expect
    .poll(() => page.locator('tbody tr').count(), { timeout: 15_000 })
    .toBeGreaterThan(0);
  const readyLeaderRows = page
    .locator('tbody tr')
    .filter({ hasText: '负责人工作节点' })
    .filter({ hasText: '就绪' });
  const readyLeaderCount = await readyLeaderRows.count();
  for (let index = 0; index < readyLeaderCount; index += 1) {
    const candidate = readyLeaderRows.nth(index);
    const candidateHref = await candidate.getByRole('link').getAttribute('href');
    const candidateId = candidateHref!.split('/').pop()!;
    await page.goto(candidateHref!);
    if (
      (await page.getByRole('heading', { name: /template-worker-/ }).count()) > 0 &&
      (await page.getByText('无权访问').count()) === 0
    ) {
      leaderId = candidateId;
      reusableLeaderFound = true;
      leaderDetailVerified = true;
      break;
    }
    await page.goto(`/${projectPath}/workers`);
    await page.getByPlaceholder('搜索工作节点').fill('template-worker-');
  }

  if (!reusableLeaderFound) {
    // If old Worker detail records are no longer visible, reuse a Leader that is
    // already configured in team-a before provisioning another slow Worker.
    await page.goto(`/${projectPath}/teams`);
    const teamLinkForLeader = page.getByRole('link').filter({ hasText: /team-a/ }).first();
    await expect(teamLinkForLeader).toBeVisible({ timeout: 10_000 });
    await teamLinkForLeader.click();
    await page.getByRole('tab', { name: '成员 Agent' }).click();
    const configuredLeader = page
      .locator('.member-row')
      .filter({ hasText: '负责人工作节点' })
      .filter({ hasText: '就绪' })
      .first();
    if ((await configuredLeader.count()) > 0) {
      const leaderText = await configuredLeader.innerText();
      const leaderMatch = leaderText.match(/[0-9a-f]{8}-[0-9a-f-]{27,}/i);
      if (leaderMatch) {
        leaderId = leaderMatch[0];
        reusableLeaderFound = true;
      }
    }
  }

  if (!reusableLeaderFound) {
    const templateName = `e2e-leader-template-${suffix}`;
    const templateDisplayName = `E2E 负责人模板 ${suffix}`;
    await page.goto(`/${projectPath}/templates`);
    await expect(page.getByRole('heading', { name: '工作节点模板' })).toBeVisible();
    await page.getByLabel('内部名称').fill(templateName);
    await page.getByLabel('显示名称').fill(templateDisplayName);
    await page.locator('#template-worker-type').selectOption('LEADER');
    await page.getByRole('button', { name: '创建模板' }).click();
    await expect(page.getByText('工作节点模板已创建')).toBeVisible();

    const templateOption = page
      .locator('#revision-template option')
      .filter({ hasText: templateDisplayName });
    await expect(templateOption).toHaveCount(1);
    await page.locator('#revision-template').selectOption((await templateOption.getAttribute('value'))!);
    await page.getByLabel('工作节点规格 JSON').fill(
      JSON.stringify({ runtime: 'qwenpaw', modelProvider: 'deepseek', modelName: 'deepseek-chat' }),
    );
    await page.getByRole('button', { name: '创建版本' }).click();
    await expect(page.getByText(/版本 \d+ 已创建/)).toBeVisible();
    await page.getByRole('button', { name: '发布此版本' }).click();
    await expect(page.getByText(/版本 \d+ 已发布/)).toBeVisible();
    await page
      .locator('article')
      .filter({ hasText: templateDisplayName })
      .getByRole('button', { name: '显式实例化工作节点' })
      .click();
    await expect(page.getByText(/实例化成功 · 工作节点/)).toBeVisible({ timeout: 30_000 });

    await page.goto(`/${projectPath}/workers`);
    const leaderRow = page.locator('tbody tr').filter({ hasText: templateDisplayName });
    await expect(leaderRow).toBeVisible({ timeout: 60_000 });
    await expect(leaderRow).toContainText('负责人工作节点');
    await expect
      .poll(
        async () => {
          await page.reload();
          const refreshedRow = page.locator('tbody tr').filter({ hasText: templateDisplayName });
          try {
            await refreshedRow.waitFor({ state: 'visible', timeout: 15_000 });
          } catch {
            return '';
          }
          return await refreshedRow.innerText();
        },
        { timeout: 300_000, intervals: [5_000] },
      )
      .toContain('就绪');
    const leaderHref = await leaderRow.getByRole('link').getAttribute('href');
    leaderId = leaderHref!.split('/').pop()!;
    await leaderRow.getByRole('link').click();
    leaderDetailVerified = true;
  }
  if (leaderDetailVerified) {
    await expect(page.getByRole('heading', { name: /template-worker-/ })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText('负责人工作节点')).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole('status').filter({ hasText: '就绪' })).toBeVisible({ timeout: 30_000 });
  }
  expect(leaderId).toMatch(/^[0-9a-f-]{36}$/i);

  // Reuse a ready EXECUTOR already registered in this project for task execution.
  await page.goto(`/${projectPath}/workers`);
  const executorRow = page.locator('tbody tr').filter({ hasText: '执行工作节点' }).filter({ hasText: '就绪' }).first();
  await expect(executorRow).toBeVisible({ timeout: 20_000 });
  const executorHref = await executorRow.getByRole('link').getAttribute('href');
  const executorId = executorHref!.split('/').pop()!;

  // 2. Add the leader to team-a and publish/deploy a team revision.
  await page.goto(`/${projectPath}/teams`);
  const teamLink = page.getByRole('link').filter({ hasText: /team-a/ }).first();
  await expect(teamLink).toBeVisible({ timeout: 10_000 });
  await teamLink.click();
  await expect(page.getByRole('heading', { name: /team-a/ })).toBeVisible();
  await page.getByRole('tab', { name: '成员 Agent' }).click();
  const currentMemberRow = page.locator('.member-row').filter({ hasText: leaderId });
  if ((await currentMemberRow.count()) === 0) {
    await page.getByLabel('成员角色').selectOption('LEADER');
    await page.getByLabel('工作节点 / 智能体').selectOption(leaderId);
    await page.getByRole('button', { name: '添加成员' }).dispatchEvent('click');
  }
  await expect(page.getByText(leaderId)).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('.member-row').filter({ hasText: leaderId })).toContainText(
    '负责人工作节点',
  );

  await page.getByRole('tab', { name: '版本与部署' }).click();
  await page.getByLabel('负责人工作节点').selectOption(leaderId);
  const memberValues = await page
    .getByLabel('成员工作节点')
    .locator('option')
    .evaluateAll((options) => options.map((option) => (option as HTMLOptionElement).value));
  expect(memberValues).toContain(leaderId);
  // 仅把本次验证的 Leader 纳入新版本。L5 上可能残留历史成员，
  // 发布校验要求调用方对每个成员 Agent 都具备可见权限。
  await page.getByLabel('成员工作节点').selectOption(leaderId);
  await page.locator('textarea').first().fill('{"mode":"playwright-e2e"}');
  await page.getByRole('button', { name: '创建版本草稿' }).click();
  await expect(page.getByText('Team Revision 草稿已创建')).toBeVisible({ timeout: 15_000 });

  const revisionRow = page.locator('.member-row').filter({ hasText: /版本 \d+/ }).last();
  await expect(revisionRow).toContainText('草稿');
  // L5 的团队详情页存在覆盖表单面板，普通坐标点击会被面板拦截；
  // 直接对已定位的业务按钮派发 click，仍然走 React handler 和真实 API。
  await revisionRow.getByRole('button', { name: '发布' }).dispatchEvent('click');
  await expect(page.getByText('Revision 已发布')).toBeVisible({ timeout: 15_000 });
  await revisionRow.getByRole('button', { name: '选择部署' }).dispatchEvent('click');
  await page.getByRole('button', { name: '提交 Deployment' }).dispatchEvent('click');
  await expect(page.getByText('Deployment 已提交')).toBeVisible({ timeout: 15_000 });
  await expect
    .poll(
      async () => {
        await page.reload();
        await page.getByRole('tab', { name: '版本与部署' }).click();
        const deploymentRow = page.locator('.member-row').filter({ hasText: 'Deployment' }).last();
        return (await deploymentRow.count()) ? await deploymentRow.innerText() : '';
      },
      { timeout: 120_000, intervals: [5_000] },
    )
    .toContain('已完成');

  // 3. Team conversation through the web UI.
  await page.goto(`/${projectPath}/conversations/new`);
  await expect(page.getByRole('heading', { name: '选择对话团队' })).toBeVisible();
  await page.getByRole('button', { name: 'team-a', exact: true }).click();
  await expect(page.getByRole('heading', { name: '工作节点对话' })).toBeVisible({ timeout: 15_000 });
  const conversationText = `Playwright team conversation ${suffix}`;
  const transcript = page.getByRole('region', { name: '对话记录' });
  await page.getByRole('textbox', { name: '输入消息' }).fill(conversationText);
  await page.getByRole('button', { name: '发送' }).click();
  await expect(transcript).toContainText(conversationText, { timeout: 15_000 });
  await expect(transcript).toContainText('CONVERSATION_MOCK_DELTA', { timeout: 15_000 });

  // 4. Create, queue, execute, and inspect a real task.
  await page.goto(`/${projectPath}/tasks/new`);
  await page.reload();
  await expect(page.getByRole('heading', { name: '创建任务' })).toBeVisible();
  const taskTeamOption = page.getByLabel('任务团队').locator('option').filter({ hasText: 'team-a' });
  await expect(taskTeamOption).toHaveCount(1, { timeout: 15_000 });
  await page.getByLabel('任务团队').selectOption((await taskTeamOption.getAttribute('value'))!);
  const taskTitle = `Playwright task execution ${suffix}`;
  await page.getByLabel('任务标题').fill(taskTitle);
  await page.getByLabel('任务说明').fill('Validate team worker execution and result inspection.');
  await page.getByLabel('任务类型').fill('qwenpaw');
  await page.getByLabel('任务工作节点').fill(executorId);
  await page.getByRole('button', { name: '创建任务' }).click();
  await expect(page).toHaveURL(new RegExp(`/${projectPath}/tasks/[0-9a-f-]{36}$`), {
    timeout: 30_000,
  });
  const taskId = new URL(page.url()).pathname.split('/').pop()!;
  expect(taskId).toMatch(/^[0-9a-f-]{36}$/i);
  await expect(page.getByRole('heading', { name: taskTitle })).toBeVisible();
  await expect(page.getByRole('button', { name: '取消任务' })).toBeVisible();
  await expect(page.getByRole('button', { name: '批准任务' })).toBeVisible();
  await page.getByRole('button', { name: '排队执行' }).click();
  await expect(page.getByText('操作已提交')).toBeVisible({ timeout: 15_000 });

  const origin = new URL(page.url()).origin;
  await expect.poll(() => authorization).toMatch(/^Bearer /);
  const browserGet = async (url: string) =>
    page.evaluate(async ({ target, token }) => {
      const response = await fetch(target, {
        headers: { Authorization: `Bearer ${token}` },
      });
      return { ok: response.ok, status: response.status, body: await response.text() };
    }, { target: url, token: authorization.slice('Bearer '.length) });
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
      { timeout: 90_000, intervals: [5_000] },
    )
    .toBe('SUCCEEDED');
  await page.reload();
  await expect(page.locator('.detail-actions [role="status"]')).toHaveText('已完成');
  await expect(page.getByRole('heading', { name: '执行尝试' })).toBeVisible();
  await expect(page.getByText('当前任务尚未产生执行尝试。')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: '执行结果' })).toBeVisible();
  await expect(page.getByText('当前任务尚未产生运行记录。')).toHaveCount(0);
  await expect(page.getByText('已完成').last()).toBeVisible();
  await expect(page.getByRole('heading', { name: '执行总览' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '实时执行过程' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '任务分解' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '决策记录' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '运行结果与成果物' })).toBeVisible();

  // 5. Validate the result manifest and artifact listing through Playwright's authenticated request context.
  const attemptCard = page.locator('article.stack-list__item').filter({ hasText: '分配记录' }).first();
  const attemptId = (await attemptCard.locator('strong').first().innerText()).trim();
  const runLink = page.getByRole('link', { name: '查看运行' }).first();
  const runHref = await runLink.getAttribute('href');
  const runId = new URL(runHref!, new URL(page.url()).origin).searchParams.get('runId')!;
  const resultResponse = await browserGet(
    `${origin}/api/v1/tasks/${taskId}/runs/${runId}/result`,
  );
  expect(resultResponse.ok).toBeTruthy();
  const resultManifest = JSON.parse(resultResponse.body);
  expect(resultManifest.status).toBe('SUCCEEDED');
  expect(Array.isArray(resultManifest.artifacts)).toBeTruthy();

  const artifactResponse = await browserGet(
    `${origin}/api/v1/tasks/${taskId}/attempts/${attemptId}/artifacts`,
  );
  if (!artifactResponse.ok) {
    throw new Error(`artifact query failed: HTTP_${artifactResponse.status} ${artifactResponse.body}`);
  }
  expect(Array.isArray(JSON.parse(artifactResponse.body))).toBeTruthy();
  await page.goto(`/${projectPath}/artifacts`);
  await expect(page.getByRole('heading', { name: '制品', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: '制品保留策略', exact: true })).toBeVisible();
});

test('real OIDC MCP page manages a credential reference and fails closed without a secret', async ({
  page,
}) => {
  const username = process.env.AGENTTEAMS_E2E_QUOTA_ADMIN_USERNAME;
  const password = process.env.AGENTTEAMS_E2E_QUOTA_ADMIN_PASSWORD;
  const oidcPort = process.env.AGENTTEAMS_E2E_OIDC_PORT || '18082';
  test.skip(!username || !password, 'set non-production quota-admin OIDC credentials');

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
  const projectPath = new URL(page.url()).pathname.split('/')[1];
  await page.goto(`/${projectPath}/mcp`);
  await expect(page.getByRole('heading', { name: 'MCP Servers' })).toBeVisible();

  const suffix = Date.now();
  const name = `E2E MCP ${suffix}`;
  await page.getByLabel('名称').fill(name);
  await page.getByLabel('Endpoint').fill('http://127.0.0.1:9/mcp');
  await page.getByLabel('Credential Ref（可选）').fill(`secret://dev/mcp-e2e-${suffix}`);
  await page.getByRole('button', { name: '登记 MCP Server' }).click();
  await expect(page.getByText('MCP Server 已创建')).toBeVisible();

  const server = page.locator('article').filter({ has: page.getByRole('heading', { name }) });
  await expect(server).toContainText('已配置（仅引用）');
  await expect(server).not.toContainText(`secret://dev/mcp-e2e-${suffix}`);
  await server.getByRole('button', { name: '连接测试' }).click();
  await expect(server.getByText(/最近连接测试：/)).toBeVisible({ timeout: 10_000 });
  await server.getByRole('button', { name: '查看 Discovery' }).click();
  await expect(server.getByText(/Discovery：UNAVAILABLE/)).toBeVisible();

  await server.getByRole('button', { name: '编辑' }).click();
  await page.getByLabel('名称').fill(`${name} Updated`);
  await page.getByRole('button', { name: '保存 MCP Server' }).click();
  await expect(page.getByText('MCP Server 已更新')).toBeVisible();
  const updatedServer = page
    .locator('article')
    .filter({ has: page.getByRole('heading', { name: `${name} Updated` }) });
  await expect(updatedServer).toBeVisible();
  await updatedServer.getByRole('button', { name: '删除' }).click();
  await page.getByRole('button', { name: '确认删除 MCP Server' }).click();
  await expect(page.getByText('MCP Server 已删除')).toBeVisible();
  await expect(updatedServer).toHaveCount(0);
});
