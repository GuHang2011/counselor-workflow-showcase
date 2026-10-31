<script setup lang="ts">
import { computed, defineAsyncComponent, h, nextTick, onMounted, onUnmounted, reactive, ref, watch, type Component } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import MaterialUpload from '@/components/MaterialUpload.vue';
import TaskReminder from '@/components/TaskReminder.vue';
import WorkDashboard from '@/components/WorkDashboard.vue';
import UserAvatar from '@/components/UserAvatar.vue';
import EngineeringIllustration from '@/components/EngineeringIllustration.vue';
import FeatureSearch from '@/components/FeatureSearch.vue';
import { api, getToken, setToken } from './api';

// Business pages load when opened, keeping the login and dashboard lightweight.
const pageLoading = { setup: () => () => h('div', { class: 'page-load-status', role: 'status' }, '正在打开页面，请稍候…') };
const pageLoadError = { setup: () => () => h('div', { class: 'page-load-status', role: 'alert' }, [
	h('p', '页面未能加载，请检查网络后重新打开。'),
	h('button', { class: 'text-link', onClick: () => location.reload() }, '重新加载页面'),
]) };
function lazyPage(loader: () => Promise<{ default: Component }>) {
	return defineAsyncComponent({ loader, loadingComponent: pageLoading, errorComponent: pageLoadError, delay: 150, timeout: 30000 });
}
const OfficeExtensions = lazyPage(() => import('@/components/OfficeExtensions.vue'));
const StudentRegistry = lazyPage(() => import('@/components/StudentRegistry.vue'));
const CollectionDesk = lazyPage(() => import('@/components/CollectionDesk.vue'));
const AccountGovernance = lazyPage(() => import('@/components/AccountGovernance.vue'));
const ActivateAccount = lazyPage(() => import('@/components/ActivateAccount.vue'));
const ErpOperations = lazyPage(() => import('@/components/ErpOperations.vue'));
const AdvancedOffice = lazyPage(() => import('@/components/AdvancedOffice.vue'));
const LeaveOperations = lazyPage(() => import('@/components/LeaveOperations.vue'));
const SupportTools = lazyPage(() => import('@/components/SupportTools.vue'));
const SystemMaintenance = lazyPage(() => import('@/components/SystemMaintenance.vue'));
const ServiceRequests = lazyPage(() => import('@/components/ServiceRequests.vue'));
const AiWorkbench = lazyPage(() => import('@/components/AiWorkbench.vue'));
const InternalChat = lazyPage(() => import('@/components/InternalChat.vue'));
const CollegeWork = lazyPage(() => import('@/components/CollegeWork.vue'));

type Item = Record<string, any>;
const systemName = '智能工程技术学院辅导员系统';
type User = Item & { id: string; name: string; role: string; className: string };
const user = ref<User | null>(null);
const starting = ref(true);
const startupError = ref('');
const reconnecting = ref(false);
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const menuOpen = ref(false);
const page = ref(location.hash.slice(1) || 'dashboard');
const theme = ref('blue');
const themeOptions = [
	{ id: 'blue', label: '晴空蓝', color: '#205ad3' },
	{ id: 'green', label: '竹叶绿', color: '#168468' },
	{ id: 'pink', label: '蔷薇粉', color: '#b74579' },
];
const roles: Item = { admin: '超级管理员', counselor: '辅导员 / 兼职辅导员', student: '学生', leader: '学院书记 / 副书记', business: '业务管理员', mentor: '班主任 / 导师', finance: '财务经办', logistics: '后勤经办', assistant: '学生助理', sysadmin: '系统管理员' };
const isAdmin = computed(() => user.value?.role === 'admin');
const isSuperAdmin = computed(() => isAdmin.value && user.value?.adminLevel !== 'administrator');
const isStaff = computed(() => ['admin', 'counselor'].includes(user.value?.role || ''));
const activationOpen = ref(false);
const roleState = ref<{activeRoleId:string;roles:Item[]}>({activeRoleId:'primary',roles:[]});
const requestedRole = ref('primary');
const users = ref<Item[]>([]),
	notices = ref<Item[]>([]),
	tasks = ref<Item[]>([]),
	requests = ref<Item[]>([]),
	audit = ref<Item[]>([]);
const directory = ref<Item[]>([]);
const availablePeople = computed(() => Array.from(new Map([...users.value, ...directory.value].map(u => [u.id, u])).values()));
const settings = ref<Item>({});
const messages = ref<Item[]>([]);
const chatUnread = ref(0);
const chatBusy = ref(false);
const chatRefresh = ref(0);
const menuSearch = ref('');
const commandOpen = ref(false);
const moreBusinessOpen = ref(false), dashboardRefresh = ref(0), initialRecordId = ref(''), initialRecordTarget = ref('');
const recordRequest = ref(0);
const identityKey = computed(() => user.value ? user.value.id + ':' + (user.value.activeRoleId || 'primary') : 'guest');
const loginForm = reactive({ username: '', password: '' });
const passwordForm = reactive({ oldPassword: '', newPassword: '', repeat: '' });
const passwordOpen = ref(false);
const search = ref('');
const tab = ref(page.value === 'tasks' || page.value === 'college-tasks' ? 'open' : 'all');
const personalScope = ref('mine');
const uploading = ref(false);
const dialog = ref('');
const selected = ref<Item | null>(null);
const form = reactive<Item>({});
const today = ref(new Date());
const todayLabel = computed(() => today.value.toLocaleDateString('zh-CN', { timeZone: 'Asia/Shanghai', month: 'long', day: 'numeric', weekday: 'long' }));
const greeting = computed(() => {
	const hour = Number(today.value.toLocaleString('en-US', { timeZone: 'Asia/Shanghai', hour: 'numeric', hourCycle: 'h23' }));
	return hour < 12 ? '上午' : hour < 18 ? '下午' : '晚上';
});
const menu = computed(() => [
	{ id: 'dashboard', title: '工作台', icon: 'Odometer', desc: '先看今天要做什么，再进入具体业务。' },
	{ id: 'chat', title: '内部聊天', icon: 'ChatDotRound', desc: '与校内联系人沟通，文字、表情包和文件集中留存。' },
	{ id: 'notices', title: '通知公告', icon: 'Bell', desc: '查看学校通知，重要公告需要点击确认。' },
	...(!['admin','sysadmin'].includes(user.value?.role || '') ? [{ id: 'advanced', title: '日程与工作交接', icon: 'Connection', desc: '安排日程，交接工作。' }] : []),
	{ id: 'collections', title: '学生材料收集', icon: 'Document', desc: '发起收集、查看提交情况、审核材料。' },
	{ id: 'leaves', title: '请假与返校', icon: 'Calendar', desc: '处理学生请假并核验返校。' },
	{ id: 'erp', title: '业务协同', icon: 'OfficeBuilding', desc: '资助、住宿、迎新离校和活动统一办理。' },
	{ id: 'support', title: '制度与资源预约', icon: 'Reading', desc: '查看制度来源，安排值班、场地与设备。' },
	...(['admin','counselor','mentor','leader','business'].includes(user.value?.role || '') ? [{ id: 'ai', title: 'AI 办公', icon: 'MagicStick', desc: '写作、润色、通知和总结。' }] : []),
	{ id: 'service-requests', title: '咨询与预约', icon: 'ChatDotRound', desc: '咨询有回音，预约、转介和办理过程可查看。' },
		...(['admin','sysadmin'].includes(user.value?.role || '') ? [{ id: 'maintenance', title: '运行与备份', icon: 'Monitor', desc: '运行状态、异常处理、加密备份与恢复治理。' }] : []),
	{ id: 'tasks', title: '个人待办', icon: 'Checked', desc: '处理个人安排和公告确认；需要提交成果的任务请到“' + (['admin','leader'].includes(user.value?.role || '') ? '任务下发与审核' : '工作任务') + '”办理。' },
	{ id: 'college-tasks', title: ['admin','leader'].includes(user.value?.role || '') ? '任务下发与审核' : '工作任务', icon: 'DataAnalysis', desc: ['admin','leader'].includes(user.value?.role || '') ? '下发任务、查看完成情况、审核提交的成果。' : ['counselor','mentor','business','logistics'].includes(user.value?.role || '') ? '在“我收到的”办理任务，在“我发布的”查看接收人的进度。' : '查看收到的任务，保存办理进度，办完后提交完成。' },
	...(['counselor','mentor','leader','business','logistics'].includes(user.value?.role || '') ? [
		{ id: 'college-party', title: '入团入党', icon: 'Flag', desc: '记录发展阶段、材料核验及跟进时间。' },
		{ id: 'college-dorms', title: '宿舍与安全', icon: 'House', desc: '床位分配、人员变动、卫生和退宿。' },
		{ id: 'college-archive', title: '工作材料归档', icon: 'Folder', desc: '把办结事项和附件统一留存。' },
	] : []),
	...(['counselor','mentor'].includes(user.value?.role || '') ? [{ id: 'college-care', title: '谈话与学生跟进', icon: 'FirstAidKit', desc: '记录师生谈话及需要持续关心的学生。' }] : []),
	...(!['admin','sysadmin'].includes(user.value?.role || '') ? [{ id: 'requests', title: user.value?.role === 'student' ? '我的事务' : '学生事务', icon: 'FolderOpened', desc: '每一次申请、咨询和反馈都有记录。' }] : []),
	{ id: 'office', title: user.value?.role === 'student' ? '假期登记' : '日常办公', icon: 'Calendar', desc: user.value?.role === 'student' ? '填写假期去向，返校后确认到校。' : '记录日常工作，查看假期去向并核验返校。' },
	{ id: 'messages', title: '提醒中心', icon: 'ChatLineRound', desc: '查看任务和学生事务提醒，进入对应事项办理。' },
	...(!['admin','sysadmin'].includes(user.value?.role || '') ? [{ id: 'registry', title: user.value?.role === 'student' ? '我的档案' : '学生名单与档案', icon: 'UserFilled', desc: '查找学生，查看与修改学生资料。' }] : []),
		...(isAdmin.value ? [{ id: 'governance', title: isSuperAdmin.value ? '账号与组织管理' : '用户管理', icon: 'Key', desc: isSuperAdmin.value ? '用户状态、管理员级别、组织和权限治理。' : '维护业务账号资料、状态和密码。' }] : []),
	...(isSuperAdmin.value
		? [
				{ id: 'audit', title: '操作记录', icon: 'Tickets', desc: '重要操作可追踪、可核对。' },
		  ]
		: []),
	{ id: 'settings', title: '个人与设置', icon: 'Setting', desc: '修改密码、设置提醒和调整页面颜色。' },
].filter(item => (!user.value?.roleRestricted || ['erp','settings'].includes(item.id)) &&
	(user.value?.role !== 'sysadmin' || ['dashboard','maintenance','college-tasks','tasks','messages','chat','notices','settings'].includes(item.id))));
