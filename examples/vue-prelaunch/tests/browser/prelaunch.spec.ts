import { test, expect, type Page } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';

const evidence = path.resolve('..', 'output', 'playwright', 'prelaunch-20261027');
async function signIn(page: Page, role: 'admin' | 'counselor' | 'student') {
  const fixture = JSON.parse(fs.readFileSync(path.resolve('..', '.cache', 'ui-fixtures.json'), 'utf8'));
  await page.goto('/');
  await page.getByLabel('账号', { exact: true }).fill(fixture[role].username);
  await page.getByLabel('密码', { exact: true }).fill(fixture[role].password);
  await page.getByRole('button', { name: '进入系统', exact: true }).click();
  await expect(page.locator('.work-dashboard')).toBeVisible();
  await expect(page.locator('.el-loading-mask')).toHaveCount(0);
}

test('手机登录首屏直接看到账密输入和登录按钮', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  for (const width of [360, 390, 600]) {
    await page.setViewportSize({ width, height: 800 });
    await page.goto('/');
    await expect(page.getByLabel('密码', { exact: true })).toBeInViewport();
    await expect(page.getByRole('button', { name: '进入系统', exact: true })).toBeInViewport();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
    await page.screenshot({ path: path.join(evidence, `login-${width}.png`), fullPage: true });
  }
});

test('功能搜索显示全部授权入口并支持中文关键词、键盘与空结果', async ({ page }) => {
  await signIn(page, 'counselor');
  await page.keyboard.press('Control+k');
  const dialog = page.getByRole('dialog', { name: '查找功能', exact: true });
  const input = dialog.getByRole('combobox');
  await expect(input).toBeFocused();
  await expect(dialog.getByRole('option', { name: /个人与设置/ })).toHaveCount(1);
  await expect(dialog.getByRole('option', { name: /账号与组织管理/ })).toHaveCount(0);
  await input.fill('查学生');
  await expect(dialog.getByRole('option')).toHaveCount(1);
  await input.press('Enter');
  await expect(page.locator('.page-title h1')).toHaveText('学生名单与档案');
  await page.keyboard.press('Control+k');
  await input.fill('不存在的功能xyz');
  await expect(dialog.getByText(/没有找到相关功能/)).toBeVisible();
  await input.press('Enter');
  await expect(dialog).toBeVisible();
  await input.fill('');
  await input.press('ArrowDown');
  await input.press('Enter');
  await expect(page.locator('.page-title h1')).toHaveText('内部聊天');
  await page.setViewportSize({ width: 360, height: 640 });
  await page.keyboard.press('Control+k');
  await expect(input).toBeFocused();
  await expect(input).toBeInViewport();
  await expect(dialog.getByText('Esc 关闭', { exact: true })).toBeInViewport();
  await page.screenshot({ path: path.join(evidence, 'search-mobile.png'), fullPage: true });
  await input.press('Escape');
  await expect(dialog).not.toBeVisible();
});

for (const role of ['admin', 'counselor', 'student'] as const) {
  test(`${role}全部授权页面在电脑和手机可打开且无横向溢出`, async ({ page }) => {
    test.setTimeout(120000);
    const errors: string[] = [], forbidden: string[] = [];
    page.on('pageerror', e => errors.push(e.message));
    page.on('response', r => { if (r.status() >= 400 && r.url().includes('/api/')) forbidden.push(`${r.status()} ${new URL(r.url()).pathname}`); });
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.setViewportSize({ width: 1440, height: 1000 });
    await signIn(page, role);
    await page.locator('.more-business-toggle').click();
    const labels = await page.locator('.sidebar nav button:not(.more-business-toggle) > span:not(.nav-count)').allTextContents();
    expect(labels.length).toBeGreaterThan(10);
    for (const label of labels) {
      await page.setViewportSize({ width: 1440, height: 1000 });
      await page.locator('.sidebar nav button').filter({ has: page.getByText(label.trim(), { exact: true }) }).click();
      await expect(page.locator('.page-load-status')).toHaveCount(0);
      await expect(page.locator('.el-loading-mask')).toHaveCount(0);
      await expect(page.locator('.content')).toContainText(label.trim() === '工作台' ? '常用入口' : label.trim());
      for (const width of [360, 768, 1440]) {
        await page.setViewportSize({ width, height: 900 });
        await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth - innerWidth), `${role}: ${label} at ${width}`).toBeLessThanOrEqual(1);
      }
    }
    expect(forbidden).toEqual([]);
    expect(errors).toEqual([]);
  });
}

test('首页分类与长标题可读，打印不保留侧栏空白', async ({ page }) => {
  await signIn(page, 'counselor');
  for (const width of [360, 390, 768, 1440]) {
    await page.setViewportSize({ width, height: 1000 });
    const clipped = await page.locator('.queue-switch button, .quick-action-copy strong').evaluateAll(items => items.filter(item => item.scrollWidth > item.clientWidth + 1).map(item => item.textContent));
    expect(clipped, `clipped controls at ${width}`).toEqual([]);
    await page.screenshot({ path: path.join(evidence, `dashboard-${width}.png`), fullPage: true });
  }
  await page.emulateMedia({ media: 'print' });
  expect(await page.locator('.main-shell').evaluate(el => getComputedStyle(el).marginLeft)).toBe('0px');
});

