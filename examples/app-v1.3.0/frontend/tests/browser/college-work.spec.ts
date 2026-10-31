import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
const fixture=JSON.parse(fs.readFileSync(path.resolve('..','.cache','ui-fixtures.json'),'utf8'));
const accounts:Record<string,any>={};
async function data(request:APIRequestContext,method:string,url:string,token:string,body?:any){const r=await request.fetch('/api'+url,{method,headers:{Authorization:'Bearer '+token},data:body});expect(r.status(),await r.text()).toBe(200);return (await r.json()).data;}
test.beforeAll(async({request})=>{const login=await request.post('/api/auth/login',{data:fixture.admin});expect(login.ok()).toBeTruthy();const admin=(await login.json()).data.token;const prefix='cw_'+randomUUID().slice(0,8),password='Initial9!'+randomUUID(),updated='Updated9!'+randomUUID();for(const [key,role] of [['teacher','counselor'],['student','student'],['leader','leader']]){const user=await data(request,'POST','/users',admin,{username:prefix+'_'+key,name:'新功能验收'+key,password,role,college:prefix,className:prefix+'_class',enabled:true});const first=await request.post('/api/auth/login',{data:{username:user.username,password}});const token=(await first.json()).data.token;await data(request,'POST','/auth/password',token,{oldPassword:password,newPassword:updated});const second=await request.post('/api/auth/login',{data:{username:user.username,password:updated}});accounts[key]=(await second.json()).data;}});
async function enter(page:Page,key:string,section:string){await page.goto('/');await page.evaluate(token=>sessionStorage.setItem('counselor_erp_token',token),accounts[key].token);await page.goto('/#college-'+section);await page.reload();await expect(page.locator('.college-work')).toBeVisible();await expect(page.locator('.el-loading-mask')).toHaveCount(0);}
async function statusFilter(page:Page,label:string){await page.locator('.college-work > .toolbar .el-select').first().click();await page.locator('.el-select-dropdown__item:visible').getByText(label,{exact:true}).click();}
test('汇报文字与PPT下载、个人统计和新闻初稿',async({page,request})=>{
 await data(request,'POST','/college-work/tasks',accounts.teacher.token,{clientId:randomUUID(),title:'汇报验收事项',content:'核验汇报事实',assigneeIds:[accounts.teacher.user.id],needsReview:false,dueAt:'2099-01-01T00:00:00Z'});
 await enter(page,'teacher','tasks');await page.getByRole('button',{name:'我的任务统计',exact:true}).click();const panel=page.getByRole('dialog').filter({has:page.locator('.el-tabs')});await expect(panel.getByText('收到任务 1',{exact:true})).toBeVisible();await panel.getByRole('tab',{name:'任务汇报',exact:true}).click();await panel.getByRole('button',{name:'生成汇报',exact:true}).click();await expect(panel.locator('textarea:visible')).toHaveValue(/有效任务办理记录/);await expect(panel.getByRole('button',{name:'下载综合 PPT'})).toBeVisible();
 const download=page.waitForEvent('download');await panel.getByRole('button',{name:'下载综合 PPT'}).click();const deck=await download;expect(deck.suggestedFilename()).toMatch(/\.pptx$/);const saved=await deck.path();expect(fs.readFileSync(saved!).subarray(0,2).toString()).toBe('PK');
 await panel.getByRole('tab',{name:'新闻初稿',exact:true}).click();await panel.getByPlaceholder('活动时间、地点、参与人员、具体过程和已核实成果').fill('10月9日在教室开展学习交流活动，20名同学参加。');await panel.getByRole('button',{name:'生成新闻初稿',exact:true}).click();await expect(panel.locator('article')).toContainText('20名同学');await expect(panel.getByText(/未分析图片/)).toBeVisible();await page.setViewportSize({width:390,height:960});await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1)).toBe(true);await page.screenshot({path:path.resolve('..','docs','screenshots','v25-news-mobile.png'),fullPage:true});
});
test('辅导员发布任务后自动切换到已发布列表',async({page})=>{
 await enter(page,'teacher','tasks');
 await page.getByRole('button',{name:'安排工作任务',exact:true}).click();
 const dialog=page.getByRole('dialog',{name:'安排工作任务'});
 const title='页面发布验收-'+randomUUID().slice(0,8);
 await dialog.locator('.el-form-item').filter({hasText:'标题'}).locator('input').fill(title);
 await dialog.locator('.el-form-item').filter({hasText:'任务要求 / 内容'}).locator('textarea').fill('确认发布后能在我发布的任务中看到接收人和任务进度');
 await dialog.locator('.el-form-item').filter({hasText:'接收人'}).locator('.el-select').click();
 await page.locator('.el-select-dropdown__item:visible').filter({hasText:accounts.student.user.name}).click();
 const deadline=dialog.locator('.el-form-item').filter({hasText:'任务截止时间'});
 await deadline.getByRole('textbox').fill('2099-01-01 00:00:00');
 await deadline.getByRole('textbox').press('Enter');
 await dialog.getByRole('button',{name:'发布任务',exact:true}).click();
 await expect(page.getByText('任务已发布，已切换到“我发布的”查看进度',{exact:true})).toBeVisible();
 const scope=page.getByRole('radiogroup',{name:'任务范围'});
 await expect(scope.locator('.el-radio-button').filter({hasText:'我发布的'})).toHaveClass(/is-active/);
 await expect(page.locator('.college-work .el-table__row').filter({hasText:title})).toContainText(accounts.student.user.name);
});

