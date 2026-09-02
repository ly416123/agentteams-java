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

  await expect(page).toHaveURL(/:30080\/console$/, { timeout: 30_000 });
  await page.goto('/settings/organizations');
  await expect(page.getByRole('heading', { name: 'Organization 与 Tenant' })).toBeVisible();

  const suffix = Date.now();
  const organizationName = `E2E Organization ${suffix}`;
  const tenantName = `E2E Tenant ${suffix}`;
  await page.getByLabel('组织名称').fill(organizationName);
  await page.getByRole('button', { name: '创建 Organization' }).click();
  await expect(page.getByText('Organization 已创建')).toBeVisible();

  const organization = page.locator('article').filter({ hasText: organizationName });
  await expect(organization).toBeVisible();
  await page.getByLabel('Tenant 名称').fill(tenantName);
  await page.getByRole('button', { name: '创建 Tenant' }).click();
  await expect(page.getByText('Tenant 已创建')).toBeVisible();
  await expect(organization).toContainText(tenantName);

  await organization.getByRole('button', { name: '暂停 Tenant' }).click();
  await expect(page.getByText('Tenant 状态已更新')).toBeVisible();
  await expect(organization).toContainText('SUSPENDED');
  await organization.getByRole('button', { name: '恢复 Tenant' }).click();
  await expect(page.getByText('Tenant 状态已更新')).toBeVisible();
  await expect(organization).toContainText('ACTIVE');

  await organization.getByRole('button', { name: '暂停 Organization' }).click();
  await expect(page.getByText('Organization 状态已更新')).toBeVisible();
  await expect(organization).toContainText('SUSPENDED');
  await organization.getByRole('button', { name: '恢复 Organization' }).click();
  await expect(page.getByText('Organization 状态已更新')).toBeVisible();
  await expect(organization).toContainText('ACTIVE');
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

  await expect(page).toHaveURL(/\/console$/, { timeout: 30_000 });
  const suffix = Date.now();
  const organizationName = `E2E Provisioning Organization ${suffix}`;
  const integrationName = `E2E Provisioning Integration ${suffix}`;
  await page.goto('/settings/organizations');
  await page.getByLabel('组织名称').fill(organizationName);
  await page.getByRole('button', { name: '创建 Organization' }).click();
  await expect(page.getByText('Organization 已创建')).toBeVisible();

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
  await expect(page).toHaveURL(/\/console$/, { timeout: 30_000 });

  const suffix = Date.now();
  const organizationName = `E2E Credential Organization ${suffix}`;
  const integrationName = `E2E Credential Integration ${suffix}`;
  await page.goto('/settings/organizations');
  await page.getByLabel('组织名称').fill(organizationName);
  await page.getByRole('button', { name: '创建 Organization' }).click();
  await expect(page.getByText('Organization 已创建')).toBeVisible();

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
  await expect(page.getByText('OWNER')).toBeVisible();
  await expect(page.getByText('PROJECT_READ').first()).toBeVisible();
});

