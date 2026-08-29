import { expect, test } from '@playwright/test';

test('public console entry exposes the login action', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('link', { name: '登录' })).toBeVisible();
});
