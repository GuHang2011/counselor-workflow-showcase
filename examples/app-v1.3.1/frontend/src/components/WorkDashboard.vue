<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { api, getToken } from '../api';

type Row = Record<string, any>;
type Queue = 'open' | 'today' | 'week' | 'overdue' | 'review';
type Todo = { id: string; recordId: string; title: string; dueAt?: string; route: string; target?: string; kind: string; action: string; review: boolean; person?: string };
const props = defineProps<{ user: Row; refreshKey?: number }>();
const emit = defineEmits<{ (event: 'navigate', route: string, filter?: string, recordId?: string): void }>();
const manager = computed(() => ['admin', 'leader'].includes(props.user.role));
const counselor = computed(() => ['counselor', 'mentor'].includes(props.user.role));
const tasks = ref<Row[]>([]), board = ref<Row[]>([]), care = ref<Row[]>([]), people = ref<Row[]>([]);
const loading = ref(false), errors = ref<string[]>([]), tasksLoaded = ref(false);
const queue = ref<Queue>('open');
const search = ref(''), visibleLimit = ref(8);
const searchTerm = computed(() => search.value.trim().toLocaleLowerCase());
watch([queue, search], () => { visibleLimit.value = 8; });
const clock = ref(Date.now());
let loadId = 0;
const timer = window.setInterval(() => { clock.value = Date.now(); }, 60000);
onUnmounted(() => { ++loadId; window.clearInterval(timer); });

async function load() {
  const currentLoad = ++loadId, token = getToken();
  loading.value = true;
  const requests: { label: string; path: string; target: typeof tasks }[] = [
    { label: '任务', path: '/college-work/tasks', target: tasks },
    { label: '学生事务', path: '/office/board', target: board },
    { label: '人员', path: '/college-work/people', target: people },
  ];
  if (counselor.value) requests.push({ label: '学生跟进', path: '/college-work/care', target: care });
  const results = await Promise.allSettled(requests.map(request => api<Row[]>(request.path)));
  if (currentLoad !== loadId || token !== getToken()) return;
  errors.value = [];
  results.forEach((result, index) => {
    const request = requests[index];
    if (result.status === 'fulfilled') request.target.value = result.value;
    else { request.target.value = []; errors.value.push(request.label + '暂未加载：' + (result.reason?.message || '连接失败')); }
  });
  tasksLoaded.value = results[0].status === 'fulfilled';
  loading.value = false;
}
onMounted(load);
watch(() => props.refreshKey, load);
const personName = (id: string) => id === props.user.id ? '我' : people.value.find(person => person.id === id)?.name || '相关人员';
const assigned = computed(() => tasks.value.filter(task => manager.value || task.assigneeId === props.user.id));
const effective = computed(() => assigned.value.filter(task => task.status !== 'cancelled'));
const completed = computed(() => effective.value.filter(task => task.status === 'completed').length);
const submitted = computed(() => effective.value.filter(task => task.status === 'submitted').length);
const rate = computed(() => effective.value.length ? Math.round(completed.value / effective.value.length * 100) : 0);
const canReview = (task: Row) => task.status === 'submitted' && (props.user.role === 'admin' || task.ownerId === props.user.id);
const closed = (row: Row) => ['completed', 'cancelled', 'closed', 'archived', 'withdrawn', 'submitted'].includes(row.status);
const isScheduled = (task: Row) => !closed(task) && task.startsAt && new Date(task.startsAt).getTime() > clock.value;
const scheduledTasks = computed(() => assigned.value.filter(isScheduled));
const dueTime = (value?: string) => value ? new Date(value).getTime() : Infinity;
const day = (time: number) => new Date(time).toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' });
const isOverdue = (todo: Todo) => !todo.review && dueTime(todo.dueAt) < clock.value;
const isToday = (todo: Todo) => !todo.review && Number.isFinite(dueTime(todo.dueAt)) && day(dueTime(todo.dueAt)) === day(clock.value);
const isSoon = (todo: Todo) => !todo.review && dueTime(todo.dueAt) >= clock.value && dueTime(todo.dueAt) <= clock.value + 7 * 86400000;