test('real OIDC project page creates a project in the current tenant', async ({ page }) => {
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
  await page.goto('/settings/projects');
  await expect(page.getByRole('heading', { name: 'Project 管理' })).toBeVisible();

  const name = `E2E Project ${Date.now()}`;
  await page.getByLabel('Project 名称').fill(name);
  await page.getByRole('button', { name: '创建 Project' }).click();
  await expect(page.getByText('Project 已创建')).toBeVisible();
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
  await expect(page.getByRole('heading', { name: 'Teams' })).toBeVisible();
  await page.getByRole('link', { name: '创建 Team' }).first().click();
  await expect(page.getByRole('heading', { name: '创建 Team' })).toBeVisible();

  const displayName = `E2E Team ${Date.now()}`;
  await page.getByLabel('显示名称').fill(displayName);
  await page.getByRole('button', { name: '下一步' }).click();
  await page.getByRole('button', { name: '下一步' }).click();
  await page.getByRole('button', { name: '下一步' }).click();
  await page.getByRole('button', { name: '创建 Team' }).click();

  await expect(page).toHaveURL(new RegExp(`/${projectPath}/teams/[^/]+$`));
  await expect(page.getByRole('heading', { name: displayName })).toBeVisible();
  await page.getByRole('tab', { name: '版本与部署' }).click();
  await expect(page.getByText('发布不会自动部署 Worker')).toBeVisible();

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
  await expect(page.getByRole('heading', { name: 'Worker Templates' })).toBeVisible();
  await page.getByLabel('内部名称').fill(templateName);
  await page.getByLabel('显示名称').fill(displayName);
  await page.getByRole('button', { name: '创建模板' }).click();
  await expect(page.getByText('Worker Template 已创建')).toBeVisible();

  const templateOption = page.locator('#revision-template option').filter({ hasText: displayName });
  await expect(templateOption).toHaveCount(1);
  await page
    .locator('#revision-template')
    .selectOption((await templateOption.getAttribute('value'))!);
  await page.getByLabel('Worker Spec JSON').fill(
    JSON.stringify({
      runtime: 'qwenpaw',
      modelProvider: 'deepseek',
      modelName: 'deepseek-chat',
    }),
  );
  await page.getByRole('button', { name: '创建 Revision' }).click();
  await expect(page.getByText(/Revision \d+ 已创建/)).toBeVisible();
  await page.getByRole('button', { name: '发布此 Revision' }).click();
  await expect(page.getByText(/Revision \d+ 已发布/)).toBeVisible();

  const templateCard = page.locator('article').filter({ hasText: displayName });
  await expect(templateCard.getByRole('button', { name: '显式实例化 Worker' })).toBeVisible();
  await templateCard.getByRole('button', { name: '显式实例化 Worker' }).click();
  await expect(page.getByText(/已创建实例 .*，等待 Worker Ready/)).toBeVisible({ timeout: 30_000 });

  await page.goto(`/${projectPath}/workers`);
  await expect(page.getByRole('heading', { name: 'Workers' })).toBeVisible();
  const workerLink = page.getByRole('link', { name: /template-worker-/ }).first();
  await expect(workerLink).toBeVisible({ timeout: 30_000 });
  await workerLink.click();
  await expect(page.getByRole('heading', { name: /template-worker-/ })).toBeVisible();
  await expect(page.getByRole('heading', { name: '操作记录' })).toBeVisible();
});

test('real OIDC AgentSpec page creates publishes and deactivates independently', async ({
  page,
}) => {
  const username = process.env.AGENTTEAMS_E2E_AGENT_SPEC_USERNAME;
  const password = process.env.AGENTTEAMS_E2E_AGENT_SPEC_PASSWORD;
  const oidcPort = process.env.AGENTTEAMS_E2E_OIDC_PORT || '18082';
  test.skip(!username || !password, 'set non-production AgentSpec OIDC credentials');

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
  await expect(page.getByRole('heading', { name: 'Agent Specs' })).toBeVisible();

  const name = `E2E AgentSpec ${Date.now()}`;
  await page.getByLabel('内部名称').fill(name);
  await page.getByLabel('Runtime').fill('qwenpaw');
  await page.getByLabel('Model Provider').fill('deepseek');
  await page.getByLabel('Model Name').fill('deepseek-chat');
  await page.getByLabel('Spec JSON').fill('{"mode":"safe"}');
  await page.getByRole('button', { name: '创建 AgentSpec' }).click();
  await expect(page.getByText('AgentSpec 已创建')).toBeVisible();

  const card = page.locator('article').filter({ hasText: name });
  await expect(card).toBeVisible();
  await expect(card.getByText('DRAFT')).toBeVisible();
  await card.getByRole('button', { name: '发布' }).click();
  await expect(page.getByText('AgentSpec 已发布')).toBeVisible();
  await expect(card.getByText('PUBLISHED')).toBeVisible();
  await card.getByRole('button', { name: '停用' }).click();
  await expect(page.getByText('AgentSpec 已停用')).toBeVisible();
  await expect(card.getByText('DISABLED')).toBeVisible();
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
