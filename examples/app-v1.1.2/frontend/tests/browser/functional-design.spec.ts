import { test, expect, type Page } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';

// Use real isolated login, with synthetic responses for precise UI boundary cases.
async function signIn(page: Page) {
  const fixture = JSON.parse(fs.readFileSync(path.resolve('..', '.cache/ui-fixtures.json'), 'utf8'));
  const login = page.waitForResponse(r => r.url().endsWith('/api/auth/login') && r.request().method() === 'POST');
  await page.goto('/');
  await page.getByLabel('账号', { exact: true }).fill(fixture.counselor.username);
  await page.getByLabel('密码', { exact: true }).fill(fixture.counselor.password);
  await page.getByRole('button', { name: '进入系统', exact: true }).click();
  const user = (await (await login).json()).data.user;
  await expect(page.locator('.work-dashboard')).toBeVisible();
  return user;
}
const respond = (route: any, data: unknown) => route.fulfill({ json: { code: 0, data } });
async function silenceReminders(page: Page) { await page.route('**/api/college-work/reminders', route => respond(route, [])); }
async function goTasks(page: Page) {
  await page.locator('.sidebar').getByRole('button', { name: '工作任务', exact: true }).click();
  await expect(page.locator('.college-work')).toBeVisible();
  await expect(page.locator('.el-loading-mask')).toHaveCount(0);
}
const task = (id: string, user: any, extra: Record<string, unknown> = {}) => ({ id, title: `【演示】任务 ${id}`, content: '核对材料后提交办理结果', status: 'pending', assigneeId: user.id, assigneeName: '演示辅导员', ownerId: 'publisher', version: 2, startsAt: '2020-01-01T00:00:00Z', dueAt: '2099-01-01T00:00:00Z', progress: 0, history: [], attachmentIds: [], ...extra });

test('弹窗和站内提醒定位同一具体任务，关闭后可以再次打开', async ({ page }) => {
  let user: any;
  await page.route('**/api/auth/login', async route => { const response = await route.fetch(); const body = await response.json(); user = body.data.user; await route.fulfill({ response }); });
  await page.route('**/api/college-work/tasks', route => respond(route, [task('reminder-target', user)]));
  await page.route('**/api/college-work/reminders', route => respond(route, [{ id: 'reminder-target', title: '【演示】任务 reminder-target', module: 'tasks', dueAt: '2099-01-01T00:00:00Z' }]));
  await page.route('**/api/messages', route => respond(route, [{ id: 'reminder-message', title: '【演示】已提交的任务提醒', content: '请查看最新状态', collegeModule: 'tasks', collegeRecordId: 'reminder-target', resolved: true, createdAt: '2026-10-31T10:00:00Z' }]));
  await signIn(page);
  await page.getByRole('dialog', { name: '待办提醒', exact: true }).getByRole('button', { name: '查看：', exact: false }).click();
  const dialog = page.getByRole('dialog', { name: '办理工作任务', exact: true });
  await expect(dialog.locator('.action-summary')).toContainText('reminder-target');
  await dialog.getByRole('button', { name: '关闭', exact: true }).click();
  // Reopen through an inbox link to the same record while task identity is retained.
  await page.getByRole('button', { name: '查看站内提醒', exact: true }).click();
  const message = page.locator('.message-item').filter({ hasText: '【演示】已提交的任务提醒' });
  await expect(message).toContainText('此条提醒已结束');
  await expect(message).not.toContainText('事项已完成');
  await message.getByRole('button', { name: '查看事项', exact: true }).click();
  await expect(dialog.locator('.action-summary')).toContainText('reminder-target');
});