const todos = computed<Todo[]>(() => {
  const output: Todo[] = [];
  for (const task of tasks.value) {
    if (canReview(task)) output.push({ id: 'college:' + task.id, recordId: task.id, title: task.title, dueAt: task.dueAt, route: 'college-tasks', kind: '任务审核', action: '审核任务', review: true, person: personName(task.assigneeId) });
    else if (!closed(task) && !isScheduled(task) && (manager.value || task.assigneeId === props.user.id)) output.push({ id: 'college:' + task.id, recordId: task.id, title: task.title, dueAt: task.dueAt, route: 'college-tasks', kind: '工作任务', action: task.assigneeId === props.user.id ? '办理任务' : '查看进度', review: false, person: manager.value ? personName(task.assigneeId) : undefined });
  }
  const routes: Record<string, string> = { manual: 'tasks', notice: 'notices', worklog: 'office', holiday: 'office', collection: 'collections', cooperation: 'erp', activity: 'erp', award: 'erp', housing: 'erp', support: 'erp', event: 'erp', transfer: 'erp', job: 'erp' };
  const erpModules: Record<string, string> = { cooperation: 'cases', activity: 'activities', award: 'awards', housing: 'housing', support: 'support', event: 'events', transfer: 'transfers', job: 'jobs' };
  const kinds: Record<string, string> = { manual: '个人待办', notice: '通知确认', request: '学生申请', worklog: '工作跟进', holiday: '假期返校', collection: '学生材料', cooperation: '部门办理', activity: '学生活动' };
  for (const item of board.value) {
    if (closed(item) || item.status === 'paused' || item.status === 'exception') continue;
    const action = String(item.nextAction || '');
    if (/等待|待配置|查看办理进度/.test(action)) continue;
    if (item.source === 'manual' && item.assigneeId !== props.user.id) continue;
    if (item.source === 'request' && item.assigneeId !== props.user.id) continue;
    if (item.source === 'holiday' && item.assigneeId !== props.user.id && action !== '核验返校') continue;
    const source = item.source === 'request' ? 'request' : item.source;
    const rawRoute = item.route || (source === 'request' ? item.requestType === 'leave' ? 'leaves' : 'service-requests' : routes[source]);
    const route = rawRoute === 'operations' ? 'erp' : rawRoute;
    if (!route) continue;
    const target = source === 'worklog' ? 'worklogs' : source === 'holiday' ? 'holidays' : route === 'erp' ? erpModules[source] : undefined;
    const review = /核验|审核|审批/.test(action);
    output.push({ id: 'office:' + item.id, recordId: item.sourceId || item.businessId || item.id, title: item.title, dueAt: item.dueAt, route, target, kind: kinds[item.source] || '业务待办', action: item.source === 'request' && review ? '处理申请' : review ? '审核事项' : '查看事项', review });
  }
  for (const item of care.value) {
    if (closed(item) || item.ownerId !== props.user.id || !item.dueAt) continue;
    output.push({ id: 'care:' + item.id, recordId: item.id, title: item.title, dueAt: item.dueAt, route: 'college-care', kind: '学生跟进', action: '记录跟进', review: false });
  }
  return output.sort((a, b) => Number(isOverdue(b)) - Number(isOverdue(a)) || dueTime(a.dueAt) - dueTime(b.dueAt) || a.title.localeCompare(b.title, 'zh-CN'));
});
const queues = computed(() => [
  { id: 'open' as Queue, label: manager.value ? '未完成事项' : '需要我处理', count: todos.value.length, icon: 'List', tone: 'neutral' },
  { id: 'today' as Queue, label: '今天到期', count: todos.value.filter(isToday).length, icon: 'Calendar', tone: 'blue' },
  { id: 'week' as Queue, label: '7天内到期', count: todos.value.filter(isSoon).length, icon: 'Clock', tone: 'amber' },
  { id: 'overdue' as Queue, label: '逾期未完成', count: todos.value.filter(isOverdue).length, icon: 'Warning', tone: 'red' },
  { id: 'review' as Queue, label: '待我审核', count: todos.value.filter(todo => todo.review).length, icon: 'Checked', tone: 'green' },
]);
const queueTodos = computed(() => todos.value.filter(todo => queue.value === 'today' ? isToday(todo) : queue.value === 'week' ? isSoon(todo) : queue.value === 'overdue' ? isOverdue(todo) : queue.value === 'review' ? todo.review : true));
const visibleTodos = computed(() => queueTodos.value.filter(todo => !searchTerm.value || [todo.title, todo.kind, todo.person || ''].some(value => value.toLocaleLowerCase().includes(searchTerm.value))));
const displayedTodos = computed(() => visibleTodos.value.slice(0, visibleLimit.value));
const remainingTodos = computed(() => visibleTodos.value.length - displayedTodos.value.length);
const emptyMessage = computed(() => {
  if (searchTerm.value) return errors.value.length ? '已加载事项中未找到匹配结果，部分待办还需重新加载' : '当前分类没有找到匹配事项，试试其他关键词或清空搜索';
  if (errors.value.length) return '部分待办未能加载，请重新加载后查看';
  return queue.value === 'review' ? '暂无待你审核的事项' : queue.value === 'overdue' ? '暂无逾期事项' : queue.value === 'today' ? '今天没有到期事项' : queue.value === 'week' ? '7天内没有到期事项' : '暂无需要处理的事项';
});
const shortcuts = computed(() => manager.value ? [
  { route: 'college-tasks', title: '下发与查看任务', icon: 'DataAnalysis' },
  { route: 'notices', title: '通知公告', icon: 'Bell' },
  ...(props.user.role === 'admin' ? [{ route: 'governance', title: '用户管理', icon: 'UserFilled' }] : [{ route: 'registry', title: '学生名单', icon: 'UserFilled' }]),
] : props.user.role === 'sysadmin' ? [
  { route: 'maintenance', title: '运行与备份', icon: 'Monitor' },
  { route: 'college-tasks', title: '我的工作任务', icon: 'List' },
  { route: 'messages', title: '消息提醒', icon: 'ChatLineRound' },
] : counselor.value ? [
  { route: 'registry', title: '学生名单', icon: 'UserFilled' },
  { route: 'college-care', title: '谈话与学生跟进', icon: 'ChatLineRound' },
  { route: 'leaves', title: '请假与返校', icon: 'Calendar' },
  { route: 'collections', title: '收集学生材料', icon: 'Document' },
] : [
  { route: 'college-tasks', title: '我的工作任务', icon: 'List' },
  { route: 'notices', title: '通知公告', icon: 'Bell' },
  { route: 'messages', title: '消息提醒', icon: 'ChatLineRound' },
]);
const bars = computed(() => {
  const grouped = new Map<string, { id: string; name: string; total: number; completed: number; overdue: number }>();
  for (const task of effective.value) {
    const id = task.assigneeId;
    if (!grouped.has(id)) grouped.set(id, { id, name: personName(id), total: 0, completed: 0, overdue: 0 });
    const person = grouped.get(id)!;
    ++person.total;
    if (task.status === 'completed') ++person.completed;
    else if (!closed(task) && dueTime(task.dueAt) < clock.value) ++person.overdue;
  }
  return [...grouped.values()].sort((a, b) => b.overdue - a.overdue || a.name.localeCompare(b.name, 'zh-CN'));
});
const dateLabel = (value?: string) => value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }) : '未设截止时间';
function openTodo(todo: Todo) {
  emit('navigate', todo.route, todo.route.startsWith('college-') ? todo.review ? 'review' : 'open' : todo.target || 'all', todo.recordId);
}
</script>