test('保存100%进度仍需提交完成、进度图表、下载和打印',async({page,request})=>{
 const [task]=await data(request,'POST','/college-work/tasks',accounts.teacher.token,{clientId:randomUUID(),title:'新功能材料任务',content:'完成提交后下载进度名单',assigneeIds:[accounts.student.user.id],needsReview:false,dueAt:'2099-01-01T00:00:00Z'});
 await enter(page,'student','tasks');const row=page.locator('.college-work .el-table__row').filter({hasText:'新功能材料任务'});await row.getByRole('button',{name:'办理任务',exact:true}).click();const dialog=page.getByRole('dialog',{name:'办理工作任务'});await expect(dialog.getByText('保存进度不会结束任务；全部完成后，请选择“提交完成”。',{exact:true})).toBeVisible();await dialog.locator('textarea').first().fill('材料已整理，待最后提交');await dialog.getByRole('spinbutton').fill('100');await dialog.getByRole('spinbutton').press('Tab');await dialog.getByRole('button',{name:'保存进度',exact:true}).click();await expect(dialog).not.toBeVisible();await expect(row).toContainText('进行中');const progressed=(await data(request,'GET','/college-work/tasks',accounts.student.token)).find((r:any)=>r.id===task.id);expect(progressed.progress).toBe(100);expect(progressed.status).toBe('in_progress');
 await row.getByRole('button',{name:'办理任务',exact:true}).click();await dialog.locator('.task-action-choices .el-radio-button__inner').filter({hasText:/^提交完成$/}).click();await expect(dialog.getByText('提交后任务将直接记为已完成。',{exact:true})).toBeVisible();await dialog.locator('textarea').first().fill('已完成任务材料');await dialog.getByRole('button',{name:'提交完成',exact:true}).click();await expect(dialog).not.toBeVisible();await expect(row).toHaveCount(0);await statusFilter(page,'历史记录');await expect(row).toContainText('已完成');
 await enter(page,'teacher','tasks');await page.getByRole('radiogroup',{name:'任务范围'}).getByText('我发布的',{exact:true}).click();await statusFilter(page,'全部记录');await page.getByPlaceholder('搜索姓名、任务或记录').fill('新功能材料任务');await expect(page.locator('.metrics').getByText('已完成 1',{exact:true})).toBeVisible();await page.getByText('查看筛选结果统计图',{exact:true}).click();await expect(page.getByLabel('班级完成率')).toBeVisible();await expect(page.getByLabel('班级完成率')).toContainText('1/1');const download=page.waitForEvent('download');await page.getByTitle('导出筛选结果',{exact:true}).click();expect((await download).suggestedFilename()).toContain('工作任务');await page.evaluate(()=>{(window as any).__printed=false;window.print=()=>{(window as any).__printed=true;};});await page.getByTitle('打印筛选结果',{exact:true}).click();await expect.poll(()=>page.evaluate(()=>(window as any).__printed)).toBeTruthy();await page.screenshot({path:path.resolve('..','docs','screenshots','v24-task-dashboard.png'),fullPage:true});
});