test('手机批量审核可全选，筛选后仅审核当前选中的可审核任务', async ({ page }) => {
  await silenceReminders(page);
  const user = await signIn(page);
  const records = [task('A', user, { ownerId: user.id, assigneeId: 'student-A', status: 'submitted', progress: 100 }), task('B', user, { ownerId: user.id, assigneeId: 'student-B', status: 'submitted', progress: 100 }), task('waiting', user)];
  await page.route('**/api/college-work/tasks', route => respond(route, records));
  await goTasks(page);
  await page.getByText('全部可见任务', { exact: true }).click();
  await expect(page.getByRole('radio', { name: '全部可见任务', exact: true })).toBeChecked();
  await page.getByRole('button', { name: '清空筛选', exact: true }).click();
  await page.setViewportSize({ width: 390, height: 900 });
  const all = page.getByRole('checkbox', { name: /全选当前待审核/ });
  await page.getByText(/全选当前待审核/).click();
  await expect(all).toBeChecked();
  await expect(page.getByRole('button', { name: '审核通过选中 2 项', exact: true })).toBeEnabled();
  await expect(page.locator('.mobile-task-list').getByRole('checkbox', { name: '选中审核', exact: true })).toHaveCount(2);
  await page.getByRole('textbox', { name: '搜索姓名、任务或记录', exact: true }).fill('任务 A');
  await expect(page.getByRole('button', { name: '审核通过选中 1 项', exact: true })).toBeEnabled();
  let selected: any;
  await page.route('**/api/college-work/review', async route => { selected = route.request().postDataJSON(); await respond(route, []); });
  await page.getByRole('button', { name: '退回并填写批注', exact: true }).click();
  const prompt = page.getByRole('dialog', { name: '审核选中的 1 项任务', exact: true });
  await prompt.getByRole('textbox').fill('请补齐签名后重新提交');
  await prompt.getByRole('button', { name: '保存审核', exact: true }).click();
  await expect.poll(() => selected?.records?.map((r: any) => r.id)).toEqual(['A']);
  expect(selected.reason).toBe('请补齐签名后重新提交');
  expect(selected.action).toBe('return');
  await page.getByText('审核已保存', { exact: true }).waitFor();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  await page.screenshot({ path: path.resolve('..', 'output/playwright/v1.1.2-mobile-review.png'), fullPage: true, animations: 'disabled' });
});

test('个人统计失败时不显示假零或旧数字，重试恢复真实结果', async ({ page }) => {
  await silenceReminders(page);
  await signIn(page); await goTasks(page);
  let failure = true;
  await page.route('**/api/college-content/statistics', route => failure ? route.abort() : respond(route, { total: 3, completed: 1, submitted: 1, overdue: 0, completionRate: 33, tasks: [] }));
  await page.getByRole('button', { name: '我的任务统计', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '我的任务统计', exact: true });
  await expect(dialog.getByRole('button', { name: '重新读取统计', exact: true })).toBeVisible();
  await expect(dialog.locator('.statline')).toHaveCount(0);
  failure = false;
  await dialog.getByRole('button', { name: '重新读取统计', exact: true }).click();
  await expect(dialog.locator('.statline')).toContainText('收到任务 3');
  await dialog.locator('.el-dialog__headerbtn').click();
  failure = true;
  await page.getByRole('button', { name: '我的任务统计', exact: true }).click();
  await expect(dialog.getByRole('button', { name: '重新读取统计', exact: true })).toBeVisible();
  await expect(dialog.locator('.statline')).toHaveCount(0);
  await page.screenshot({ path: path.resolve('..', 'output/playwright/v1.1.2-statistics-retry.png'), fullPage: true, animations: 'disabled' });
});

test('任务截止后页面自动变为逾期，已提交任务不误计入逾期', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-10-31T04:00:00Z') });
  await silenceReminders(page);
  const user = await signIn(page);
  const records = [task('deadline', user, { dueAt: '2026-10-31T04:00:30Z', overdue: false }), task('submitted', user, { dueAt: '2026-10-31T03:00:00Z', status: 'submitted', overdue: false })];
  await page.route('**/api/college-work/tasks', route => respond(route, records));
  await goTasks(page);
  await page.getByRole('button', { name: '清空筛选', exact: true }).click();
  await expect(page.locator('.metrics')).toContainText('逾期未完成 0');
  await page.clock.fastForward(61000);
  await expect(page.locator('.metrics')).toContainText('逾期未完成 1');
  await expect(page.locator('.desktop-task-table .el-table__row').filter({ hasText: '任务 deadline' })).toContainText('逾期');
  await expect(page.locator('.desktop-task-table .el-table__row').filter({ hasText: '任务 submitted' })).not.toContainText('逾期');
});

test('入团入党更新阶段不能沿用过去的下次跟进时间', async ({ page }) => {
  await silenceReminders(page);
  const user = await signIn(page);
  await page.route('**/api/college-work/party', route => respond(route, [{ id: 'party-followup', title: '【演示】入团培养跟进', studentName: '演示学生', studentId: 'synthetic-student', ownerId: user.id, status: 'open', track: 'league', stage: 0, dueAt: '2020-01-01T00:00:00Z', basis: '培养材料', version: 2 }]));
  await page.locator('.sidebar').getByRole('button', { name: '更多业务', exact: true }).click();
  await page.locator('.sidebar').getByRole('button', { name: '入团入党', exact: true }).click();
  await page.locator('.college-work .el-table__row').getByRole('button', { name: '办理', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '办理事项', exact: true });
  await dialog.getByRole('button', { name: '保存', exact: true }).click();
  await expect(page.getByText('下次跟进时间应晚于现在；无需继续跟进时请选择结束跟进', { exact: true })).toBeVisible();
  await expect(dialog).toBeVisible();
});