<template>
  <section class="work-dashboard" v-loading="loading">
    <div v-if="errors.length" class="dashboard-errors">
      <el-alert v-for="message in errors" :key="message" :title="message" type="warning" :closable="false" show-icon />
      <div class="load-retry"><span>已加载的事项仍可办理，未加载部分请重试。</span><el-button :loading="loading" @click="load">重新加载</el-button></div>
    </div>
    <div class="queue-switch" role="group" aria-label="待办分类">
      <button v-for="item in queues" :key="item.id" :class="[item.tone, { active: queue === item.id }]" :aria-pressed="queue === item.id" @click="queue = item.id">
        <span class="queue-label">{{ item.label }}<el-icon><component :is="item.icon" /></el-icon></span>
        <span class="queue-value"><strong>{{ loading || errors.length ? '—' : item.count }}</strong><small>项</small></span>
        <span class="queue-caption">{{ queue === item.id ? '正在查看' : '点击查看' }}<el-icon><ArrowRight /></el-icon></span>
      </button>
    </div>
    <section class="todo-section" aria-label="待办清单">
      <header><div><span class="section-kicker">待办清单</span><h2>{{ queues.find(item => item.id === queue)?.label }}</h2></div><span class="todo-count" aria-live="polite">{{ errors.length ? '已加载 ' : searchTerm ? '找到 ' : '' }}{{ visibleTodos.length }} 项</span><el-button text :loading="loading" @click="load"><el-icon><Refresh /></el-icon>更新待办</el-button></header>
      <div class="todo-search"><el-input v-model="search" clearable label="搜索首页待办" aria-label="搜索首页待办" :placeholder="manager ? '搜索任务、类型或办理人' : '搜索任务标题或类型'"><template #prefix><el-icon><Search /></el-icon></template></el-input><el-button v-if="searchTerm" text @click="search = ''">清空搜索</el-button></div>
      <ul v-if="visibleTodos.length" class="todo-list">
        <li v-for="todo in displayedTodos" :key="todo.id" :class="{ 'is-overdue': isOverdue(todo), 'is-review': todo.review }">
          <span class="todo-kind-icon" aria-hidden="true"><el-icon><component :is="todo.review ? 'Checked' : todo.route === 'college-care' ? 'ChatLineRound' : 'Document'" /></el-icon></span>
          <div class="todo-main"><div class="todo-meta"><span>{{ todo.kind }}</span><span v-if="todo.person">{{ todo.person }}</span></div><h3>{{ todo.title }}</h3><div class="todo-deadline"><el-tag v-if="todo.review" type="success" size="small">待审核</el-tag><el-tag v-else-if="isOverdue(todo)" type="danger" size="small">已逾期</el-tag><el-tag v-else-if="isToday(todo)" type="warning" size="small">今天到期</el-tag><time :datetime="todo.dueAt">{{ dateLabel(todo.dueAt) }}</time></div></div>
          <el-button type="primary" plain @click="openTodo(todo)">{{ todo.action }}<el-icon><ArrowRight /></el-icon></el-button>
        </li>
      </ul>
      <div v-else-if="!loading" class="dashboard-empty" role="status"><span class="empty-check" aria-hidden="true"><el-icon><Checked /></el-icon></span><h3>{{ emptyMessage }}</h3><p v-if="!searchTerm && !errors.length">{{ queue === 'open' ? '新事项会自动汇集到这里。' : '可以切换分类，查看其他待办。' }}</p><el-button v-if="searchTerm" @click="search = ''">清空搜索</el-button><el-button v-else-if="queue !== 'open'" @click="queue = 'open'">查看全部待办</el-button></div>
      <div v-if="remainingTodos" class="todo-more"><span aria-live="polite">已显示 {{ displayedTodos.length }} / {{ visibleTodos.length }} 项</span><el-button @click="visibleLimit += 8">再看 {{ Math.min(8, remainingTodos) }} 项</el-button></div>
      <div v-if="scheduledTasks.length" class="scheduled-tasks"><span><el-icon><Calendar /></el-icon>另有 {{ scheduledTasks.length }} 项任务尚未开始</span><el-button text @click="emit('navigate', 'college-tasks', 'scheduled')">查看安排<el-icon><ArrowRight /></el-icon></el-button></div>
    </section>
    <section class="progress-section" aria-label="工作任务统计">
      <header><div><span class="section-kicker">工作概览</span><h2>{{ manager ? '人员工作任务进度' : '我的工作任务进度' }}</h2></div></header>
      <div v-if="tasksLoaded" class="progress-content">
        <div class="rate"><el-progress type="circle" :percentage="rate" :width="112" :stroke-width="10" color="var(--brand)" /><span>任务完成率</span></div>
        <div class="progress-totals"><div><strong>{{ completed }}</strong><span>已完成</span></div><div><strong>{{ submitted }}</strong><span>待审核</span></div><div><strong>{{ effective.length }}</strong><span>全部任务</span></div></div>
        <div v-if="manager" class="people"><div v-for="person in bars" :key="person.id" class="person"><div><strong>{{ person.name }}</strong><span>{{ person.completed }} / {{ person.total }}<em v-if="person.overdue"> · 逾期 {{ person.overdue }}</em></span></div><el-progress :percentage="Math.round(person.completed / person.total * 100)" :show-text="false" color="var(--brand)" /></div><p v-if="!bars.length" class="no-tasks">暂无工作任务</p></div>
        <p class="progress-note">仅统计工作任务，{{ manager ? '展示当前权限内人员进度' : '提交待审核的任务不计入已完成' }}。</p>
      </div>
      <p v-else-if="!loading" class="no-tasks">任务统计暂未加载</p>
      <el-button class="progress-link" @click="emit('navigate', 'college-tasks')">查看任务<el-icon><ArrowRight /></el-icon></el-button>
    </section>
    <section class="shortcut-section">
      <div class="quick-actions-head"><h2>常用入口</h2><el-icon aria-hidden="true"><Connection /></el-icon></div>
      <div class="quick-actions" aria-label="常用业务">
        <button v-for="item in shortcuts" :key="item.route" class="quick-action-card" :aria-label="item.title" @click="emit('navigate', item.route)"><span class="quick-action-icon" aria-hidden="true"><el-icon><component :is="item.icon" /></el-icon></span><span class="quick-action-copy"><strong>{{ item.title }}</strong></span><el-icon class="quick-action-arrow" aria-hidden="true"><ArrowRight /></el-icon></button>
      </div>
      <p class="quick-actions-hint">更多功能可在顶部搜索 <kbd>Ctrl K</kbd></p>
    </section>
  </section>
