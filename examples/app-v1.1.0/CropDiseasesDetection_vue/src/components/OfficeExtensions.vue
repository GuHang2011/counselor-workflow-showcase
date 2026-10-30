<template>
  <section ref="officeRoot" class="office-extensions">
    <div class="section-intro">
      <div><h2>{{ isStudent ? '假期去向与返校' : '日常办公' }}</h2><p>{{ isStudent ? '登记假期去向，返校后及时确认。' : '记录每一次沟通，跟进待办事项，汇总已开展的工作。' }}</p></div>
      <el-button :loading="loading" @click="loadActive">刷新</el-button>
    </div>
    <el-alert v-if="error" class="load-error" type="error" :title="error" :closable="false" show-icon />
    <el-tabs v-model="activeTab" @tab-change="loadActive">
      <el-tab-pane v-if="!isStudent" label="工作记录" name="worklogs">
        <div class="toolbar">
          <el-radio-group v-model="logFilter" size="small"><el-radio-button label="all">全部</el-radio-button><el-radio-button label="open">待跟进</el-radio-button><el-radio-button label="closed">已完成</el-radio-button></el-radio-group>
          <el-button type="primary" @click="openNewLog">新增工作记录</el-button>
        </div>
        <p class="hint">保密正文仅记录者和明确授权人员可读。转介只发送经确认的必要摘要，接收行动不会自动获得正文。</p>
        <div v-loading="loading" class="record-list">
          <el-empty v-if="!visibleLogs.length && !loading" description="暂无工作记录，完成一次沟通后可随手记下。" />
          <article v-for="log in visibleLogs" :key="log.id" :data-worklog-id="log.id" :class="{ 'record-target': log.id === props.initialRecordId }" class="record-card">
            <div class="card-heading"><h3>{{ log.title }}</h3><el-tag :type="log.status === 'closed' ? 'success' : 'warning'">{{ log.status === 'closed' ? '已完成' : '待跟进' }}</el-tag></div>
            <p class="meta">{{ logTypes[log.type] || log.type }} · {{ formatTime(log.createdAt) }}<span v-if="log.studentId"> · {{ studentName(log) }}</span></p>
            <p class="record-content">{{ log.content }}</p>
            <p v-if="log.followUpAt" class="follow-up" :class="{ overdue: isOverdue(log) }">跟进时间：{{ formatTime(log.followUpAt) }}{{ isOverdue(log) ? ' · 已到期' : '' }}</p>
            <MaterialUpload v-if="log.attachmentIds?.length" :model-value="log.attachmentIds" readonly />
            <div v-if="log.actions?.length" class="action-list"><h4>后续行动</h4><p v-for="action in log.actions" :key="action.id">{{ action.summary }} · {{ formatTime(action.dueAt) }} · {{ action.status === 'completed' ? '已完成' : action.status === 'cancelled' ? '已取消' : '待跟进' }} <el-button v-if="action.status === 'pending'" size="small" @click="careAction(log,'complete_action',action)">登记完成</el-button><el-button v-if="action.status === 'pending'" size="small" @click="careAction(log,'defer_action',action)">延期</el-button><el-button v-if="action.status === 'pending'" size="small" @click="careAction(log,'cancel_action',action)">取消并说明</el-button></p></div><p v-if="log.referralStatus">转介：{{ log.referralStatus === 'accepted' ? '对方已接收' : '等待对方接收，原负责人继续跟踪' }}</p>
            <div class="card-actions"><el-button size="small" @click="openCare(log,'add_action')">新增行动</el-button><el-button size="small" @click="openCare(log,'refer')">受控转介</el-button><el-button size="small" :disabled="saving" @click="openEditLog(log)">编辑跟进</el-button><el-button size="small" :type="log.status === 'closed' ? 'default' : 'primary'" :disabled="saving" @click="toggleLog(log)">{{ log.status === 'closed' ? '重新跟进' : '标记完成' }}</el-button></div>
          </article>
        </div>
      </el-tab-pane>

      <el-tab-pane label="假期登记" name="holidays">
        <div class="toolbar"><p class="hint">{{ isStudent ? '请如实填写去向和预计返校时间；实际返校后再确认。' : '按班级发布假期登记，核验学生返校情况。' }}</p><el-button v-if="!isStudent" type="primary" @click="openHoliday">发布假期登记</el-button></div>
        <div v-loading="loading" class="record-list">
          <el-empty v-if="!holidays.length && !loading" description="暂无假期登记安排。" />
          <article v-for="holiday in holidays" :key="holiday.id" :data-holiday-id="holiday.id" :class="{ 'record-target': holiday.id === props.initialRecordId }" class="record-card">
            <div class="card-heading"><h3>{{ holiday.title }}</h3><el-tag>{{ holiday.className || '全校' }}</el-tag></div>
            <p class="meta">假期：{{ formatTime(holiday.startAt) }} 至 {{ formatTime(holiday.endAt) }}</p>
            <p class="meta">登记截止：{{ formatTime(holiday.dueAt) }} · {{ holiday.status === 'closed' ? '批次已关闭' : '办理中' }}</p><p class="hint">去向环节 {{ holiday.destinationEnabled === false ? '未启用' : '已启用' }} · 返校环节 {{ holiday.returnEnabled === false ? '未启用' : '已启用' }} · 请假豁免 {{ holiday.leaveExemptionEnabled ? '按全程离校请假核验' : '未启用' }}</p>
            <div v-if="!isStudent" class="card-actions"><el-button size="small" @click="holidayLifecycle(holiday,holiday.status==='closed'?'reopen':'close')">{{ holiday.status==='closed'?'授权重开':'关闭批次' }}</el-button><el-button size="small" @click="holidayLifecycle(holiday,'makeup')">设置补办期限</el-button></div>
            <template v-if="isStudent">
              <div v-if="ownRegistration(holiday)" class="registration-summary">
                <p v-if="ownRegistration(holiday)?.destinationExempt">已依据全程离校请假免去向登记；返校环节独立办理。</p><p v-if="ownRegistration(holiday)?.makeupDueAt">豁免失效或授权补办，请于 {{ formatTime(ownRegistration(holiday)?.makeupDueAt) }} 前办理。</p><p>去向：{{ ownRegistration(holiday)?.destination }}</p><p>联系电话：{{ ownRegistration(holiday)?.contact }}</p><p>预计返校：{{ formatTime(ownRegistration(holiday)?.returnAt) }}</p>
                <el-tag :type="ownRegistration(holiday)?.verifiedAt ? 'success' : ownRegistration(holiday)?.returnedAt ? 'warning' : 'info'">{{ ownRegistration(holiday)?.verifiedAt ? '老师已核验返校' : ownRegistration(holiday)?.returnedAt ? '已确认返校，待核验' : ownRegistration(holiday)?.registeredAt ? '已登记，待返校' : ownRegistration(holiday)?.destinationExempt ? '去向已豁免' : '尚未登记去向' }}</el-tag>
              </div>
              <div class="card-actions"><el-button v-if="holiday.destinationEnabled!==false && !ownRegistration(holiday)?.destinationExempt" :disabled="saving || !!ownRegistration(holiday)?.returnedAt || holiday.status==='closed'" @click="openRegistration(holiday)">{{ ownRegistration(holiday)?.registeredAt ? '修改登记' : '登记去向' }}</el-button><el-button v-if="holiday.returnEnabled!==false && !ownRegistration(holiday)?.returnExempt && !ownRegistration(holiday)?.returnedAt" type="primary" :disabled="saving || !holidayStarted(holiday) || holiday.status==='closed'" @click="confirmReturn(holiday)">{{ holidayStarted(holiday) ? '我已返校' : '假期开始后可确认返校' }}</el-button></div>
            </template>
            <template v-else>
              <div class="toolbar compact"><span class="hint">已登记 {{ holiday.registeredCount ?? holiday.registrations.length }} 人 · 已确认返校 {{ holiday.returnedCount ?? holiday.registrations.filter(r => r.returnedAt).length }} 人 · 已核验 {{ holiday.verifiedCount ?? holiday.registrations.filter(r => r.verifiedAt).length }} 人</span><el-button v-if="user.role === 'counselor'" size="small" :disabled="!holiday.registrations.length" @click="exportRegistrations(holiday)">导出当前登记表</el-button></div>
              <p v-if="user.role === 'admin'" class="hint">超级管理员查看整体进度，学生去向及返校核验由对应辅导员办理。</p>
              <el-table v-else :data="holiday.registrations" empty-text="还没有学生登记" class="registration-table">
                <el-table-column prop="userName" label="学生" min-width="95" /><el-table-column prop="className" label="班级" min-width="100" />
                <el-table-column label="豁免依据" min-width="140"><template #default="{ row }">{{ row.destinationExempt ? '全程离校假免去向' : '无豁免' }}{{ row.returnExempt ? '；免返校环节' : '' }}</template></el-table-column><el-table-column prop="destination" label="去向" min-width="150" show-overflow-tooltip /><el-table-column prop="contact" label="联系电话" min-width="140" />
                <el-table-column label="预计返校" min-width="170"><template #default="{ row }">{{ formatTime(row.returnAt) }}</template></el-table-column>
                <el-table-column label="返校状态" min-width="160"><template #default="{ row }"><el-tag :type="row.verifiedAt ? 'success' : row.returnedAt ? 'warning' : 'info'">{{ !row.registeredAt ? '未登记' : row.verifiedAt ? '已核验' : row.returnedAt ? '待核验' : '待返校' }}</el-tag></template></el-table-column>
                <el-table-column label="操作" width="200"><template #default="{ row }"><el-button size="small" :disabled="saving || !row.returnedAt || !!row.verifiedAt" @click="verifyReturn(holiday, row)">核验返校</el-button><el-button v-if="row.returnedAt && !row.verifiedAt" size="small" @click="rejectReturn(holiday,row)">退回</el-button></template></el-table-column>
              </el-table>
            </template>
          </article>
        </div>
      </el-tab-pane>

      <el-tab-pane v-if="!isStudent" label="工作汇总" name="summary">
        <div class="toolbar summary-toolbar"><div class="period-controls"><label>开始日期<el-date-picker v-model="summaryStart" type="date" value-format="YYYY-MM-DD" placeholder="选择开始日期" /></label><label>结束日期<el-date-picker v-model="summaryEnd" type="date" value-format="YYYY-MM-DD" placeholder="选择结束日期" /></label><el-button type="primary" :loading="loading" @click="loadSummary">查询</el-button></div><el-button :disabled="!summary || loading" @click="exportSummary">导出当前汇总</el-button></div>
        <p class="hint">统计当前账号有权查看的工作。汇总仅供复盘，需核实后再用于正式周报或月报。</p>
        <div v-if="summary" v-loading="loading">
          <p class="meta">当前结果周期：{{ summary.period.start }} 至 {{ summary.period.end }}</p>
          <div class="summary-cards"><div v-for="metric in metrics" :key="metric.key" :title="summary.metricDefinitions?.[metric.key]"><span>{{ metric.label }}</span><strong>{{ summary[metric.key] }}</strong></div></div>
          <p class="hint">当前待办与到期跟进按查询时刻统计，不受日期范围影响。其余指标按所选周期统计。</p>
          <el-table :data="summary.entries" empty-text="所选周期暂无工作条目"><el-table-column label="类型" min-width="110"><template #default="{ row }">{{ entryType(row.type) }}</template></el-table-column><el-table-column prop="title" label="事项" min-width="220" show-overflow-tooltip /><el-table-column label="时间" min-width="170"><template #default="{ row }">{{ formatTime(row.time) }}</template></el-table-column></el-table>
          <template v-if="summary.currentEntries"><h3 class="current-heading">当前待办与到期跟进</h3><p class="meta">截至 {{ formatTime(summary.currentAsOf) }}</p><el-table :data="summary.currentEntries" empty-text="当前没有待办或到期跟进"><el-table-column label="类型" min-width="130"><template #default="{ row }">{{ entryType(row.type) }}</template></el-table-column><el-table-column prop="title" label="事项" min-width="220" show-overflow-tooltip /><el-table-column label="截止 / 跟进时间" min-width="180"><template #default="{ row }">{{ formatTime(row.time) }}</template></el-table-column></el-table></template>
        </div>
        <el-empty v-else-if="!loading" description="选择时间范围，查询工作汇总。" />
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="careDialog" :title="careForm.action==='refer'?'受控转介':'新增后续行动'" width="min(94vw, 600px)"><el-form label-position="top"><el-form-item :label="careForm.action==='refer'?'仅向接收人分享的必要摘要':'行动摘要'"><el-input v-model="careForm.summary" type="textarea"/></el-form-item><el-form-item v-if="careForm.action==='refer'" label="接收老师"><el-select v-model="careForm.userId" filterable><el-option v-for="u in careUsers" :key="u.id" :label="u.name+' · '+u.role" :value="u.id"/></el-select></el-form-item><el-form-item label="跟进日期"><el-date-picker v-model="careForm.dueAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ"/></el-form-item><el-form-item label="授权依据／行动说明"><el-input v-model="careForm.reason" type="textarea"/></el-form-item></el-form><template #footer><el-button type="primary" :loading="saving" @click="saveCare">确认保存</el-button></template></el-dialog>
    <el-dialog v-model="logDialog" :title="editingLog ? '编辑工作跟进' : '新增工作记录'" width="min(94vw, 700px)" destroy-on-close :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent="saveLog">
        <div class="form-grid"><el-form-item label="记录类型"><el-select v-model="logForm.type" :disabled="!!editingLog"><el-option v-for="(label, value) in logTypes" :key="value" :label="label" :value="value" /></el-select></el-form-item><el-form-item v-if="user.role === 'counselor'" label="关联学生（可选）"><el-select v-model="logForm.studentId" clearable filterable :disabled="!!editingLog" placeholder="班会或一般事项可不选择"><el-option v-for="student in students" :key="student.id" :label="`${student.name} · ${student.className}`" :value="student.id" /></el-select></el-form-item></div>
        <el-form-item label="标题" required><el-input v-model="logForm.title" maxlength="120" show-word-limit :disabled="!!editingLog" placeholder="例如：学习适应情况沟通" /></el-form-item>
        <div class="form-grid"><el-form-item label="实际开展时间"><el-date-picker v-model="logForm.occurredAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ"/></el-form-item><el-form-item label="参与老师／人员"><el-input v-model="logForm.participants" maxlength="1000"/></el-form-item></div><el-form-item label="关联学生（可多选）"><el-select v-model="logForm.studentIds" multiple filterable><el-option v-for="student in students" :key="student.id" :label="student.name+' · '+student.className" :value="student.id"/></el-select></el-form-item><el-form-item label="工作目的"><el-input v-model="logForm.purpose" maxlength="1000"/></el-form-item><el-form-item v-if="logForm.type==='family'" label="家校沟通允许披露的范围与依据"><el-input v-model="logForm.disclosureBasis" type="textarea" maxlength="2000"/></el-form-item><el-form-item label="记录内容" required><el-input v-model="logForm.content" type="textarea" :rows="5" maxlength="5000" show-word-limit placeholder="记录沟通要点、已采取措施和下一步安排。" /></el-form-item>
        <div class="form-grid"><el-form-item label="下次跟进时间（可选）"><el-date-picker v-model="logForm.followUpAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ" clearable placeholder="选择跟进时间" /></el-form-item><el-form-item v-if="editingLog" label="状态"><el-select v-model="logForm.status"><el-option label="待跟进" value="open" /><el-option label="已完成" value="closed" /></el-select></el-form-item></div>
        <el-form-item v-if="!editingLog" label="附件"><MaterialUpload v-model="logForm.attachmentIds" :disabled="saving" @busy="uploadBusy = $event" /></el-form-item>
        <p v-else class="hint">已有附件随原记录保留；本次修改工作内容与跟进状态。</p>
      </el-form>
      <template #footer><el-button :disabled="saving || uploadBusy" @click="logDialog = false">取消</el-button><el-button type="primary" :loading="saving" :disabled="uploadBusy" @click="saveLog">{{ uploadBusy ? '等待附件上传' : '保存记录' }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="holidayDialog" title="发布假期登记" width="min(94vw, 600px)" :close-on-click-modal="false">
      <el-form label-position="top"><el-form-item label="假期名称" required><el-input v-model="holidayForm.title" maxlength="120" placeholder="例如：国庆假期去向登记" /></el-form-item><el-form-item label="目标班级" required><el-input v-model="holidayForm.className" :disabled="user.role === 'counselor'" placeholder="填写班级名称" /></el-form-item><div class="form-grid"><el-form-item label="假期开始" required><el-date-picker v-model="holidayForm.startAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ" /></el-form-item><el-form-item label="假期结束" required><el-date-picker v-model="holidayForm.endAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ" /></el-form-item></div><el-checkbox v-model="holidayForm.destinationEnabled">启用去向登记</el-checkbox><el-checkbox v-model="holidayForm.returnEnabled">启用返校登记</el-checkbox><el-checkbox v-model="holidayForm.leaveExemptionEnabled">全程离校假免去向登记</el-checkbox><el-checkbox v-if="holidayForm.leaveExemptionEnabled" v-model="holidayForm.exemptReturn">校规同时免返校环节</el-checkbox><el-form-item label="登记截止时间" required><el-date-picker v-model="holidayForm.dueAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ" /></el-form-item></el-form>
      <template #footer><el-button :disabled="saving" @click="holidayDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveHoliday">发布登记</el-button></template>
    </el-dialog>

    <el-dialog v-model="registrationDialog" :title="`假期去向 · ${registeringHoliday?.title || ''}`" width="min(94vw, 560px)" :close-on-click-modal="false">
      <el-form label-position="top"><el-form-item label="假期去向" required><el-input v-model="registrationForm.destination" maxlength="300" placeholder="填写留校或目的地城市、必要的联系地址" /></el-form-item><el-form-item label="联系电话" required><el-input v-model="registrationForm.contact" maxlength="40" inputmode="tel" placeholder="假期内可联系的电话号码" /></el-form-item><el-form-item label="预计返校时间" required><el-date-picker v-model="registrationForm.returnAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ" /></el-form-item></el-form>
      <template #footer><el-button :disabled="saving" @click="registrationDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveRegistration">提交登记</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { csvCell } from '../../shared/csv';
