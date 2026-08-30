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
  test.skip(!username || !password, 'set AGENTTEAMS_E2E_USERNAME and AGENTTEAMS_E2E_PASSWORD');

  await page.goto('/');
  await page.getByRole('link', { name: '进入控制台' }).click();
  await expect(page).toHaveURL(/\/login$/);
  await page.getByRole('button', { name: '使用组织账号登录' }).click();
  await expect(page).toHaveURL(/:30082\/realms\/agentteams\/protocol\/openid-connect\/auth/);

  await page.locator('#username').fill(username!);
  await page.locator('#password').fill(password!);
  await page.locator('#kc-login').click();

  await expect(page).toHaveURL(/:30080\/[^/]+\/overview$/, { timeout: 30_000 });
  await expect(page.getByText('Control Plane 已连接')).toBeVisible();
  await expect(page).not.toHaveURL(/\/login$/);
});