test('未开始任务不计入当前待办，并可从首页查看后续安排',async({page,request})=>{
 const title='未来开始的辅导员任务';await data(request,'POST','/college-work/tasks',accounts.teacher.token,{clientId:randomUUID(),title,content:'开始后再办理',assigneeIds:[accounts.teacher.user.id],needsReview:true,startsAt:'2098-12-01T00:00:00Z',dueAt:'2099-01-01T00:00:00Z'});
 await enter(page,'teacher','tasks');await expect(page.locator('.college-work .el-table__row').filter({hasText:title})).toHaveCount(0);await page.goto('/#dashboard');await expect(page.locator('.work-dashboard')).toBeVisible();await expect(page.locator('.el-loading-mask')).toHaveCount(0);await expect(page.getByText(title,{exact:true})).toHaveCount(0);await expect(page.locator('.scheduled-tasks')).toContainText('1 项任务尚未开始');await page.getByRole('button',{name:'查看安排',exact:true}).click();const row=page.locator('.college-work .el-table__row').filter({hasText:title});await expect(row).toContainText('未开始');await expect(row.getByRole('button',{name:'办理任务',exact:true})).toHaveCount(0);await row.getByRole('button',{name:'查看详情',exact:true}).click();const dialog=page.getByRole('dialog',{name:'查看事项'});await expect(dialog.getByText(/将于 .* 开始办理/)).toBeVisible();await expect(dialog.getByRole('button',{name:'提交完成',exact:true})).toHaveCount(0);
});
test('宿舍分配预览、实际交接和卫生统计页面',async({page,request})=>{const token=accounts.teacher.token;const bed=await data(request,'POST','/college-work/beds',token,{campus:'主校区',building:'1号楼',room:'101',bed:'1',conditions:'女生',source:'隔离验收台账'});const body={studentIds:[accounts.student.user.id],bedIds:[bed.id],conditions:'女生',reservedUntil:'2099-01-01T00:00:00Z',conditionsVerified:true};expect((await data(request,'POST','/college-work/allocation',token,body)).length).toBe(1);await data(request,'POST','/college-work/allocation',token,{...body,confirm:true});const [h]=await data(request,'GET','/college-work/housing',token);await data(request,'POST','/college-work/housing/'+h.id,token,{action:'confirm_handover',reason:'已完成真实交接验收',version:h.version,actualHandover:true,handoverReference:'CW-001'});await data(request,'POST','/college-work/hygiene',token,{clientId:randomUUID(),title:'卫生检查验收',bedId:bed.id,score:55,checkedAt:'2026-10-09T01:00:00Z',content:'整理后复查'});await enter(page,'teacher','dorms');await expect(page.locator('.college-work .el-table__row').getByText('入住',{exact:true})).toBeVisible();await expect(page.getByText(/均分 55.0/)).toBeVisible();await expect(page.getByRole('button',{name:'一键分配',exact:true})).toBeVisible();await page.screenshot({path:path.resolve('..','docs','screenshots','v24-dorm-desktop.png'),fullPage:true});for(const width of [390,768,1440]){await page.setViewportSize({width,height:960});await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1)).toBe(true);await page.screenshot({path:path.resolve('..','docs','screenshots',`v24-dorm-${width}.png`),fullPage:true});}});
test('谈话与团党记录、提醒设置及受限权限',async({page,request})=>{await data(request,'POST','/college-work/care',accounts.teacher.token,{clientId:randomUUID(),title:'定期关怀验收',studentId:accounts.student.user.id,type:'followup',level:'一般',content:'必要状态观察',dueAt:'2099-01-01T00:00:00Z'});await data(request,'POST','/college-work/party',accounts.teacher.token,{clientId:randomUUID(),title:'入党发展验收',studentId:accounts.student.user.id,track:'party',basis:'校内发展程序',dueAt:'2099-01-01T00:00:00Z'});await enter(page,'teacher','care');await expect(page.getByText('定期关怀验收',{exact:true})).toBeVisible();await page.getByRole('button',{name:'任务提醒设置',exact:true}).click();const settings=page.getByRole('dialog',{name:'任务提醒设置'});await settings.getByRole('spinbutton').first().fill('10');await settings.getByRole('spinbutton').first().press('Tab');await settings.getByRole('button',{name:'保存',exact:true}).click();await expect(settings).not.toBeVisible();expect((await data(request,'GET','/college-work/preferences',accounts.teacher.token)).interval).toBe(10);await enter(page,'leader','party');await expect(page.getByText(/入党 · 入党申请/)).toBeVisible();expect((await data(request,'GET','/college-work/care',accounts.leader.token)).length).toBe(0);await page.locator('.sidebar').getByRole('textbox',{name:'查找功能'}).fill('AI 办公');await page.locator('.sidebar').getByRole('button',{name:'AI 办公',exact:true}).click();await page.getByRole('button',{name:/文本优化/}).click();await expect(page.getByPlaceholder(/粘贴原文/)).toBeVisible();});

test('首页工作跟进待办跳转后定位并高亮对应记录', async ({page,request}) => {
  const title = '首页待办定位验收-' + randomUUID().slice(0,8);
  const followUpAt = '2099-01-01T12:34:00+08:00';
  const log = await data(request,'POST','/worklogs',accounts.teacher.token,{
    type:'talk',title,content:'仅用于验证首页待办定位与卡片高亮。',followUpAt,
  });

  await enter(page,'teacher','tasks');
  await page.goto('/#dashboard');
  await expect(page.locator('.work-dashboard')).toBeVisible();
  await expect(page.locator('.el-loading-mask')).toHaveCount(0);

  const todo = page.locator('.todo-list li').filter({hasText:'工作跟进'});
  await expect(todo).toHaveCount(1);
  await todo.getByRole('button',{name:'查看事项',exact:true}).click();

  const target = page.locator(`.record-card.record-target[data-worklog-id="${log.id}"]`);
  await expect(target).toBeVisible();
  await expect(target).toContainText(title);
  await expect(target).toHaveCSS('outline-width','3px');
  await expect(target).toHaveCSS('outline-style','solid');
});