import { computed, nextTick, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api';
import MaterialUpload from './MaterialUpload.vue';

type User = { id: string; name: string; role: string; className: string };
type WorkLog = { actions?: any[]; referralStatus?: string; id: string; type: string; title: string; content: string; studentId?: string; studentName?: string; followUpAt?: string; status: string; attachmentIds: string[]; createdAt: string };
type Registration = { destinationExempt?: boolean; returnExempt?: boolean; makeupDueAt?: string; userId: string; userName: string; className: string; destination: string; contact: string; returnAt: string; registeredAt?: string; returnedAt?: string; verifiedAt?: string };
type Holiday = { destinationEnabled?: boolean; returnEnabled?: boolean; leaveExemptionEnabled?: boolean; status?: string; id: string; title: string; startAt: string; endAt: string; dueAt: string; className: string; recipientCount?: number; registeredCount?: number; returnedCount?: number; verifiedCount?: number; registrations: Registration[] };
type SummaryMetric = 'completedTasks' | 'pendingTasks' | 'notices' | 'worklogs' | 'followUps' | 'requests';
type SummaryEntry = { type: string; title: string; time: string };
type Summary = Record<SummaryMetric, number> & { period: { start: string; end: string }; entries: SummaryEntry[]; currentAsOf?: string; currentEntries?: SummaryEntry[]; metricDefinitions?: Record<SummaryMetric, string> };
const props = defineProps<{ user: User; users?: User[]; initialRecordId?: string; initialSection?: string }>();
const isStudent = computed(() => props.user.role === 'student');
const activeTab = ref(props.initialSection || (isStudent.value ? 'holidays' : 'worklogs'));
const officeRoot = ref<HTMLElement>();
const loading = ref(false); const saving = ref(false); const error = ref('');
const logs = ref<WorkLog[]>([]); const students = ref<User[]>([]); const holidays = ref<Holiday[]>([]);
const logFilter = ref('all');
const visibleLogs = computed(() => logs.value.filter(log => logFilter.value === 'all' || log.status === logFilter.value));
const logTypes: Record<string, string> = { talk: '谈心谈话', dormitory: '查寝走访', class_meeting: '班会记录', contact: '电话与联系', class_visit: '下课堂', online: '线上沟通', family: '家校沟通', education: '主题教育' };
const logDialog = ref(false); const editingLog = ref<WorkLog>(); const uploadBusy = ref(false);
const logForm = reactive({ occurredAt:'', participants:'', purpose:'', disclosureBasis:'', studentIds:[] as string[], type: 'talk', title: '', content: '', studentId: '', followUpAt: '', status: 'open', attachmentIds: [] as string[] });
const holidayDialog = ref(false);
const holidayForm = reactive({ destinationEnabled:true,returnEnabled:true,leaveExemptionEnabled:false,exemptReturn:false,title: '', className: props.user.className || '', startAt: '', endAt: '', dueAt: '' });
const registrationDialog = ref(false); const registeringHoliday = ref<Holiday>();
const registrationForm = reactive({ destination: '', contact: '', returnAt: '' });
const summary = ref<Summary>();
function localDate(value: Date) { return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`; }
const summaryStart = ref(localDate(new Date(new Date().getFullYear(), new Date().getMonth(), 1)));
const summaryEnd = ref(localDate(new Date()));
const metrics: { key: SummaryMetric; label: string }[] = [{ key: 'completedTasks', label: '本期已完成任务' }, { key: 'pendingTasks', label: '当前待办任务' }, { key: 'notices', label: '本期发布公告' }, { key: 'worklogs', label: '本期工作记录' }, { key: 'followUps', label: '当前到期跟进' }, { key: 'requests', label: '本期处理事项' }];
let loadVersion = 0;

function formatTime(value?: string) { if (!value) return '未设置'; const time = new Date(value); return Number.isNaN(time.getTime()) ? value : time.toLocaleString('zh-CN', { hour12: false }); }
function studentName(log: WorkLog) { return log.studentName || students.value.find(student => student.id === log.studentId)?.name || '关联学生'; }
function isOverdue(log: WorkLog) { return log.status !== 'closed' && !!log.followUpAt && new Date(log.followUpAt).getTime() < Date.now(); }
function ownRegistration(holiday: Holiday) { return holiday.registrations.find(registration => registration.userId === props.user.id); }
function holidayStarted(holiday: Holiday) { return new Date(holiday.startAt).getTime() <= Date.now(); }
function entryType(type: string) { return ({ ...logTypes, task: '任务', notice: '公告', worklog: '工作记录', request: '学生事项', holiday: '假期登记' } as Record<string, string>)[type] || type; }
function showError(cause: unknown, prefix = '') { const detail = cause instanceof Error ? cause.message : '操作失败，请重试。'; error.value = prefix + detail; ElMessage.error(error.value); }
function validDate(value: string) { return !!value && !Number.isNaN(new Date(value).getTime()); }
function dateValue(value?: string) { return value ? new Date(value).toISOString() : null; }

async function loadActive() {
  if (activeTab.value === 'summary') return loadSummary();
  const version = ++loadVersion; const tab = activeTab.value;
  loading.value = true; error.value = '';
  try {
    if (tab === 'worklogs' && !isStudent.value) {
      const [records, users] = await Promise.all([api<WorkLog[]>('/worklogs'), props.user.role === 'counselor' ? api<User[]>('/users') : Promise.resolve([] as User[])]);
      if (version !== loadVersion) return;
      logs.value = records; students.value = users.filter(user => user.role === 'student');
    } else {
      const records = await api<Holiday[]>('/holidays');
      if (version === loadVersion) holidays.value = records;
    }
  } catch (cause) { if (version === loadVersion) showError(cause, '加载失败：'); }
  finally { if (version === loadVersion) loading.value = false; }
}

async function focusInitialRecord() {
  const id = props.initialRecordId;
  if (!id) return;
  const isHoliday = props.initialSection === 'holidays';
  const selector = isHoliday ? '[data-holiday-id]' : '[data-worklog-id]';
  const attribute = isHoliday ? 'holidayId' : 'worklogId';
  await nextTick();
  const target = [...(officeRoot.value?.querySelectorAll<HTMLElement>(selector) || [])]
    .find(element => element.dataset[attribute] === id);
  if (!target) {
    if (!error.value) ElMessage.info('这项待办已变化或当前账号无权查看，请刷新后查看最新记录。');
    return;
  }
  target.scrollIntoView({ behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth', block: 'center' });
}

async function loadSummary() {
  if (isStudent.value) return;
  if (!summaryStart.value || !summaryEnd.value || summaryStart.value > summaryEnd.value) return ElMessage.warning('请选择有效的开始、结束日期，结束日期不能早于开始日期。');
  const version = ++loadVersion; loading.value = true; error.value = '';
  try {
    const result = await api<Summary>(`/work-summary?start=${encodeURIComponent(summaryStart.value)}&end=${encodeURIComponent(summaryEnd.value)}`);
    if (version === loadVersion) summary.value = result;
  } catch (cause) { if (version === loadVersion) showError(cause, '汇总查询失败：'); }
  finally { if (version === loadVersion) loading.value = false; }
}

function openNewLog() { editingLog.value = undefined; uploadBusy.value = false; Object.assign(logForm, { occurredAt:'',participants:'',purpose:'',disclosureBasis:'',studentIds:[],type: 'talk', title: '', content: '', studentId: '', followUpAt: '', status: 'open', attachmentIds: [] }); logDialog.value = true; }
function openEditLog(log: WorkLog) { editingLog.value = log; uploadBusy.value = false; Object.assign(logForm, { ...log, studentId: log.studentId || '', followUpAt: log.followUpAt || '', attachmentIds: [...(log.attachmentIds || [])] }); logDialog.value = true; }
async function saveLog() {
  if (saving.value || uploadBusy.value) return;
  if (!logForm.title.trim() || !logForm.content.trim()) return ElMessage.warning('请填写标题和记录内容。');
  if (logForm.followUpAt && !validDate(logForm.followUpAt)) return ElMessage.warning('请选择有效的跟进时间。');
  saving.value = true;
  try {
    if (editingLog.value) await api(`/worklogs/${editingLog.value.id}`, { method: 'PUT', body: { occurredAt:dateValue(logForm.occurredAt),participants:logForm.participants,purpose:logForm.purpose,disclosureBasis:logForm.disclosureBasis,content: logForm.content.trim(), status: logForm.status, followUpAt: dateValue(logForm.followUpAt) } });
    else await api('/worklogs', { method: 'POST', body: { occurredAt:dateValue(logForm.occurredAt),participants:logForm.participants,purpose:logForm.purpose,disclosureBasis:logForm.disclosureBasis,studentIds:logForm.studentIds,type: logForm.type, title: logForm.title.trim(), content: logForm.content.trim(), studentId: logForm.studentId || null, followUpAt: dateValue(logForm.followUpAt), attachmentIds: logForm.attachmentIds } });
    logDialog.value = false; ElMessage.success('工作记录已保存。'); await loadActive();
  } catch (cause) { showError(cause, '未保存：'); } finally { saving.value = false; }
}
async function toggleLog(log: WorkLog) {
  if (log.status !== 'closed') { await careAction(log,'close'); return; }
  if (saving.value) return; saving.value = true;
  try { await api(`/worklogs/${log.id}`, { method: 'PUT', body: { status: log.status === 'closed' ? 'open' : 'closed', content: log.content, followUpAt: log.followUpAt || null } }); ElMessage.success('跟进状态已更新。'); await loadActive(); }
  catch (cause) { showError(cause, '状态未更新：'); } finally { saving.value = false; }
}
function openHoliday() { Object.assign(holidayForm, { title: '', className: props.user.className || '', startAt: '', endAt: '', dueAt: '' }); holidayDialog.value = true; }
async function saveHoliday() {
  if (saving.value) return;
  if (!holidayForm.title.trim() || !holidayForm.className.trim() || ![holidayForm.startAt, holidayForm.endAt, holidayForm.dueAt].every(validDate)) return ElMessage.warning('请完整填写假期名称、班级和各项时间。');
  if (new Date(holidayForm.endAt) <= new Date(holidayForm.startAt)) return ElMessage.warning('假期结束时间须晚于开始时间。');
  if (new Date(holidayForm.dueAt) > new Date(holidayForm.endAt)) return ElMessage.warning('登记截止时间不能晚于假期结束时间。');
  saving.value = true;
  try { await api('/holidays', { method: 'POST', body: { ...holidayForm, title: holidayForm.title.trim(), className: holidayForm.className.trim(), startAt: dateValue(holidayForm.startAt), endAt: dateValue(holidayForm.endAt), dueAt: dateValue(holidayForm.dueAt) } }); holidayDialog.value = false; ElMessage.success('假期登记已发布。'); await loadActive(); }
  catch (cause) { showError(cause, '发布失败：'); } finally { saving.value = false; }
}
function openRegistration(holiday: Holiday) { registeringHoliday.value = holiday; const existing = ownRegistration(holiday); Object.assign(registrationForm, { destination: existing?.destination || '', contact: existing?.contact || '', returnAt: existing?.returnAt || holiday.endAt }); registrationDialog.value = true; }
async function saveRegistration() {
  if (saving.value || !registeringHoliday.value) return;
  if (!registrationForm.destination.trim() || !registrationForm.contact.trim() || !validDate(registrationForm.returnAt)) return ElMessage.warning('请填写去向、联系电话和预计返校时间。');
  saving.value = true;
  try { await api(`/holidays/${registeringHoliday.value.id}/register`, { method: 'POST', body: { destination: registrationForm.destination.trim(), contact: registrationForm.contact.trim(), returnAt: dateValue(registrationForm.returnAt) } }); registrationDialog.value = false; ElMessage.success('假期去向已登记。'); await loadActive(); }
  catch (cause) { showError(cause, '登记未提交：'); } finally { saving.value = false; }
}
async function confirmReturn(holiday: Holiday) {
  if (saving.value) return;
  try { await ElMessageBox.confirm('确认你已实际返回学校？提交后老师将核验返校情况。', '确认返校', { confirmButtonText: '我已返校', cancelButtonText: '暂不提交', type: 'warning' }); }
  catch { return; }
  saving.value = true;
  try { await api(`/holidays/${holiday.id}/return`, { method: 'POST', body: {} }); ElMessage.success('返校确认已提交，等待老师核验。'); await loadActive(); }
  catch (cause) { showError(cause, '返校确认未提交：'); } finally { saving.value = false; }
}
async function verifyReturn(holiday: Holiday, registration: Registration) {
  if (saving.value) return;
  let comment: string;
  try { const result = await ElMessageBox.prompt(`请核实 ${registration.userName} 已返校，并填写核验说明。`, '返校核验', { confirmButtonText: '确认已核验', cancelButtonText: '取消', inputPlaceholder: '例如：已电话联系并现场确认', inputValidator: value => !!value?.trim() || '请填写核验说明。' }); comment = result.value; }
  catch { return; }
  saving.value = true;
  try { await api(`/holidays/${holiday.id}/verify`, { method: 'POST', body: { userId: registration.userId, comment } }); ElMessage.success('返校情况已核验。'); await loadActive(); }
  catch (cause) { showError(cause, '核验未保存：'); } finally { saving.value = false; }
}

const careDialog=ref(false),careLog=ref<WorkLog>(),careUsers=ref<User[]>([]);
const careForm=reactive({action:'add_action',summary:'',userId:'',dueAt:'',reason:''});
async function openCare(log:WorkLog,action:string){careLog.value=log;Object.assign(careForm,{action,summary:'',userId:'',dueAt:'',reason:''});try{careUsers.value=(props.users?.length?props.users:await api<User[]>('/directory')).filter(u=>['counselor','mentor','leader','business'].includes(u.role));careDialog.value=true;}catch(e){showError(e);}}
async function saveCare(){if(!careLog.value)return;saving.value=true;try{await api('/office/care/'+careLog.value.id+'/action',{method:'POST',body:careForm});careDialog.value=false;await loadActive();}catch(e){showError(e);}finally{saving.value=false;}}
async function careAction(log:WorkLog,action:string,item?:any){let reason:string;try{reason=(await ElMessageBox.prompt('请记录结果、延期原因或取消依据。','工作行动',{inputValidator:v=>!!v?.trim()||'请填写说明'})).value;}catch{return;}let dueAt='';if(action==='defer_action'){try{dueAt=(await ElMessageBox.prompt('填写新的跟进日期，例如 2026-12-01。','延期日期')).value;}catch{return;}}saving.value=true;try{await api('/office/care/'+log.id+'/action',{method:'POST',body:{action,actionId:item?.id,reason,dueAt}});await loadActive();}catch(e){showError(e);}finally{saving.value=false;}}
async function rejectReturn(holiday:Holiday,row:Registration){let comment:string;try{comment=(await ElMessageBox.prompt('请填写退回核验的原因。','退回返校登记',{inputValidator:v=>!!v?.trim()||'请填写原因'})).value;}catch{return;}try{await api('/holidays/'+holiday.id+'/verify',{method:'POST',body:{userId:row.userId,reject:true,comment}});await loadActive();}catch(e){showError(e);}}
async function holidayLifecycle(holiday:Holiday,action:string){let reason:string,until='';try{reason=(await ElMessageBox.prompt('请填写关闭、重开或补办的依据。','批次管理',{inputValidator:v=>!!v?.trim()||'请填写说明'})).value;if(action!=='close')until=(await ElMessageBox.prompt('填写新的补办截止日期，例如 2026-12-01。','补办期限')).value;}catch{return;}try{await api('/holidays/'+holiday.id+'/lifecycle',{method:'POST',body:{action,reason,until}});await loadActive();}catch(e){showError(e);}}

function downloadCsv(name: string, rows: unknown[][]) {
  const file = new Blob(['\ufeff' + rows.map(row => row.map(csvCell).join(',')).join('\r\n')], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(file); const link = document.createElement('a'); link.href = url; link.download = name.replace(/[<>:"/\\|?*\u0000-\u001f]/g, '_') + '.csv'; document.body.appendChild(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
}
function exportRegistrations(holiday: Holiday) {
  downloadCsv(`${holiday.title}_登记表`, [['学生', '班级', '登记状态', '去向', '联系电话', '预计返校', '确认返校', '核验时间'], ...holiday.registrations.map(row => [row.userName, row.className, row.registeredAt ? '已登记' : '未登记', row.destination, row.contact, row.returnAt ? formatTime(row.returnAt) : '', row.returnedAt ? formatTime(row.returnedAt) : '', row.verifiedAt ? formatTime(row.verifiedAt) : ''])]);
}
function exportSummary() {
  if (!summary.value) return;
  const result = summary.value;
  downloadCsv(`工作汇总_${result.period.start}_${result.period.end}`, [['统计周期', result.period.start, result.period.end], ['当前待办统计时刻', formatTime(result.currentAsOf)], ['统计项目', '数量', '口径'], ...metrics.map(metric => [metric.label, result[metric.key], result.metricDefinitions?.[metric.key] || '']), [], ['本期事项类型', '事项名称', '时间'], ...result.entries.map(entry => [entryType(entry.type), entry.title, formatTime(entry.time)]), [], ['当前待办类型', '事项名称', '截止或跟进时间'], ...(result.currentEntries || []).map(entry => [entryType(entry.type), entry.title, formatTime(entry.time)])]);
}
watch(() => [props.user.id, props.initialRecordId, props.initialSection] as const, async ([userId, recordId, section], previous) => {
  if (previous && previous[0] !== userId) { ++loadVersion; logs.value = []; students.value = []; holidays.value = []; summary.value = undefined; logDialog.value = false; holidayDialog.value = false; registrationDialog.value = false; careDialog.value=false;careLog.value=undefined;careUsers.value=[]; }
  activeTab.value = section || (isStudent.value ? 'holidays' : 'worklogs');
  if (recordId && activeTab.value === 'worklogs') logFilter.value = 'all';
  await loadActive();
  await focusInitialRecord();
}, { immediate: true });
</script>

<style scoped>
.office-extensions { color: var(--el-text-color-primary); }
.section-intro, .toolbar, .card-heading, .card-actions, .period-controls { display: flex; align-items: center; gap: 12px; }
.section-intro, .toolbar, .card-heading { justify-content: space-between; }
.section-intro { margin-bottom: 18px; }
h2 { margin: 0; font-size: 24px; } h3 { margin: 0; font-size: 17px; line-height: 1.5; overflow-wrap: anywhere; }
.section-intro p, .hint, .meta { color: var(--el-text-color-secondary); line-height: 1.7; font-size: 13px; }
.section-intro p { margin: 7px 0 0; }
.load-error { margin-bottom: 16px; }
.toolbar { margin: 8px 0 16px; flex-wrap: wrap; }
.compact { margin: 16px 0 8px; }
.hint { margin: 8px 0 14px; } .toolbar .hint { margin: 0; }
.record-list { min-height: 180px; }
.record-card { padding: 20px; border: 1px solid var(--el-border-color-light); border-radius: 14px; margin-bottom: 14px; background: var(--el-bg-color); }
.record-card.record-target { border-color: var(--el-color-primary); outline: 3px solid var(--el-color-primary-light-5); background: var(--el-color-primary-light-9); box-shadow: 0 0 0 6px var(--el-color-primary-light-9); animation: record-target-arrive 1.2s ease-out 1; scroll-margin-block: 24px; }
@keyframes record-target-arrive { from { box-shadow: 0 0 0 9px var(--el-color-primary-light-9); } to { box-shadow: 0 0 0 6px var(--el-color-primary-light-9); } }
@media (prefers-reduced-motion: reduce) { .record-card.record-target { animation: none; } }
.card-heading { align-items: flex-start; } .card-heading .el-tag { flex-shrink: 0; }
.meta { margin: 8px 0; }
.record-content { line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; margin: 14px 0; }
.follow-up { color: var(--el-color-primary); font-size: 13px; } .overdue { color: var(--el-color-danger); }
.card-actions { flex-wrap: wrap; margin-top: 16px; } .card-actions .el-button { margin: 0; }
.registration-summary { margin-top: 14px; padding: 12px 16px; border-radius: 10px; background: var(--el-fill-color-light); font-size: 14px; }
.registration-summary p { margin: 8px 0; overflow-wrap: anywhere; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.form-grid :deep(.el-select), .form-grid :deep(.el-date-editor), :deep(.el-form-item .el-date-editor) { width: 100%; }
.summary-toolbar { align-items: flex-end; }
.period-controls { flex-wrap: wrap; align-items: flex-end; }
.period-controls label { display: flex; flex-direction: column; gap: 7px; font-size: 13px; }
.period-controls :deep(.el-date-editor) { width: 170px; }
.summary-cards { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin: 18px 0; }
.summary-cards > div { padding: 17px; border-radius: 12px; background: var(--el-color-primary-light-9); border: 1px solid var(--el-color-primary-light-8); }
.summary-cards span { display: block; color: var(--el-text-color-secondary); font-size: 13px; }
.summary-cards strong { display: block; color: var(--el-color-primary); font-size: 28px; margin-top: 8px; }
.registration-table { width: 100%; }
.current-heading { margin-top: 24px; }
@media (max-width: 640px) {
  h2 { font-size: 21px; }
  .record-card { padding: 15px; }
  .form-grid { grid-template-columns: 1fr; }
  .summary-cards { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
  .summary-cards > div { padding: 13px; }
  .summary-toolbar, .period-controls { width: 100%; }
  .period-controls label { flex: 1; min-width: 135px; }
  .period-controls :deep(.el-date-editor) { width: 100%; }
  .card-actions .el-button { flex: 1; }
}
</style>