const current = computed(() => menu.value.find((item) => item.id === page.value) || menu.value[0]);
/* 角色分流：管理员看治理与审核，辅导员看学生工作与办理，其他角色看常用功能。
   高频入口放前几组，低频业务统一收进“更多业务”，避免辅导员面对一长串用不上的功能。 */
const managerNav = computed(() => ['admin', 'leader'].includes(user.value?.role || ''));
const systemNav = computed(() => user.value?.role === 'sysadmin');
const counselorNav = computed(() => ['counselor', 'mentor'].includes(user.value?.role || ''));
const navTitle = computed(() =>
	managerNav.value ? '管理工作空间' : systemNav.value ? '系统运维工作空间' : counselorNav.value ? '辅导员工作空间' : '我的工作空间'
);
const menuAliases: Record<string, string> = {
	dashboard: '首页 今天 待办 工作进度',
	registry: '查学生 找学生 学生信息 学生资料 学号 花名册 通讯录 导入学生 导出学生 打印名单',
	'college-tasks': '交材料 交任务 提交任务 提交成果 任务进度 任务统计 汇报 总结 PPT 补时 延期 批量导入任务 导出任务 打印任务',
	collections: '收材料 收表格 收集表格 审核材料 学生提交',
	leaves: '批假 审批请假 销假 回校',
	'college-care': '找学生谈话 谈心谈话 心理 关怀 关注学生 回访',
	'college-dorms': '寝室 床位 住宿 查寝 卫生 退宿 导入床位 导出宿舍 打印宿舍',
	'college-party': '团员 党员 发展对象',
	chat: '发消息 聊天 发文件 联系老师 表情 表情包 emoji 导入文件 导出聊天 打印聊天',
	notices: '公告 通知 历史公告',
	ai: 'AI 写作 润色 新闻 写新闻 API 密钥',
	tasks: '个人安排 备忘 备忘录',
	settings: '改密码 修改密码 提醒设置 关闭提醒 主题 颜色',
	governance: '改账号 重置密码 用户管理 启用 停用',
};
function matchesMenu(item: { id: string; title: string; desc: string }) {
	const normalize = (value: string) => value.toLocaleLowerCase().replace(/\s+/g, '');
	return normalize(item.title + item.desc + (menuAliases[item.id] || '')).includes(normalize(menuSearch.value));
}
function openCommandPalette() {
	if (!user.value || user.value.mustChangePassword || user.value.roleRestricted) return;
	commandOpen.value = true;
}
function chooseCommand(id: string) {
	commandOpen.value = false;
	navigate(id);
}
function focusWorkContent() {
	document.getElementById('workspace-content')?.focus();
}
function handleGlobalKey(event: KeyboardEvent) {
	if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
		event.preventDefault();
		openCommandPalette();
	} else if (event.key === 'Escape' && commandOpen.value) {
		commandOpen.value = false;
	} else if (event.key === 'Escape' && menuOpen.value) {
		menuOpen.value = false;
		nextTick(() => document.querySelector<HTMLButtonElement>('.mobile-menu')?.focus());
	}
}
const menuGroups = computed(() => {
	const groups = managerNav.value
		? [
				{ title: '管理与审核', ids: ['dashboard', 'college-tasks', 'governance', 'messages'] },
				{ title: '系统治理', ids: ['maintenance', 'audit'] },
				{ title: '沟通协作', ids: ['chat', 'notices'] },
				{ title: '更多业务', ids: ['registry', 'tasks', 'collections', 'leaves', 'college-dorms', 'college-party', 'college-archive', 'erp', 'support', 'ai', 'service-requests', 'office', 'requests', 'advanced'] },
				{ title: '个人设置', ids: ['settings'] },
	  ]
		: systemNav.value
		? [
				{ title: '系统运维', ids: ['dashboard', 'maintenance', 'messages'] },
				{ title: '我的任务', ids: ['college-tasks', 'tasks'] },
				{ title: '沟通协作', ids: ['chat', 'notices'] },
				{ title: '个人设置', ids: ['settings'] },
		  ]
		: counselorNav.value
		? [
				{ title: '我的工作', ids: ['dashboard', 'college-tasks', 'messages'] },
				{ title: '学生工作', ids: ['registry', 'collections', 'leaves', 'college-care'] },
				{ title: '沟通协作', ids: ['chat', 'notices', 'ai'] },
				{ title: '更多业务', ids: ['tasks', 'college-dorms', 'college-party', 'college-archive', 'requests', 'office', 'erp', 'support', 'service-requests', 'advanced'] },
				{ title: '个人设置', ids: ['settings'] },
		  ]
		: [
				{ title: '我的工作', ids: ['dashboard', 'college-tasks', 'messages'] },
				{ title: '常用功能', ids: ['registry', 'notices', 'requests', 'office'] },
				{ title: '沟通协作', ids: ['chat', 'ai'] },
				{ title: '更多业务', ids: ['tasks', 'collections', 'leaves', 'college-dorms', 'college-party', 'college-archive', 'erp', 'support', 'service-requests', 'advanced'] },
				{ title: '个人设置', ids: ['settings'] },
		  ];
	return groups
		.map(group => ({
			...group,
			items: group.ids
				.map(id => menu.value.find(item => item.id === id))
				.filter((item): item is NonNullable<typeof item> => !!item && matchesMenu(item)),
		}))
		.filter(group => group.items.length);
});
const classNames = computed(
	() =>
		[
			...new Set(
				users.value
					.filter((u) => u.role === 'student')
					.map((u) => u.className)
					.filter(Boolean)
			),
		] as string[]
);
const pendingTasks = computed(() => tasks.value.filter((t) => t.status !== 'completed'));
const overdue = (item: Item) => !!item.dueAt && new Date(item.dueAt).getTime() < Date.now() && !['completed', 'withdrawn'].includes(item.status);
const overdueTasks = computed(() => pendingTasks.value.filter(overdue));
const todayTasks = computed(() => pendingTasks.value.filter((t) => t.dueAt && new Date(t.dueAt).toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' }) === today.value.toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' })));
const weekTasks = computed(() =>
	pendingTasks.value.filter((t) => {
		const d = new Date(t.dueAt).getTime();
		return d >= Date.now() && d <= Date.now() + 7 * 86400000;
	})
);
const pendingRequests = computed(() => requests.value.filter((r) => r.status === 'pending'));
const myReceipt = (notice: Item) => (notice.receipts || []).find((r: Item) => r.userId === user.value?.id) || notice.myReceipt || {};
const unreadNotices = computed(() =>
	isStaff.value ? [] : notices.value.filter((n) => ['published', 'overdue'].includes(n.status) && !myReceipt(n).readAt)
);
const unresolvedMessages = computed(() => messages.value.filter((m) => !m.resolved));
const canManageNotice = (notice: Item) => isAdmin.value || (isStaff.value && notice.ownerId === user.value?.id);
const activeNoticeCount = computed(() => notices.value.filter((n) => ['published', 'overdue'].includes(n.status)).length);
const countReceipts = (notice: Item, field: string) => (notice.receipts || []).filter((r: Item) => r[field]).length;
const filteredNotices = computed(() =>
	notices.value.filter(
		(n) =>
			matchSearch(n, ['title', 'content', 'className']) &&
			(tab.value === 'all' || (tab.value === 'history' ? ['expired','withdrawn','archived'].includes(n.status) || (!!n.expiresAt && new Date(n.expiresAt).getTime() < Date.now()) : tab.value === 'unread' ? !myReceipt(n).readAt : n.status === tab.value))
	)
);
const filteredTasks = computed(() =>
	tasks.value.filter(
		(t) =>
			((isStaff.value && personalScope.value === 'all') || t.assigneeId === user.value?.id) &&
			matchSearch(t, ['title', 'description', 'assigneeName']) &&
			(tab.value === 'all' ||
				(tab.value === 'open'
					? t.status !== 'completed'
					: tab.value === 'overdue'
					? overdue(t)
					: tab.value === 'today'
					? todayTasks.value.includes(t)
					: tab.value === 'week'
					? weekTasks.value.includes(t)
					: t.status === tab.value))
	)
);
const filteredRequests = computed(() =>
	requests.value.filter(
		(r) => matchSearch(r, ['title', 'content', 'userName', 'className', 'applicantName']) && (tab.value === 'all' || r.status === tab.value)
	)
);
const filteredUsers = computed(() =>
	users.value.filter((u) => (page.value !== 'students' || u.role === 'student') && matchSearch(u, ['name', 'username', 'className']))
);
const statusNames: Item = {
	pending: '待处理',
	in_progress: '进行中',
	completed: '已完成',
	scheduled: '待发布',
	published: '已发布',
	overdue: '已截止',
	withdrawn: '已撤回',
	returned: '退回修改',
	approved: '已通过',
	rejected: '已驳回',
	cancelled: '已取消',
	closed: '已办结',
};
const requestNames: Item = { consultation: '咨询求助', material: '材料提交', aid: '资助咨询' };
const priorityNames: Item = { normal: '普通', high: '重要', urgent: '紧急' };
const activeTasks = computed(() =>
	[...pendingTasks.value].sort((a, b) => new Date(a.dueAt || '9999').getTime() - new Date(b.dueAt || '9999').getTime()).slice(0, 5)
);