test('会话检查断网保留登录并提供重试，恢复后直接回到工作台', async ({ page }) => {
  await signIn(page, 'counselor');
  await page.route('**/api/auth/me', route => route.abort('internetdisconnected'));
  await page.reload();
  await expect(page.getByRole('button', { name: '重新连接', exact: true })).toBeVisible();
  expect(await page.evaluate(() => !!sessionStorage.getItem('counselor_erp_token'))).toBe(true);
  await page.unroute('**/api/auth/me');
  await page.getByRole('button', { name: '重新连接', exact: true }).click();
  await expect(page.locator('.work-dashboard')).toBeVisible();
});

test('首页任务加载失败显示重试，恢复后显示真实统计', async ({ page }) => {
  await page.route('**/api/college-work/tasks', route => route.fulfill({ status: 503, json: { code: 503, message: '测试：任务服务暂不可用' } }));
  await signIn(page, 'counselor');
  await expect(page.locator('.work-dashboard')).toContainText('任务统计暂未加载');
  await expect(page.locator('.progress-section .rate')).toHaveCount(0);
  await page.unroute('**/api/college-work/tasks');
  await page.locator('.load-retry').getByRole('button', { name: '重新加载', exact: true }).click();
  await expect(page.locator('.progress-section .rate')).toBeVisible();
});

test('提醒设置读取失败不能保存默认值，重试后保留已保存偏好', async ({ page }) => {
  await signIn(page, 'counselor');
  const original = await page.evaluate(async () => {
    const headers = { Authorization: 'Bearer ' + sessionStorage.getItem('counselor_erp_token'), 'Content-Type': 'application/json' };
    const saved = await (await fetch('/api/college-work/preferences', { headers })).json();
    const seeded = await fetch('/api/college-work/preferences', { method: 'POST', headers, body: JSON.stringify({ enabled: false, interval: 50, leadHours: 12 }) });
    if (!seeded.ok) throw new Error('Failed to prepare reminder preferences');
    return saved.data;
  });
  try {
    await page.route('**/api/college-work/preferences', route => route.fulfill({ status: 503, json: { code: 503, message: '测试：设置读取失败' } }));
    await page.getByRole('button', { name: '任务提醒设置', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '任务提醒设置', exact: true });
    await expect(dialog).toContainText('设置读取失败');
    await expect(dialog.getByRole('button', { name: '保存', exact: true })).toBeDisabled();
    await expect(dialog.getByRole('spinbutton')).toHaveCount(0);
    await page.unroute('**/api/college-work/preferences');
    await dialog.getByRole('button', { name: '重新读取', exact: true }).click();
    await expect(dialog.getByRole('spinbutton').first()).toHaveValue('50');
    await expect(dialog.getByRole('spinbutton').nth(1)).toHaveValue('12');
    await expect(dialog.getByRole('switch')).not.toBeChecked();
    const saved = page.waitForResponse(r => r.url().endsWith('/college-work/preferences') && r.request().method() === 'POST');
    await dialog.getByRole('button', { name: '保存', exact: true }).click();
    expect((await (await saved).json()).data).toMatchObject({ enabled: false, interval: 50, leadHours: 12 });
    await expect(dialog).not.toBeVisible();
  } finally {
    await page.unroute('**/api/college-work/preferences');
    await page.evaluate(async prefs => {
      const response = await fetch('/api/college-work/preferences', { method: 'POST', headers: { Authorization: 'Bearer ' + sessionStorage.getItem('counselor_erp_token'), 'Content-Type': 'application/json' }, body: JSON.stringify(prefs) });
      if (!response.ok) throw new Error('Failed to restore reminder preferences');
    }, original);
  }
});

test('AI状态失败可重试，写作与润色断网保留正确场景及原文', async ({ page }) => {
  await signIn(page, 'counselor');
  await page.route('**/api/ai/status', route => route.abort('internetdisconnected'));
  await page.locator('.sidebar').getByRole('button', { name: 'AI 办公', exact: true }).click();
  await expect(page.getByText('服务状态读取失败，可重试', { exact: true })).toBeVisible();
  await page.unroute('**/api/ai/status');
  await page.getByRole('button', { name: '重试服务状态', exact: true }).click();
  await expect(page.getByText('当前使用本地模板，未调用外部模型', { exact: true })).toBeVisible();
  await page.route('**/api/ai/assist', route => route.abort('internetdisconnected'));
  await page.getByRole('button', { name: /文本写作 根据/ }).click();
  await page.getByPlaceholder('给这次整理起一个清晰的名称').fill('主题班会工作说明');
  await page.getByRole('button', { name: '生成建议', exact: true }).click();
  await expect(page.locator('.ai-result h3')).toHaveText('文本写作框架');
  await page.getByRole('button', { name: /文本优化 纠错/ }).click();
  const original = '今天完成主题班会，后续继续核实参加情况。';
  await page.getByPlaceholder(/粘贴原文/).fill(original);
  await page.getByRole('button', { name: '生成建议', exact: true }).click();
  await expect(page.locator('.ai-result h3')).toHaveText('原文已保留，尚未优化');
  await expect(page.locator('.ai-result .ai-output')).toHaveText(original);
  await expect(page.locator('.ai-result')).not.toContainText('制度问答');
});
