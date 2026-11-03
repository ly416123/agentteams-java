import { expect, test } from '@playwright/test';

test('horizontal console navigation stays visible and scrollable on narrow screens', async ({
  page,
}) => {
  await page.goto('/');
  await page.evaluate(() => {
    document.body.innerHTML = `
      <div class="console-shell">
        <aside class="sidebar">
          <a class="brand"><span class="brand-mark">A</span><span class="sidebar-brand-name">AgentTeams</span></a>
          <p class="sidebar-caption">OPERATIONS CONSOLE</p>
          <nav class="main-nav" aria-label="主导航">
            <details class="nav-group"><summary><span>工作区</span></summary><div class="nav-group-items"><a class="nav-item" href="#overview">概览</a></div></details>
            <details class="nav-group"><summary><span>智能体资源</span></summary><div class="nav-group-items"><a class="nav-item" href="#templates">模板</a></div></details>
            <details class="nav-group"><summary><span>治理与监控</span></summary><div class="nav-group-items"><a class="nav-item" href="#usage">用量</a></div></details>
            <details class="nav-group nav-group--settings"><summary><span>系统设置</span></summary><div class="nav-group-items"><a class="nav-item" href="#identity">身份与权限</a></div></details>
          </nav>
          <div class="sidebar-footer">Control Plane 已连接</div>
        </aside>
        <div class="console-body"><header class="topbar">顶部工具栏</header><main>页面</main></div>
      </div>`;
  });

  const sidebar = page.locator('.console-shell .sidebar');
  const mainNav = page.locator('.console-shell .main-nav');
  for (const width of [1440, 800, 390, 320]) {
    await page.setViewportSize({ width, height: 844 });
    await expect(sidebar, `sidebar at ${width}px`).toBeVisible();
    await expect(mainNav, `main navigation at ${width}px`).toBeVisible();
    await expect
      .poll(async () => mainNav.evaluate((nav) => getComputedStyle(nav).display))
      .toBe('flex');
    if (width <= 760) {
      await expect
        .poll(async () => mainNav.evaluate((nav) => getComputedStyle(nav).overflowX))
        .toBe('auto');
    }
  }

  await page.getByText('智能体资源').click();
  await expect(page.getByRole('link', { name: '模板' })).toBeVisible();
});