function matchSearch(item: Item, keys: string[]) {
	return (
		!search.value ||
		keys.some((key) =>
			String(item[key] || '')
				.toLowerCase()
				.includes(search.value.toLowerCase())
		)
	);
}
function formatDate(value: string | undefined, short = false) {
	if (!value) return '未设置';
	const date = new Date(value);
	return isNaN(date.getTime())
		? value
		: date.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', ...(short ? {} : { hour: '2-digit', minute: '2-digit', hour12: false }) });
}
function statusType(status: string): 'success' | 'warning' | 'danger' | 'info' | '' {
	return ['completed', 'approved', 'closed', 'published'].includes(status)
		? 'success'
		: ['returned', 'pending', 'scheduled', 'in_progress'].includes(status)
		? 'warning'
		: ['rejected', 'overdue'].includes(status)
		? 'danger'
		: 'info';
}
function navigate(id: string, filter = '', recordId = '') {
	if (page.value === 'chat' && chatBusy.value && id !== 'chat') { ElMessage.warning('请发送或清空聊天草稿，并等待附件处理完成后再离开。'); return; }
	if (id === 'users') id = 'governance';
	if (!menu.value.some(item => item.id === id)) id = user.value?.roleRestricted ? 'erp' : 'dashboard';
	const routeChanged = page.value !== id;
	page.value = id;
	location.hash = id;
	tab.value = filter || (id === 'tasks' || id === 'college-tasks' ? 'open' : 'all');
	initialRecordId.value = recordId;
	if (recordId) ++recordRequest.value;
	initialRecordTarget.value = '';
	if (id === 'tasks') personalScope.value = 'mine';
	menuSearch.value = '';
	if (menuGroups.value.find(group => group.title === '更多业务')?.items.some(item => item.id === id)) moreBusinessOpen.value = true;
	search.value = '';
	menuOpen.value = false;
	if (routeChanged) nextTick(() => {
		window.scrollTo({ top: 0, behavior: 'auto' });
		if (!recordId) document.querySelector<HTMLElement>('.page-title h1')?.focus({ preventScroll: true });
	});
}
async function dashboardNavigate(route: string, filter = '', recordId = '') {
	navigate(route, filter, recordId);
	if (route === 'office' || route === 'erp') initialRecordTarget.value = filter === 'all' ? '' : filter;
	if (page.value !== route || !recordId) return;
	await nextTick();
	if (route === 'tasks') {
		const task = tasks.value.find(item => item.id === recordId);
		if (task) openTask(task);
		else ElMessage.info('事项已变化，请刷新列表查看最新记录。');
	} else if (route === 'notices') openRelatedNotice(recordId);
}
function officeNavigate(section: string) {
 const routes: Item = { logs: 'office', holidays: 'office', tasks: 'tasks', erp: 'erp', leaves: 'leaves', requests: 'requests', collections: 'collections', students: 'registry', notices: 'notices' };
 navigate(routes[section] || (menu.value.some(m => m.id === section) ? section : 'advanced'));
}
function applyTheme(value: string) {
	theme.value = themeOptions.some((t) => t.id === value) ? value : 'blue';
	document.documentElement.dataset.theme = theme.value;
}
function clearSession() {
	setToken('');
	user.value = null;
	users.value = [];
	directory.value = [];
	notices.value = [];
	tasks.value = [];
	requests.value = [];
	audit.value = [];
	messages.value = [];
	chatUnread.value = 0;
	chatBusy.value = false;
	menuSearch.value = '';
	commandOpen.value = false;
	startupError.value = '';
	moreBusinessOpen.value = false;
	initialRecordId.value = '';
	personalScope.value = 'mine';
	settings.value = {};
	roleState.value = {activeRoleId:'primary',roles:[]};
	requestedRole.value = 'primary';
	selected.value = null;
	Object.keys(form).forEach((key) => delete form[key]);
	passwordOpen.value = false;
	Object.assign(passwordForm, { oldPassword: '', newPassword: '', repeat: '' });
	dialog.value = '';
}
function expired() {
	clearSession();
	error.value = '登录已过期，请重新登录。';
}
async function signIn() {
	if (saving.value) return;
	if (!loginForm.username.trim() || !loginForm.password) return ElMessage.warning('请填写账号和密码。');
	saving.value = true;
	error.value = '';
	try {
		const data = await api('/auth/login', { method: 'POST', body: loginForm });
		setToken(data.token);
		user.value = data.user;
		applyTheme(data.user.theme);
		loginForm.password = '';
		navigate('dashboard');
		passwordOpen.value = !!data.user.mustChangePassword;
		await loadData();
	} catch (e: any) {
		error.value = e.message;
	} finally {
		saving.value = false;
	}
}
async function logout() {
	if (chatBusy.value) {
		try { await ElMessageBox.confirm('退出会丢弃未发送的聊天草稿。已上传但未发送的附件不会发给对方。', '确认退出', {confirmButtonText:'退出登录',cancelButtonText:'继续编辑'}); }
		catch { return; }
	}
	// Capture the current token before clearing local data, even if the network is unavailable.
	const revocation = api('/auth/logout', { method: 'POST' });
	clearSession();
	try {
		await revocation;
	} catch (e: any) {
		if (!getToken() && e.code !== 'SESSION_CHANGED' && e.status !== 401) {
			ElMessage.warning('已退出本机。服务器未确认注销；如使用公共设备，请关闭浏览器，并在网络恢复后到登录设备中撤销旧会话。');
		}
	}
}
async function switchRole() {
	if (chatBusy.value) return ElMessage.warning('请发送或清空聊天草稿，并等待附件处理完成。');
	if (!user.value || requestedRole.value === (user.value.activeRoleId || 'primary') || saving.value) return;
	try {
		await ElMessageBox.confirm('切换后将清除当前页面未保存内容，并使此账号的旧登录会话失效。请先保存需要保留的草稿。', '切换办公角色', { confirmButtonText:'确认切换', cancelButtonText:'取消' });
		saving.value = true;
		const data = await api('/auth/switch-role', {method:'POST',body:{roleId:requestedRole.value}});
		clearSession();
		await nextTick();
		setToken(data.token);user.value=data.user;applyTheme(data.user.theme);
		navigate(data.user.roleRestricted ? 'erp' : 'dashboard');
		await loadData();ElMessage.success('已切换角色，当前仅使用该角色的权限。');
	} catch (e:any) { if (e?.message) ElMessage.error(e.message); }
	finally { saving.value=false; }
}
async function loadData() {
	if (!user.value) return;
	if (user.value.mustChangePassword) return;
	chatRefresh.value++;
	dashboardRefresh.value++;
	refreshChatUnread();
	const identityToken = getToken();
	loading.value = true;
	error.value = '';
	if (user.value.roleRestricted) {
		try { const data = await api('/auth/roles'); if(getToken()===identityToken){roleState.value=data;requestedRole.value=data.activeRoleId;} }
		catch(e:any){if(getToken()===identityToken)error.value=e.message;}
		finally{loading.value=false;}
		return;
	}
	const entries: [string, any][] = [
		['/auth/roles', roleState],
		['/notices', notices],
		['/tasks', tasks],
		['/requests', requests],
		['/settings', settings],
		['/messages', messages],
	];
	entries.push(['/users', users]);
	if (!['student','assistant'].includes(user.value.role)) entries.push(['/directory', directory]);
	if (isSuperAdmin.value) entries.push(['/audit', audit]);
	const results = await Promise.allSettled(
		entries.map(async ([path, target]) => {
			const value = await api(path);
			if(getToken()===identityToken) target.value = value;
		})
	);
	if (getToken() !== identityToken) return;
	requestedRole.value=roleState.value.activeRoleId;
	const failures = results.filter((r) => r.status === 'rejected') as PromiseRejectedResult[];
	if (failures.length)
		error.value = failures
			.map((r) => r.reason?.message || '数据加载失败')
			.filter((v, i, a) => a.indexOf(v) === i)
			.join('；');
	loading.value = false;
}
async function saveTheme(value: string) {
	if (!user.value || saving.value) return;
	saving.value = true;
	try {
		const updated = await api('/auth/theme', { method: 'PUT', body: { theme: value } });
		user.value = updated;
		applyTheme(updated.theme);
		ElMessage.success('主题已保存，并会同步到其他设备。');
	} catch (e: any) {
		ElMessage.error(e.message);
	} finally {
		saving.value = false;
	}
}
async function changePassword() {
	if (!/^(?=.*[A-Za-z])(?=.*\d).{12,128}$/.test(passwordForm.newPassword)) return ElMessage.warning('新密码需要至少 12 位，并包含字母和数字。');
	if (passwordForm.newPassword !== passwordForm.repeat) return ElMessage.warning('两次输入的新密码不一致。');
	if (!passwordForm.oldPassword) return ElMessage.warning('请填写当前密码。');
	saving.value = true;
	try {
		await api('/auth/password', { method: 'POST', body: { oldPassword: passwordForm.oldPassword, newPassword: passwordForm.newPassword } });
		clearSession();
		Object.assign(passwordForm, { oldPassword: '', newPassword: '', repeat: '' });
		ElMessage.success('密码已修改，请使用新密码登录。');
	} catch (e: any) {
		ElMessage.error(e.message);
	} finally {
		saving.value = false;
	}
}
function newDialog(kind: string, existing?: Item) {
	selected.value = existing || null;
	Object.keys(form).forEach((k) => delete form[k]);
	uploading.value = false;
	if (kind === 'notice')
		Object.assign(form, { title: '', content: '', className: '', requiresConfirmation: true, dueAt: '', publishAt: '', attachmentIds: [] });
	if (kind === 'task') Object.assign(form, { title: '', description: '', assigneeId: '', dueAt: '', priority: 'normal', attachmentIds: [] });
	if (kind === 'request')
		Object.assign(
			form,
			{ type: 'leave', title: '', content: '', startAt: '', endAt: '', attachmentIds: [] },
			existing ? JSON.parse(JSON.stringify(existing)) : {}
		);
	if (kind === 'request' && existing)
		for (const key of ['startAt', 'endAt']) {
			if (form[key]) {
				const date = new Date(form[key]);
				form[key] = new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
			}
		}
	if (kind === 'user')
		Object.assign(
			form,
			{ username: '', name: '', password: '', role: 'student', className: '', enabled: true },
			existing ? { ...existing, password: '' } : {}
		);
	dialog.value = kind;
}
async function saveForm() {
	if (uploading.value) return ElMessage.warning('附件仍在上传，请稍等。');
	const kind = dialog.value;
	if (kind !== 'user' && !String(form.title || '').trim()) return ElMessage.warning('请填写标题。');
	if (kind === 'notice' && !String(form.content || '').trim()) return ElMessage.warning('请填写公告内容。');
	if (kind === 'task' && !form.assigneeId) return ElMessage.warning('请选择任务负责人。');
	if (kind === 'request' && !String(form.content || '').trim()) return ElMessage.warning('请填写申请说明。');
	if (kind === 'request' && form.type === 'leave' && (!form.startAt || !form.endAt || new Date(form.endAt) <= new Date(form.startAt)))
		return ElMessage.warning('请填写正确的请假起止时间。');
	if (kind === 'user') {
		if (!form.username?.trim() || !form.name?.trim()) return ElMessage.warning('请填写账号和姓名。');
		if (form.role !== 'admin' && !form.className?.trim()) return ElMessage.warning('学生与辅导员账号需要填写班级范围。');
		if (!selected.value && !/^(?=.*[A-Za-z])(?=.*\d).{12,128}$/.test(form.password))
			return ElMessage.warning('初始密码需要至少 12 位，并包含字母和数字。');
	}
	saving.value = true;
	try {
		const paths: Item = { notice: '/notices', task: '/tasks', request: '/requests', user: '/users' };
		const editing = !!selected.value && ['user', 'request'].includes(kind);
		const payload = { ...form };
		if (kind === 'user' && editing) delete payload.password;
		await api(paths[kind] + (editing ? `/${selected.value!.id}` : ''), { method: editing ? 'PUT' : 'POST', body: payload });
		dialog.value = '';
		ElMessage.success(kind === 'notice' && form.publishAt ? '公告已加入定时发布。' : '保存成功。');
		await loadData();
	} catch (e: any) {
		ElMessage.error(e.message);
	} finally {
		saving.value = false;
	}
}
async function openNotice(notice: Item) {
	selected.value = notice;
	dialog.value = 'noticeDetail';
	if (!isStaff.value && ['published', 'overdue'].includes(notice.status) && !myReceipt(notice).readAt) {
		try {
			await api(`/notices/${notice.id}/read`, { method: 'POST' });
			await loadData();
			selected.value = notices.value.find((n) => n.id === notice.id) || notice;
		} catch (e: any) {
			ElMessage.error(e.message);
		}
	}
}
async function noticeAction(action: string) {
	if (!selected.value) return;
	let reason = '';
	if (action === 'withdraw') {
		try {
			const result = await ElMessageBox.prompt('填写撤回原因。撤回后停止后续提醒，历史回执保留。', '撤回公告', {
				type: 'warning',
				inputType: 'textarea',
				inputValidator: value => !!value?.trim() || '请填写撤回原因',
				confirmButtonText: '确认撤回',
				cancelButtonText: '取消',
			});
			reason = result.value.trim();
		} catch {
			return;
		}
	}
	saving.value = true;
	try {
		const id = selected.value.id;
		const result = await api(`/notices/${id}/${action}`, { method: 'POST', body: { version: selected.value.version, reason: action === 'withdraw' ? reason : undefined } });
		ElMessage.success(
			action === 'confirm'
				? '已主动确认。'
				: action === 'remind'
				? `本次提醒 ${result.sent} 人；已完成或 24 小时内提醒过的 ${result.skipped} 人已跳过。`
				: '公告已撤回。'
		);
		await loadData();
		selected.value = notices.value.find((n) => n.id === id) || selected.value;
	} catch (e: any) {
		ElMessage.error(e.message);
	} finally {
		saving.value = false;
	}
}
function openRelatedNotice(id: string) {
	const notice = notices.value.find((n) => n.id === id);
	if (notice) openNotice(notice);
	else {
		navigate('notices');
		ElMessage.info('请刷新列表，查看公告的最新状态。');
	}
}
function openTask(task: Item) {
	if (task.source === 'notice' || task.noticeId) return openRelatedNotice(task.noticeId);
	selected.value = task;
	Object.assign(form, { status: task.status, comment: '' });
	dialog.value = 'taskDetail';
}
async function updateTask() {
	if (!selected.value) return;
	saving.value = true;
	try {
		await api(`/tasks/${selected.value.id}`, { method: 'PUT', body: { status: form.status, comment: form.comment } });
		dialog.value = '';
		ElMessage.success('任务进度已更新。');
		await loadData();
	} catch (e: any) {
		ElMessage.error(e.message);
	} finally {
		saving.value = false;
	}
}
function openRequest(request: Item) {
	if (request.type === 'leave') { navigate('leaves'); return; }
	selected.value = request;
	form.comment = '';
	dialog.value = 'requestDetail';
}
async function requestAction(action: string) {
	if (!selected.value) return;
	if (['reject', 'return'].includes(action) && !form.comment?.trim()) return ElMessage.warning('请填写原因，方便学生了解或补正。');
	saving.value = true;
	try {
		await api(`/requests/${selected.value.id}/action`, { method: 'POST', body: { action, comment: form.comment } });
		dialog.value = '';
		ElMessage.success('办理结果已保存。');
		await loadData();
	} catch (e: any) {
		ElMessage.error(e.message);
	} finally {
		saving.value = false;
	}
}
async function toggleUser(account: Item) {
	try {
		await ElMessageBox.confirm(`确定${account.enabled ? '停用' : '启用'} ${account.name} 的账号？`, '账号状态', {
			confirmButtonText: '确认',
			cancelButtonText: '取消',
			type: 'warning',
		});
		await api(`/users/${account.id}`, { method: 'PUT', body: { ...account, enabled: !account.enabled } });
		ElMessage.success('账号状态已更新。');
		await loadData();
	} catch (e: any) {
		if (e?.message) ElMessage.error(e.message);
	}
}
async function resetPassword(account: Item) {
	try {
		const result = await ElMessageBox.prompt(`为 ${account.name} 设置临时密码，用户下次登录必须修改。`, '重置密码', {
			inputType: 'password',
			inputPattern: /^(?=.*[A-Za-z])(?=.*\d).{12,128}$/,
			inputErrorMessage: '至少 12 位，包含字母和数字',
			confirmButtonText: '重置',
			cancelButtonText: '取消',
		});
		await api(`/users/${account.id}/reset-password`, { method: 'POST', body: { password: result.value } });
		ElMessage.success('密码已重置，请通过校内安全方式告知本人。');
	} catch (e: any) {
		if (e?.message) ElMessage.error(e.message);
	}
}
function onHash() {
	if (page.value === 'chat' && chatBusy.value) { location.hash = 'chat'; return; }
	const target = location.hash.slice(1) === 'users' ? 'governance' : location.hash.slice(1);
	if (target === page.value) return;
	if (user.value) navigate(target);
}
let refreshTimer: ReturnType<typeof setInterval>;
let chatTimer: ReturnType<typeof setInterval>;
let chatPolling = false;
async function refreshChatUnread() {
	if (!user.value || user.value.mustChangePassword || user.value.roleRestricted || document.hidden || chatPolling) return;
	const token = getToken();
	chatPolling = true;
	try { const result = await api('/chat/unread'); if (getToken() === token) chatUnread.value = result.unreadCount; }
	catch { /* The chat page provides a visible retry state when opened. */ }
	finally { chatPolling = false; }
}
async function resumeSession() { if (!user.value || document.hidden || saving.value) return; const token=getToken(); try { const currentUser = await api('/auth/me'); if(getToken()!==token)return; user.value = currentUser; applyTheme(currentUser.theme); if (!dialog.value) await loadData(); } catch (e: any) { if(getToken()===token)error.value = e.message; } }
async function startSession() {
	if (reconnecting.value) return;
	reconnecting.value = true;
	startupError.value = '';
	try {
		if (getToken()) {
			user.value = await api('/auth/me');
			applyTheme(user.value!.theme);
			passwordOpen.value = !!user.value!.mustChangePassword;
			if (page.value === 'users' && isAdmin.value) navigate('governance');
			else if (!menu.value.some(m => m.id === page.value)) navigate(user.value!.roleRestricted ? 'erp' : 'dashboard');
			starting.value = false;
			await loadData();
		} else starting.value = false;
	} catch (e: any) {
		if (getToken()) startupError.value = '暂时无法连接服务，请检查网络后重试。';
		else { error.value = e.message; starting.value = false; }
	} finally { reconnecting.value = false; }
}
onMounted(async () => {
	window.addEventListener('session-expired', expired);
	window.addEventListener('hashchange', onHash);
	window.addEventListener('keydown', handleGlobalKey);
	document.addEventListener('visibilitychange', resumeSession);
	await startSession();
	chatTimer = setInterval(refreshChatUnread, 10000);
	refreshTimer = setInterval(() => {
		today.value = new Date();
		if (user.value && !document.hidden && !dialog.value && !loading.value && !saving.value) resumeSession();
	}, 60000);
});
onUnmounted(() => {
	window.removeEventListener('session-expired', expired);
	window.removeEventListener('hashchange', onHash);
	window.removeEventListener('keydown', handleGlobalKey);
	document.removeEventListener('visibilitychange', resumeSession);
	clearInterval(refreshTimer);
	clearInterval(chatTimer);
});
watch([page, user], () => {
	document.title = user.value ? `${current.value.title} · ${systemName}` : systemName;
}, { immediate: true });
</script>

