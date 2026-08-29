import { existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { chromium } from '@playwright/test';

const args = globalThis.process.argv.slice(2);
const listingOnly = args.includes('--list');
if (!listingOnly && !existsSync(chromium.executablePath())) {
  globalThis.console.error('Playwright Chromium 未安装，请先运行 npm run e2e:install。');
  globalThis.process.exit(1);
}

const executable = globalThis.process.platform === 'win32' ? 'playwright.cmd' : 'playwright';
const result = spawnSync(executable, ['test', ...args], { stdio: 'inherit' });
globalThis.process.exit(result.status ?? 1);