</template>


<style scoped>
.work-dashboard{display:grid;grid-template-columns:minmax(0,1fr) 300px;gap:20px;align-items:start;min-width:0;color:var(--ink)}
.dashboard-errors,.queue-switch{grid-column:1/-1}.dashboard-errors .el-alert+.el-alert{margin-top:8px}
.queue-switch{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:14px}
.queue-switch button{position:relative;display:flex;flex-direction:column;gap:14px;min-width:0;padding:18px 18px 14px;border:1px solid var(--line);border-radius:8px;background:#fff;color:#52627a;text-align:left;transition:border-color .18s,box-shadow .18s}
.queue-switch button:hover{border-color:var(--brand);box-shadow:0 4px 15px #1934570a}
.queue-switch button.active{border-color:var(--brand);box-shadow:0 0 0 1px var(--brand)}
.queue-label{display:flex;align-items:center;justify-content:space-between;gap:7px;font-size:13px;line-height:1.5}
.queue-label .el-icon{flex:none;font-size:19px;color:var(--brand)}
.queue-value{display:flex;align-items:baseline;gap:7px}.queue-value strong{font-size:32px;line-height:1.1;font-weight:650;letter-spacing:-1px;color:var(--ink);font-variant-numeric:tabular-nums}.queue-value small{font-size:12px;color:var(--muted)}
.queue-caption{display:flex;align-items:center;justify-content:space-between;font-size:11px;color:var(--muted)}.active .queue-caption{color:var(--brand)}
.queue-switch .red .queue-label .el-icon,.queue-switch .red .queue-value strong{color:#b83e4b}.queue-switch .green .queue-label .el-icon{color:#21836a}.queue-switch .amber .queue-label .el-icon{color:#aa7620}
.todo-section,.progress-section,.shortcut-section{min-width:0;border:1px solid var(--line);border-radius:10px;background:#fff;box-shadow:none}
.todo-section{grid-column:1;grid-row:2/4;padding:24px;min-height:400px}.progress-section{grid-column:2;grid-row:2;padding:22px}.shortcut-section{grid-column:2;grid-row:3;padding:20px}
header{display:flex;align-items:center;gap:12px;margin-bottom:20px}header h2,.quick-actions-head h2{margin:0;font-size:17px;line-height:1.5;font-weight:650}.section-kicker{display:block;font-size:11px;color:var(--muted);margin-bottom:4px}header>.el-button{margin-left:auto}.todo-count{padding:3px 8px;border-radius:6px;background:#f0f4f8;color:#5f7085;font-size:12px;white-space:nowrap}
.todo-search{display:flex;align-items:center;gap:8px;margin-bottom:10px}.todo-search .el-input{min-width:0;flex:1}.todo-search>.el-button{flex:none;margin:0}
.todo-list{list-style:none;padding:0;margin:0}.todo-list li{display:grid;grid-template-columns:38px minmax(0,1fr) auto;align-items:center;gap:13px;padding:19px 0;border-bottom:1px solid #edf1f5}.todo-list li:last-child{border-bottom:0}.todo-kind-icon{display:grid;place-items:center;align-self:start;width:38px;height:38px;border-radius:11px;background:var(--brand-soft);color:var(--brand);font-size:19px}.is-overdue .todo-kind-icon{background:#fbeef0;color:#b83e4b}.is-review .todo-kind-icon{background:#eaf6f0;color:#21836a}.todo-main{min-width:0}.todo-meta{display:flex;gap:8px;margin-bottom:4px;color:#697b90;font-size:12px}.todo-meta span+span::before{content:'\00b7';margin-right:8px}.todo-main h3{margin:0;font-size:15px;font-weight:600;line-height:1.7;overflow-wrap:anywhere}.todo-deadline{display:flex;align-items:center;flex-wrap:wrap;gap:8px;margin-top:9px;color:#738296;font-size:12px}.todo-deadline time{font-variant-numeric:tabular-nums}.todo-list .el-button{margin:0;padding-inline:12px}
.dashboard-empty{display:flex;flex-direction:column;align-items:center;gap:13px;padding:55px 15px;text-align:center}.empty-check{display:grid;place-items:center;width:60px;height:60px;border-radius:20px;background:#edf6f3;color:#4d927b;font-size:28px}.dashboard-empty h3{font-size:15px;font-weight:500;line-height:1.7}.dashboard-empty p{font-size:13px;line-height:1.7;color:var(--muted)}
.progress-content{display:flex;flex-direction:column;gap:20px}.rate{display:flex;align-items:center;flex-direction:column;gap:10px;color:var(--muted);font-size:12px}.progress-totals{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));padding-block:15px;border-block:1px solid #edf1f5}.progress-totals>div{text-align:center;border-right:1px solid #edf1f5}.progress-totals>div:last-child{border:0}.progress-totals strong{display:block;font-size:22px;font-weight:600;font-variant-numeric:tabular-nums}.progress-totals span{display:block;margin-top:7px;color:var(--muted);font-size:12px}.people{display:grid;gap:15px;max-height:230px;overflow:auto;padding-right:4px}.person>div:first-child{display:flex;flex-wrap:wrap;justify-content:space-between;gap:5px;margin-bottom:7px;font-size:12px}.person>div:first-child span{color:var(--muted)}.person strong{font-weight:500}.person em{color:#b83e4b;font-style:normal}.no-tasks,.progress-note{color:var(--muted);font-size:12px;line-height:1.8}.progress-link{width:100%;margin-top:16px}
.quick-actions-head{display:flex;align-items:center;justify-content:space-between;margin-bottom:15px;color:var(--ink)}.quick-actions-head>.el-icon{color:#9aabba}.quick-actions{display:grid;gap:8px}.quick-action-card{display:flex;align-items:center;gap:10px;min-width:0;padding:8px;border:1px solid transparent;border-radius:10px;background:#f7f9fc;text-align:left;color:var(--ink);transition:background .18s,border-color .18s}.quick-action-card:hover{background:var(--brand-soft);border-color:var(--brand)}.quick-action-icon{display:grid;place-items:center;flex:none;width:32px;height:32px;border-radius:9px;background:#fff;color:var(--brand);font-size:18px}.quick-action-copy{flex:1;min-width:0}.quick-action-copy strong{font-size:13px;font-weight:500;line-height:1.6;white-space:normal;overflow-wrap:anywhere}.quick-action-arrow{color:#8999ab;font-size:14px}.quick-actions-hint{margin:15px 0 0;font-size:11px;color:var(--muted);line-height:1.8}kbd{padding:2px 4px;border:1px solid var(--line);border-radius:4px;font-family:inherit;font-size:10px}
.load-retry,.todo-more,.scheduled-tasks{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:10px;font-size:12px;color:var(--muted);margin:16px 0 0}.scheduled-tasks{padding:12px 0 0;border-top:1px solid var(--line)}.scheduled-tasks>span{display:flex;align-items:center;gap:6px}.todo-more{justify-content:center}
@media(max-width:1250px){.work-dashboard{grid-template-columns:minmax(0,1fr) 260px;gap:16px}.queue-switch{gap:10px}.queue-switch button{padding:16px 12px}.todo-section{padding:18px}.todo-list li{grid-template-columns:minmax(0,1fr) auto}.todo-kind-icon{display:none}}
@media(max-width:1100px){.work-dashboard{grid-template-columns:minmax(0,1fr)}.todo-section,.progress-section,.shortcut-section{grid-column:1;grid-row:auto}.todo-section{min-height:0}.progress-content{display:grid;grid-template-columns:150px minmax(0,1fr);align-items:center}.rate{grid-row:1/3}.progress-totals{border-top:0}.people{grid-column:1/-1}.progress-note{grid-column:2}.progress-link{max-width:250px;display:flex;margin-inline:auto}.quick-actions{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:700px){.queue-switch{grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.queue-switch button{gap:8px;padding:12px 14px}.queue-switch button:first-child{grid-column:1/-1;display:grid;grid-template-columns:1fr auto;align-items:center;background:#fff;color:var(--ink);border-color:var(--line)}.queue-switch button:first-child strong{color:var(--ink)}.queue-switch button:first-child small{color:var(--muted)}.queue-switch button:first-child .queue-caption{grid-column:1/-1;color:#c8d8ee}.queue-value strong{font-size:27px}.queue-caption{font-size:11px}.queue-label{font-size:13px}.todo-section,.progress-section,.shortcut-section{padding:17px 14px;border-radius:8px}.todo-section header{gap:8px}.todo-section header h2{font-size:16px}.todo-section header>.el-button{padding:8px;font-size:12px}.todo-list li{grid-template-columns:minmax(0,1fr);gap:12px;padding:18px 0}.todo-list .el-button{justify-self:end;min-height:40px;min-width:112px}.progress-content{grid-template-columns:136px minmax(0,1fr);gap:16px}.progress-totals{gap:3px}.progress-totals strong{font-size:20px}.progress-totals span{font-size:11px}.progress-note{grid-column:1/-1}.people{max-height:none}.quick-action-card{gap:8px}.quick-action-arrow{display:none}.quick-actions-hint{font-size:12px}}

@media(min-width:1101px){.progress-content{display:grid;grid-template-columns:112px minmax(0,1fr);gap:18px 12px;align-items:center}.progress-totals{display:flex;flex-direction:column;padding:0;border:0;gap:12px}.progress-totals>div{display:flex;align-items:baseline;justify-content:space-between;gap:5px;text-align:left;border:0}.progress-totals strong{font-size:21px}.progress-totals span{font-size:11px;margin:0}.people,.progress-note{grid-column:1/-1}.progress-section header{margin-bottom:18px}}

@media(max-width:700px){.queue-switch button{padding:10px 13px;gap:6px}.queue-caption{display:none!important}.queue-value strong{font-size:25px}.todo-list li{grid-template-columns:minmax(0,1fr) 112px;gap:8px 12px;padding:17px 0}.todo-main{display:contents}.todo-meta,.todo-main h3{grid-column:1/-1}.todo-meta{margin:0}.todo-deadline{margin-top:0}.todo-list .el-button{grid-column:2}}

.queue-switch button.active{background:var(--brand-soft);border-color:var(--brand);box-shadow:none}
.queue-switch button.active .queue-value strong,.queue-switch button.active .queue-label{color:var(--brand)}
.queue-switch .red:not(.active) .queue-value strong{color:#b83e4b}
.todo-list li{border-radius:10px;transition:background .16s}.todo-list li:hover{background:#f8fafc}
.todo-list .is-overdue h3{color:#9f3441}.todo-list .el-button{border-radius:6px}
.quick-action-card{min-height:46px;padding:10px}.quick-action-copy strong{font-size:14px}
.todo-deadline{font-size:13px}.todo-meta{font-size:12px}.todo-main h3{font-size:16px}
/* All five categories remain visible on phones without horizontal scrolling. */
@media(max-width:700px){
 .queue-switch{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;overflow:visible}
 .queue-switch button{padding:14px;gap:10px}.queue-switch button:first-child{grid-column:1/-1;display:flex;flex-direction:row;align-items:center;justify-content:space-between;padding:14px}
 .queue-switch button:first-child .queue-label{justify-content:flex-start;gap:10px}.queue-label{font-size:13px}.queue-label .el-icon{font-size:18px}.queue-value strong{font-size:27px}.queue-caption{display:none!important}
 .queue-switch button.active{background:var(--brand-soft);border-color:var(--brand)}.queue-switch button.active strong{color:var(--brand)}
 .todo-section{padding:18px 15px}.todo-section header{margin-bottom:15px}.section-kicker{display:none}.todo-list li{padding:18px 0}.todo-main h3{font-size:15px}.todo-deadline{font-size:12px}.todo-list .el-button{min-height:42px}
 .progress-section,.shortcut-section{padding:18px 16px}.quick-actions{gap:8px}.quick-action-card{padding:10px 7px}.quick-action-copy strong{font-size:13px}
}
@media(prefers-reduced-motion:reduce){.todo-list li{transition:none}}

@media print{.work-dashboard{display:block}.queue-switch,.shortcut-section,.todo-search,.load-retry,.todo-more,.el-button{display:none!important}.todo-section,.progress-section{padding:12px 0;border:0;box-shadow:none;min-height:0}.todo-list li{break-inside:avoid;grid-template-columns:minmax(0,1fr)}.people{max-height:none;overflow:visible}.progress-content{display:block}.rate{margin-block:16px}}
</style>