<style scoped>
.navigation-group .more-business-toggle{font-size:12px;color:var(--muted);min-height:32px;justify-content:space-between;padding:8px 12px}.navigation-group .more-business-toggle:hover{color:var(--primary)}
</style>

<template>
	<div v-if="starting" class="starting">
		<span class="brand-mark">学</span>
		<p role="status">{{ startupError || '正在打开工作台…' }}</p>
		<div v-if="startupError"><el-button type="primary" :loading="reconnecting" @click="startSession">重新连接</el-button><el-button @click="clearSession(); starting = false">返回登录</el-button></div>
	</div>
	<main v-else-if="!user" class="login-page">
		<section class="login-story">
			<div class="login-brand"><span class="brand-mark">学</span><strong><span>智能工程技术学院</span><span>辅导员系统</span></strong><span>COUNSELOR SYSTEM</span></div>
			<div class="login-story-copy">
				<span class="login-kicker">江苏财经职业技术学院</span>
				<h1>辅导员工作台<span class="login-title-dot">.</span></h1>
			</div>
			<EngineeringIllustration />
			<div class="login-capabilities"><span>工作任务</span><i></i><span>学生事务</span><i></i><span>协同办公</span></div>
		</section>
		<section class="login-side">
			<ActivateAccount v-if="activationOpen" @completed="loginForm.username = $event; activationOpen = false" @cancel="activationOpen = false" />
			<div v-else class="login-card">
				<div class="login-card-heading"><div><span class="eyebrow">账号登录</span><h2>欢迎回来</h2></div><span class="login-wave" aria-hidden="true">✦</span></div>
				<p class="muted">请使用学校分配的账号登录</p>
				<el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="mb20" />
				<form @submit.prevent="signIn">
					<label class="field-label" for="login-user">账号</label
					><el-input
						id="login-user"
						v-model="loginForm.username"
						size="large"
						autocomplete="username"
						placeholder="请输入管理员分配的账号"
						:maxlength="64"
					/><label class="field-label" for="login-password">密码</label
					><el-input
						id="login-password"
						v-model="loginForm.password"
						size="large"
						type="password"
						show-password
						autocomplete="current-password"
						placeholder="请输入密码"
					/><el-button native-type="submit" type="primary" size="large" class="login-submit" :loading="saving"
						>进入系统 <el-icon><Right /></el-icon
					></el-button>
				</form>
				<el-button text @click="activationOpen = true">使用管理员邀请激活账号</el-button>
				<div class="account-help">
					<el-icon><InfoFilled /></el-icon><span>忘记密码或账号无法登录？请联系学院管理员。</span>
				</div>
			</div>
			<div class="login-foot">江苏财经职业技术学院 · 智能工程技术学院</div>
		</section>
	</main>
	<div v-else class="workspace">
		<a class="skip-to-content" href="#workspace-content" @click.prevent="focusWorkContent">跳到工作内容</a>
		<div v-if="menuOpen" class="nav-overlay" @click="menuOpen = false"></div>
		<aside id="main-navigation" class="sidebar" :class="{ 'is-open': menuOpen }">
			<a class="brand" href="#dashboard" @click.prevent="navigate('dashboard')"
				><span class="brand-mark">学</span>
				<div><strong><span>智能工程技术学院</span><span>辅导员系统</span></strong><small>COUNSELOR SYSTEM</small></div></a
			>
			<div class="workspace-label">{{ navTitle }}</div>
			<div class="navigation-search"><el-input v-model="menuSearch" placeholder="查找功能" aria-label="查找功能" clearable prefix-icon="Search" /></div>
			<nav aria-label="主导航">
				<section v-for="group in menuGroups" :key="group.title" class="navigation-group" :aria-label="group.title">
				<button v-if="group.title === '更多业务'" class="more-business-toggle" :aria-expanded="moreBusinessOpen || !!menuSearch.trim()" @click="moreBusinessOpen = !moreBusinessOpen"><span>更多业务</span><el-icon><component :is="moreBusinessOpen || menuSearch.trim() ? 'ArrowDown' : 'ArrowRight'" /></el-icon></button>
				<h2 v-else>{{ group.title }}</h2>
				<template v-if="group.title !== '更多业务' || moreBusinessOpen || menuSearch.trim()">
				<button v-for="item in group.items" :key="item.id" :class="{ active: page === item.id }" :aria-current="page === item.id ? 'page' : undefined" @click="navigate(item.id)">
					<el-icon><component :is="item.icon" /></el-icon><span>{{ item.title }}</span
					><span v-if="item.id === 'tasks' && overdueTasks.length" class="nav-count">{{ overdueTasks.length }}</span>
					<span v-if="item.id === 'chat' && chatUnread" class="nav-count">{{ chatUnread > 99 ? '99+' : chatUnread }}</span>
				</button>
				</template>
				</section>
				<div v-if="!menuGroups.length" class="navigation-empty"><p>没有找到这个功能，可试试“查学生”“收材料”或“改密码”。</p><el-button text @click="menuSearch = ''">显示全部功能</el-button></div>
			</nav>
			<div class="sidebar-bottom">
				<div class="free-message">
					<el-icon><Connection /></el-icon>
					<div><b>校内沟通与协作</b><small>聊天沟通 · 公告确认 · 待办办理</small></div>
				</div>
				<button class="sidebar-user" @click="navigate('settings')">
					<UserAvatar :user="user"/>
					<div>
						<b>{{ user.name }}</b
						><small>{{user.role==='admin'&&user.adminLevel==='administrator'?'管理员':user.role==='counselor'&&user.staffType==='part_time'?'兼职辅导员':roles[user.role]}}</small>
					</div>
					<el-icon><ArrowRight /></el-icon>
				</button>
			</div>
		</aside>
		<div class="main-shell" :data-page="page">
			<header class="topbar">
				<div class="topbar-left">
					<button class="icon-button mobile-menu" :aria-label="menuOpen ? '关闭导航' : '打开导航'" aria-controls="main-navigation" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen">
						<el-icon><Menu /></el-icon></button
					><span class="breadcrumb"
						><span class="breadcrumb-context">{{ navTitle }} <span>/</span></span> <b>{{ current.title }}</b></span
					>
				</div>
				<div class="topbar-actions">
				<button v-if="!user.mustChangePassword && !user.roleRestricted" class="global-search-trigger" aria-label="查找功能，快捷键 Ctrl K" @click="openCommandPalette"><el-icon><Search /></el-icon><span>查找功能</span><kbd>Ctrl K</kbd></button>
					<button v-if="!user.roleRestricted" class="notification-button chat-entry" aria-label="查看内部聊天" @click="navigate('chat')"><el-icon><ChatDotRound /></el-icon><span v-if="chatUnread" class="chat-badge">{{ chatUnread > 99 ? '99+' : chatUnread }}</span></button>
					<span class="top-date">{{ todayLabel }}</span
					><button class="notification-button" aria-label="查看站内提醒" @click="navigate('messages')">
						<el-icon><Bell /></el-icon><i v-if="unresolvedMessages.length || unreadNotices.length"></i>
					</button>
					<div v-if="page === 'settings'" class="theme-shortcuts" aria-label="主题颜色">
						<button
							v-for="skin in themeOptions"
							:key="skin.id"
							:title="skin.label"
							:aria-label="`切换${skin.label}主题`"
							:aria-pressed="theme === skin.id"
							:class="{ selected: theme === skin.id }"
							:style="{ backgroundColor: skin.color }"
							:disabled="saving || user.mustChangePassword"
							@click="saveTheme(skin.id)"
						></button>
					</div>
					<el-button text @click="logout">退出</el-button>
				</div>
			</header>
			<main id="workspace-content" class="content" tabindex="-1">
				<div class="page-title">
					<div>
						<div v-if="page === 'dashboard'" class="eyebrow">今天的工作</div>
						<h1 tabindex="-1">
							{{
								page === 'dashboard' ? `${user.name}，${greeting}好` : current.title
							}}
						</h1>
						<p v-if="page !== 'dashboard'" class="page-description">{{ current.desc }}</p>
						<p v-else class="page-description">{{ managerNav ? '查看全员进度，及时处理需要你审核的事项。' : '今天要办的事，都在这里。' }}</p>
					</div>
					<div class="page-actions">
						<TaskReminder v-if="!user.mustChangePassword && !user.roleRestricted" :key="user.id" @navigate="navigate" />
						<el-button v-if="['notices', 'tasks', 'requests', 'messages', 'settings', 'audit'].includes(page)" :loading="loading" @click="loadData"
							><el-icon><Refresh /></el-icon>刷新</el-button
						><el-button v-if="isStaff && page === 'notices'" type="primary" @click="newDialog('notice')"
							><el-icon><Plus /></el-icon>发布公告</el-button
						><el-button v-if="isStaff && page === 'tasks'" type="primary" @click="newDialog('task')"
							><el-icon><Plus /></el-icon>创建任务</el-button
						><el-button v-if="user.role === 'student' && page === 'requests'" type="primary" @click="newDialog('request')"
							><el-icon><Plus /></el-icon>发起申请</el-button
						>
					</div>
				</div>
				<el-alert
					v-if="error"
					:title="error"
					description="部分数据可能尚未更新，请恢复连接后重新加载。"
					type="error"
					show-icon
					:closable="false"
					class="mb20"
				><el-button :loading="loading" @click="loadData">重新加载基础数据</el-button></el-alert>
				<el-alert v-if="user.mustChangePassword" title="请先完成密码修改，再开始办理业务。" type="warning" :closable="false"/><div v-else-if="page === 'dashboard'" class="panel office-container"><WorkDashboard :key="identityKey" :user="user" :refresh-key="dashboardRefresh" @navigate="dashboardNavigate" /></div><div v-else-if="page === 'advanced'" class="panel office-container"><AdvancedOffice :user="user" :users="availablePeople" @navigate="officeNavigate" /></div>
				<div v-else-if="page === 'chat' && !user.roleRestricted" class="panel office-container"><InternalChat :key="user.id + ':' + (user.activeRoleId || 'primary')" :user="user" :refresh-key="chatRefresh" @unread-change="chatUnread = $event" @busy-change="chatBusy = $event" /></div>
				<div v-else-if="page.startsWith('college-')" class="panel office-container"><CollegeWork :key="identityKey + ':' + page" :user="user" :section="page.slice(8)" :initial-filter="tab" :initial-record-id="initialRecordId" :record-request="recordRequest" /></div>
				<div v-else-if="page === 'registry'" class="panel office-container"><StudentRegistry :user="user" :users="availablePeople" /></div>
				<div v-else-if="page === 'collections'" class="panel office-container"><CollectionDesk :key="identityKey" :user="user" :users="availablePeople" :initial-record-id="initialRecordId" /></div>
				<div v-else-if="(page === 'governance' || page === 'users') && !['student','assistant'].includes(user.role)" class="panel office-container"><AccountGovernance :user="user" :users="availablePeople" @refresh="loadData" /></div>
				<div v-else-if="page === 'erp'" class="panel office-container"><ErpOperations :user="user" :users="availablePeople" :initial-record-id="initialRecordId" :initial-module="initialRecordTarget" /></div>
				<div v-else-if="page === 'leaves'" class="panel office-container"><LeaveOperations :user="user" :users="availablePeople" :initial-record-id="initialRecordId" /></div>
				<div v-else-if="page === 'support'" class="panel office-container"><SupportTools :user="user" :users="availablePeople" /></div>
				<div v-else-if="page === 'ai'" class="panel office-container"><AiWorkbench :user="user" /></div>
				<div v-else-if="page === 'maintenance' && ['admin','sysadmin'].includes(user.role)" class="panel office-container"><SystemMaintenance :user="user" /></div>
				<div v-else-if="page === 'service-requests'" class="panel office-container"><ServiceRequests :user="user" :users="availablePeople" :initial-record-id="initialRecordId" /></div>
				<template v-else-if="page === 'notices'"
					><div class="panel">
						<div class="list-toolbar">
							<el-radio-group v-model="tab"
								><el-radio-button label="all">全部公告</el-radio-button
								><el-radio-button v-if="!isStaff" label="unread">未读</el-radio-button
								><el-radio-button label="published">已发布</el-radio-button
								><el-radio-button v-if="isStaff" label="scheduled">待发布</el-radio-button
								><el-radio-button label="withdrawn">已撤回</el-radio-button><el-radio-button label="history">历史公告</el-radio-button></el-radio-group
							><el-input v-model="search" placeholder="搜索公告标题、内容或班级" clearable prefix-icon="Search" class="search-input" />
						</div>
						<div class="announcement-grid" v-if="filteredNotices.length">
							<article class="announcement-card" v-for="notice in filteredNotices" :key="notice.id">
								<div class="card-top">
									<span class="category"
										><el-icon><Bell /></el-icon>{{ notice.className || '全部可见班级' }}</span
									><el-tag :type="statusType(notice.status)" size="small">{{ statusNames[notice.status] }}</el-tag>
								</div>
								<button class="text-link title-link" @click="openNotice(notice)">{{ notice.title }}</button>
								<p class="excerpt">{{ notice.content }}</p>
								<div class="card-meta">
									<span>发布 {{ formatDate(notice.publishAt || notice.createdAt) }}</span
									><span v-if="notice.dueAt">截止 {{ formatDate(notice.dueAt) }}</span>
								</div>
								<div class="announcement-foot">
									<span v-if="isStaff"
										>已读 {{ countReceipts(notice, 'readAt') }}/{{ notice.receipts?.length || 0 }} <i>·</i> 确认
										{{ countReceipts(notice, 'confirmedAt') }}</span
									><span v-else
										>{{ myReceipt(notice).readAt ? '已读' : '未读' }} <i>·</i>
										{{ notice.requiresConfirmation ? (myReceipt(notice).confirmedAt ? '已确认' : '待主动确认') : '无需确认' }}</span
									><el-button text type="primary" @click="openNotice(notice)"
										>查看详情 <el-icon><ArrowRight /></el-icon
									></el-button>
								</div>
							</article>
						</div>
						<el-empty v-else description="没有符合条件的公告" />
					</div>
					<p class="page-note">已读、主动确认和材料提交分别记录；阅读公告不会自动确认。</p></template
				>
				<template v-else-if="page === 'tasks'"
					><div class="panel">
						<div class="list-toolbar">
							<el-radio-group v-if="isStaff" v-model="personalScope" aria-label="个人待办范围"><el-radio-button label="mine">我的事项</el-radio-button><el-radio-button label="all">全部可见事项</el-radio-button></el-radio-group>
							<el-select v-model="tab" style="width: 160px"
								><el-option label="全部任务" value="all" /><el-option label="未完成" value="open" /><el-option label="今日到期" value="today" /><el-option
									label="未来七天"
									value="week" /><el-option label="逾期待办" value="overdue" /><el-option label="待处理" value="pending" /><el-option
									label="进行中"
									value="in_progress" /><el-option label="已完成" value="completed" /></el-select
							><el-input v-model="search" placeholder="搜索任务或负责人" clearable prefix-icon="Search" class="search-input" />
						</div>
						<el-table :data="filteredTasks" empty-text="暂无符合条件的任务" @row-click="openTask" class="clickable-table"
							><el-table-column label="任务" min-width="260"
								><template #default="{ row }"
									><div class="table-title">{{ row.title }}</div>
									<div class="table-sub">{{ row.description }}</div></template
								></el-table-column
							><el-table-column prop="assigneeName" label="负责人" min-width="110" /><el-table-column label="优先级" width="95"
								><template #default="{ row }"
									><el-tag :type="row.priority === 'urgent' ? 'danger' : row.priority === 'high' ? 'warning' : 'info'" size="small">{{
										priorityNames[row.priority] || '普通'
									}}</el-tag></template
								></el-table-column
							><el-table-column label="截止时间" min-width="140"
								><template #default="{ row }"
									><span :class="{ danger: overdue(row) }">{{ formatDate(row.dueAt) }}</span
									><small class="overdue-label" v-if="overdue(row)">已逾期</small></template
								></el-table-column
							><el-table-column label="状态" width="100"
								><template #default="{ row }"
									><el-tag :type="statusType(row.status)" size="small">{{ statusNames[row.status] }}</el-tag></template
								></el-table-column
							><el-table-column label="操作" width="95"
								><template #default="{ row }"><el-button text type="primary" @click.stop="openTask(row)">查看</el-button></template></el-table-column
							></el-table
						>
					</div></template
				>
				<template v-else-if="page === 'requests'"
					><el-alert
						v-if="isAdmin"
						title="请使用带班辅导员账号处理学生事务。超级管理员管理账号，不默认读取学生的私人申请。"
						type="info"
						:closable="false"
						show-icon
						class="mb20"
					/>
					<div class="panel">
						<div class="list-toolbar">
							<el-select v-model="tab" style="width: 160px"
								><el-option label="全部事务" value="all" /><el-option
									v-for="status in ['pending', 'returned', 'approved', 'rejected', 'cancelled', 'closed']"
									:key="status"
									:label="statusNames[status]"
									:value="status" /></el-select
							><el-input v-model="search" placeholder="搜索事务或学生" prefix-icon="Search" clearable class="search-input" />
						</div>
						<el-table :data="filteredRequests" empty-text="暂无事务申请" @row-click="openRequest" class="clickable-table"
							><el-table-column label="事务申请" min-width="250"
								><template #default="{ row }"
									><div class="table-title">{{ row.title }}</div>
									<div class="table-sub">{{ requestNames[row.type] }} · {{ row.className || '班级未设置' }}</div></template
								></el-table-column
							><el-table-column label="申请人" min-width="120"
								><template #default="{ row }">{{ row.userName || row.applicantName || row.name || '—' }}</template></el-table-column
							><el-table-column label="提交时间" min-width="140"
								><template #default="{ row }">{{ formatDate(row.createdAt) }}</template></el-table-column
							><el-table-column label="状态" width="110"
								><template #default="{ row }"
									><el-tag :type="statusType(row.status)">{{ statusNames[row.status] }}</el-tag></template
								></el-table-column
							><el-table-column label="操作" width="100"
								><template #default="{ row }"
									><el-button text type="primary" @click.stop="openRequest(row)">{{
										isStaff && row.status === 'pending' ? '办理' : '查看'
									}}</el-button></template
								></el-table-column
							></el-table
						>
					</div></template
				>
				<template v-else-if="page === 'office'"
					><div class="panel office-container"><OfficeExtensions :user="user" :initial-record-id="initialRecordId" :initial-section="initialRecordTarget" /></div></template
				><template v-else-if="page === 'messages'"
					><div class="panel">
						<div class="panel-heading">
							<div>
								<h2>站内提醒</h2>
								<p>公告和待办的办理提醒集中查看；与老师、同事沟通请进入内部聊天。</p>
							</div>
							<el-button @click="navigate('chat')">进入内部聊天</el-button>
						</div>
						<div v-if="messages.length" class="message-list">
							<article v-for="message in messages" :key="message.id" class="message-item">
								<span class="message-icon"
									><el-icon><Bell /></el-icon
								></span>
								<div>
									<b>{{ message.title || '事项提醒' }}</b>
									<p>{{ message.content || message.message }}</p>
									<small>{{ formatDate(message.createdAt || message.time) }} · {{ message.resolved ? '此条提醒已结束' : '待查看办理' }}</small>
								</div>
								<el-button v-if="message.noticeId" text type="primary" @click="openRelatedNotice(message.noticeId)">查看公告</el-button
								><el-button v-else-if="message.collegeModule" text type="primary" @click="navigate('college-'+message.collegeModule, 'all', message.collegeRecordId || '')">查看事项</el-button><el-button v-else-if="message.taskId" text type="primary" @click="dashboardNavigate('tasks', 'all', message.taskId)">查看任务</el-button>
							</article>
						</div>
						<el-empty v-else description="目前没有站内提醒" />
					</div>
					<p class="page-note">App 推送、微信订阅等外部渠道仍需学校配置接入。站内提醒不代表用户已读或已确认。</p></template
				><template v-else-if="page === 'audit'"
					><div class="panel">
						<div class="panel-heading">
							<div>
								<h2>重要操作记录</h2>
								<p>只读审计，按最近发生的时间查看</p>
							</div>
						</div>
						<el-table :data="audit" empty-text="暂无操作记录"
							><el-table-column label="时间" min-width="150"
								><template #default="{ row }">{{ formatDate(row.time || row.createdAt) }}</template></el-table-column
							><el-table-column prop="operator" label="操作人" min-width="130" /><el-table-column
								prop="action"
								label="操作"
								min-width="170" /><el-table-column prop="detail" label="详情" min-width="310" show-overflow-tooltip
						/></el-table></div
				></template>
				<template v-else-if="page === 'settings'"
					><div class="settings-grid">
						<section class="panel">
							<div class="panel-heading">
								<div>
									<h2>我的账号</h2>
									<p>由学校超级管理员统一管理</p>
								</div>
								<span class="avatar">{{ user.name?.slice(-2) }}</span>
							</div>
							<dl class="profile-list">
								<dt>姓名</dt>
								<dd>{{ user.name }}</dd>
								<dt>账号</dt>
								<dd>{{ user.username }}</dd>
								<dt>角色</dt>
								<dd>{{ roles[user.role] }}</dd>
								<dt>{{ isStaff ? '分管范围' : '所在班级' }}</dt>
								<dd>{{ user.className || '全校' }}</dd>
							</dl>
							<div class="panel-body">
								<div v-if="roleState.roles.length > 1" class="role-switcher">
									<p>当前只使用一种角色；附加角色须由两名管理员复核。</p>
									<el-select v-model="requestedRole" aria-label="选择办公角色"><el-option v-for="r in roleState.roles" :key="r.id" :value="r.id" :label="`${roles[r.role] || r.role} · ${r.college || r.className || '按已授权范围'}`" /></el-select>
									<el-button :loading="saving" :disabled="uploading || requestedRole === (user.activeRoleId || 'primary')" @click="switchRole">切换角色</el-button>
								</div>
								<el-button @click="passwordOpen = true"
									><el-icon><Lock /></el-icon>修改登录密码</el-button
								>
							</div>
						</section>
						<section class="panel">
							<div class="panel-heading">
								<div>
									<h2>主题皮肤</h2>
									<p>保存后在不同设备上保持一致</p>
								</div>
							</div>
							<div class="skin-options">
								<button
									v-for="skin in themeOptions"
									:key="skin.id"
									:class="{ selected: theme === skin.id }"
									:disabled="saving"
									@click="saveTheme(skin.id)"
								>
									<span class="skin-preview" :style="{ '--skin': skin.color }"><i></i><i></i><i></i></span><b>{{ skin.label }}</b
									><small>{{ theme === skin.id ? '当前使用' : skin.id === 'blue' ? '默认主题' : '点击切换' }}</small>
								</button>
							</div>
						</section>
						<section class="panel channel-panel">
							<div class="panel-heading">
								<div>
									<h2>通知与提醒</h2>
									<p>日常办公不产生短信费用</p>
								</div>
								<el-tag type="success">短信默认关闭</el-tag>
							</div>
							<div class="channel-grid">
								<div>
									<el-icon><Bell /></el-icon><b>站内消息</b>
									<p>公告、确认回执与待办集中呈现。登录后每分钟自动同步。</p>
									<el-tag size="small" type="success">已启用</el-tag>
								</div>
								<div>
									<el-icon><Checked /></el-icon><b>任务面板</b>
									<p>按状态和截止时间筛选，任务完成后停止相关提醒。</p>
									<el-tag size="small" type="success">已启用</el-tag>
								</div>
								<div>
									<el-icon><Iphone /></el-icon><b>外部消息渠道</b>
									<p>App 推送、微信订阅、企业微信等需学校配置接入后才能投递。</p>
									<el-tag size="small" type="info">待接入</el-tag>
								</div>
							</div>
							<div class="panel-footer">
								{{ settings.message || settings.note || '站内消息以登录系统查看为准；紧急事项请结合学校现有应急联络机制。' }}
							</div>
						</section>
					</div></template
				>
				<footer class="content-footer"><span>{{ systemName }}</span></footer>
			</main>
		</div>
	</div>

	<FeatureSearch v-model="commandOpen" :entries="menu" :aliases="menuAliases" @choose="chooseCommand" />

	<el-dialog
		v-model="passwordOpen"
		:title="user?.mustChangePassword ? '首次登录，请设置新密码' : '修改登录密码'"
		width="460px"
		class="erp-dialog"
		:close-on-click-modal="false"
		:close-on-press-escape="!user?.mustChangePassword"
		:show-close="!user?.mustChangePassword"
	>
		<p class="dialog-help">新密码至少 12 位，包含字母和数字。修改成功后，所有已登录设备需要重新登录。</p>
		<el-form label-position="top" @submit.prevent="changePassword"
			><el-form-item label="当前密码"
				><el-input v-model="passwordForm.oldPassword" type="password" show-password autocomplete="current-password" /></el-form-item
			><el-form-item label="新密码"
				><el-input v-model="passwordForm.newPassword" type="password" show-password autocomplete="new-password" /></el-form-item
			><el-form-item label="确认新密码"
				><el-input v-model="passwordForm.repeat" type="password" show-password autocomplete="new-password" /></el-form-item></el-form
		><template #footer
			><el-button v-if="!user?.mustChangePassword" @click="passwordOpen = false">取消</el-button><el-button v-else @click="logout">退出登录</el-button
			><el-button type="primary" :loading="saving" @click="changePassword">保存新密码</el-button></template
		>
	</el-dialog>
	<el-dialog
		:model-value="['notice', 'task', 'request', 'user'].includes(dialog)"
		@close="dialog = ''"
		:title="({notice:'发布公告',task:'创建任务',request:selected ? '补正并重新提交' : '发起事务',user:selected ? '编辑账号' : '创建账号'} as any)[dialog]"
		width="680px"
		class="erp-dialog"
		:close-on-click-modal="false"
		:close-on-press-escape="!saving && !uploading"
		:show-close="!saving && !uploading"
	>
		<el-form v-if="dialog === 'user'" label-position="top" :disabled="saving"
			><div class="form-grid">
				<el-form-item label="账号" required
					><el-input v-model="form.username" :disabled="!!selected" maxlength="64" placeholder="学号或工号" /></el-form-item
				><el-form-item label="姓名" required><el-input v-model="form.name" maxlength="80" /></el-form-item>
			</div>
			<el-form-item v-if="!selected" label="初始密码" required
				><el-input v-model="form.password" type="password" show-password placeholder="至少 12 位，包含字母与数字" autocomplete="new-password" /><small
					class="form-hint"
					>用户首次登录需要更改初始密码。</small
				></el-form-item
			><el-form-item label="角色" required
				><el-select v-model="form.role" style="width: 100%"
					><el-option v-for="(name, role) in roles" :key="role" :label="name" :value="role" /></el-select></el-form-item
			><el-form-item :label="form.role === 'counselor' ? '分管班级' : '所在班级'" :required="form.role !== 'admin'"
				><el-input
					v-model="form.className"
					:placeholder="form.role === 'counselor' ? '多个班级用英文逗号分隔，例如：计科2401,计科2402' : '例如：计科2401'"
					maxlength="500" /></el-form-item
			><el-form-item label="账号状态"
				><el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" :disabled="selected?.id === user?.id" /></el-form-item
		></el-form>
		<el-form v-else label-position="top" :disabled="saving"
			><el-form-item v-if="dialog === 'request'" label="事务类型" required
				><el-select v-model="form.type" :disabled="!!selected" style="width: 100%"
					><el-option v-for="(name, key) in requestNames" :key="key" :label="name" :value="key" /></el-select></el-form-item
			><el-form-item label="标题" required
				><el-input v-model="form.title" maxlength="150" show-word-limit placeholder="用一句话说明需要办理的事" /></el-form-item
			><el-form-item :label="dialog === 'task' ? '任务说明' : dialog === 'notice' ? '公告内容' : '申请说明'" :required="dialog !== 'task'"
				><el-input v-if="dialog === 'task'" v-model="form.description" type="textarea" :rows="4" maxlength="10000" show-word-limit /><el-input
					v-else
					v-model="form.content"
					type="textarea"
					:rows="5"
					maxlength="10000"
					show-word-limit
			/></el-form-item>
			<template v-if="dialog === 'notice'"
				><div class="form-grid">
					<el-form-item label="发送范围"
						><el-select v-model="form.className" filterable clearable placeholder="全部可见班级" style="width: 100%"
							><el-option v-for="className in classNames" :key="className" :label="className" :value="className" /></el-select></el-form-item
					><el-form-item label="主动确认"><el-switch v-model="form.requiresConfirmation" active-text="需要确认" /></el-form-item>
				</div>
				<div class="form-grid">
					<el-form-item label="定时发布（留空立即发布）"><el-input v-model="form.publishAt" type="datetime-local" /></el-form-item
					><el-form-item label="确认截止时间"><el-input v-model="form.dueAt" type="datetime-local" /></el-form-item></div
			></template>
			<template v-if="dialog === 'task'"
				><el-form-item label="负责人" required
					><el-select v-model="form.assigneeId" filterable style="width: 100%" placeholder="搜索姓名或账号"
						><el-option
							v-for="person in users.filter((u) => u.enabled)"
							:key="person.id"
							:value="person.id"
							:label="`${person.name} · ${person.className || roles[person.role]} · ${person.username}`" /></el-select
				></el-form-item>
				<div class="form-grid">
					<el-form-item label="截止时间"><el-input v-model="form.dueAt" type="datetime-local" /></el-form-item
					><el-form-item label="优先级"
						><el-select v-model="form.priority" style="width: 100%"
							><el-option v-for="(name, key) in priorityNames" :key="key" :label="name" :value="key" /></el-select
					></el-form-item></div
			></template>
			<div class="form-grid" v-if="dialog === 'request' && form.type === 'leave'">
				<el-form-item label="请假开始" required><el-input v-model="form.startAt" type="datetime-local" /></el-form-item
				><el-form-item label="预计返回" required><el-input v-model="form.endAt" type="datetime-local" /></el-form-item>
			</div>
			<el-form-item label="附件材料"
				><MaterialUpload v-model="form.attachmentIds" :disabled="saving" @busy="uploading = $event" /></el-form-item></el-form
		><template #footer
			><el-button :disabled="saving || uploading" @click="dialog = ''">取消</el-button
			><el-button type="primary" :loading="saving" :disabled="uploading" @click="saveForm">{{
				uploading ? '附件上传中…' : dialog === 'notice' ? (form.publishAt ? '保存定时公告' : '确认发布') : dialog === 'request' ? '提交申请' : '保存'
			}}</el-button></template
		>
	</el-dialog>
	<el-dialog
		:model-value="dialog === 'noticeDetail'"
		@close="dialog === 'noticeDetail' &amp;&amp; (dialog = '')"
		title="公告详情"
		width="760px"
		class="erp-dialog"
		><template v-if="selected"
			><div class="detail-heading">
				<el-tag :type="statusType(selected.status)">{{ statusNames[selected.status] }}</el-tag
				><span>{{ selected.className || '全部可见班级' }}</span
				><span>{{ formatDate(selected.publishAt || selected.createdAt) }}</span>
			</div>
			<h2 class="detail-title">{{ selected.title }}</h2>
			<div class="detail-content">{{ selected.content }}</div>
			<MaterialUpload v-if="selected.attachmentIds?.length" :model-value="selected.attachmentIds" readonly />
			<div v-if="!isStaff" class="notice-receipt">
				<span
					><el-icon><View /></el-icon>{{ myReceipt(selected).readAt ? `已读 ${formatDate(myReceipt(selected).readAt)}` : '尚未读取' }}</span
				><span v-if="selected.requiresConfirmation"
					><el-icon><CircleCheck /></el-icon
					>{{ myReceipt(selected).confirmedAt ? `已确认 ${formatDate(myReceipt(selected).confirmedAt)}` : '需要主动确认' }}</span
				>
			</div>
			<template v-if="isStaff && selected.receipts?.length"
				><h3 class="section-label">送达与确认情况</h3>
				<el-table :data="selected.receipts" max-height="280"
					><el-table-column prop="name" label="姓名" /><el-table-column label="阅读"
						><template #default="{ row }">{{ row.readAt ? formatDate(row.readAt) : '未读' }}</template></el-table-column
					><el-table-column label="主动确认"
						><template #default="{ row }">{{ row.confirmedAt ? formatDate(row.confirmedAt) : '未确认' }}</template></el-table-column
					></el-table
				></template
			></template
		><template #footer
			><el-button @click="dialog = ''">关闭</el-button
			><el-button
				v-if="selected && canManageNotice(selected) && !['withdrawn', 'scheduled'].includes(selected.status)"
				:loading="saving"
				@click="noticeAction('remind')"
				>提醒未完成人员</el-button
			><el-button
				v-if="selected && canManageNotice(selected) && selected.status !== 'withdrawn'"
				type="danger"
				plain
				:loading="saving"
				@click="noticeAction('withdraw')"
				>撤回</el-button
			><el-button
				v-if="!isStaff && selected?.requiresConfirmation && !myReceipt(selected).confirmedAt && ['published', 'overdue'].includes(selected?.status)"
				type="primary"
				:disabled="!myReceipt(selected).readAt"
				:loading="saving"
				@click="noticeAction('confirm')"
				>我已知悉，确认</el-button
			></template
		></el-dialog
	>
	<el-dialog
		:model-value="dialog === 'taskDetail'"
		@close="dialog === 'taskDetail' &amp;&amp; (dialog = '')"
		title="任务详情"
		width="680px"
		class="erp-dialog"
		><template v-if="selected"
			><div class="detail-heading">
				<el-tag :type="statusType(selected.status)">{{ statusNames[selected.status] }}</el-tag
				><span>{{ selected.assigneeName }}</span
				><span :class="{ danger: overdue(selected) }">截止 {{ formatDate(selected.dueAt) }}</span>
			</div>
			<h2 class="detail-title">{{ selected.title }}</h2>
			<div class="detail-content">{{ selected.description || '暂无额外说明' }}</div>
			<MaterialUpload v-if="selected.attachmentIds?.length" :model-value="selected.attachmentIds" readonly />
			<div v-if="selected.comment" class="result-box">
				<b>最近进度说明</b>
				<p>{{ selected.comment }}</p>
			</div>
			<el-form v-if="selected.status !== 'completed'" label-position="top"
				><el-form-item label="更新进度"
					><el-select v-model="form.status" style="width: 100%"
						><el-option label="待处理" value="pending" /><el-option label="进行中" value="in_progress" /><el-option
							label="已完成"
							value="completed" /></el-select></el-form-item
				><el-form-item label="办理说明"
					><el-input v-model="form.comment" type="textarea" :rows="3" maxlength="2000" placeholder="填写处理进展或完成说明" /></el-form-item></el-form
			><el-alert v-else title="任务已完成，相关提醒已停止。" type="success" :closable="false" /></template
		><template #footer
			><el-button @click="dialog = ''">关闭</el-button
			><el-button v-if="selected?.status !== 'completed'" type="primary" :loading="saving" @click="updateTask">保存进度</el-button></template
		></el-dialog
	>
	<el-dialog
		:model-value="dialog === 'requestDetail'"
		@close="dialog === 'requestDetail' &amp;&amp; (dialog = '')"
		title="事务办理"
		width="700px"
		class="erp-dialog"
		><template v-if="selected"
			><div class="detail-heading">
				<el-tag :type="statusType(selected.status)">{{ statusNames[selected.status] }}</el-tag
				><span>{{ requestNames[selected.type] }}</span
				><span>{{ selected.userName || selected.applicantName }} · {{ selected.className }}</span>
			</div>
			<h2 class="detail-title">{{ selected.title }}</h2>
			<div v-if="selected.type === 'leave'" class="date-range">
				<el-icon><Calendar /></el-icon>{{ formatDate(selected.startAt) }} — {{ formatDate(selected.endAt) }}
			</div>
			<div class="detail-content">{{ selected.content }}</div>
			<MaterialUpload v-if="selected.attachmentIds?.length" :model-value="selected.attachmentIds" readonly />
			<div v-if="selected.comment" class="result-box">
				<b>办理意见</b>
				<p>{{ selected.comment }}</p>
			</div>
			<div v-if="selected.history?.length" class="request-history">
				<h3 class="section-label">办理记录</h3>
				<div v-for="(event, index) in selected.history" :key="event.time || event.createdAt || index">
					<span>{{ formatDate(event.time || event.createdAt) }}</span
					><b>{{ event.operator || event.actorName }} {{ statusNames[event.status] || event.action }}</b>
					<p>{{ event.comment }}</p>
				</div>
			</div>
			<el-form v-if="isStaff && ['pending', 'approved'].includes(selected.status)" label-position="top"
				><el-form-item label="办理意见（退回或驳回必填）"
					><el-input
						v-model="form.comment"
						type="textarea"
						:rows="3"
						maxlength="2000"
						placeholder="清楚说明办理结果或需要补充的材料" /></el-form-item></el-form></template
		><template #footer
			><el-button @click="dialog = ''">关闭</el-button
			><template v-if="!isStaff"
				><el-button v-if="['pending', 'returned'].includes(selected?.status)" type="danger" plain :loading="saving" @click="requestAction('cancel')"
					>取消申请</el-button
				><el-button v-if="selected?.status === 'returned'" type="primary" @click="newDialog('request', selected!)">补正并重提</el-button></template
			><template v-else
				><template v-if="selected?.status === 'pending'"
					><el-button :loading="saving" @click="requestAction('return')">退回补正</el-button
					><el-button type="danger" plain :loading="saving" @click="requestAction('reject')">驳回</el-button
					><el-button type="primary" :loading="saving" @click="requestAction('approve')">通过</el-button></template
				><el-button
					v-if="selected?.status === 'approved' || (selected?.status === 'pending' && selected?.type === 'consultation')"
					type="primary"
					:loading="saving"
					@click="requestAction('close')"
					>办结</el-button
				></template
			></template
		></el-dialog
	>
</template>
