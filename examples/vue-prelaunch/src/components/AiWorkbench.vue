<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api';
import { ApiError } from '../../shared/api-client';

type Scenario = 'notice_draft' | 'task_breakdown' | 'work_summary' | 'chat' | 'text_write' | 'text_polish';
type Result = {
  title?: string;
  content?: string;
  answer?: string;
  mode?: string;
  requiresHumanReview?: boolean;
  modelConfigured?: boolean;
  warning?: string;
  suggestions?: string[];
  sources?: Array<{ title?: string; source?: string; version?: string }>;
};
type AiStatus = {
  enabled?: boolean;
  provider?: string;
  model?: string;
  modelConfigured?: boolean;
  localFallback?: boolean;
  externalDataAllowed?: boolean;
};

const props = defineProps<{ user: Record<string, any> }>();
const isAdmin = computed(() => props.user.role === 'admin');
const configOpen = ref(false); const config = reactive({ provider: 'local', baseUrl: '', model: '', apiKey: '', allowExternalData: false });
const scenario = ref<Scenario>('notice_draft');
const busy = ref(false);
const error = ref('');
const result = ref<Result | null>(null);
const status = ref<AiStatus | null>(null);
const statusError = ref('');
const statusLoading = ref(false);
const form = reactive({
  topic: '',
  details: '',
  audience: '',
  deadline: '',
  tone: 'formal',
});

const scenarios: Array<{ id: Scenario; label: string; description: string }> = [
  { id: 'text_write', label: '文本写作', description: '根据用途与已知事实生成文稿' },
  { id: 'text_polish', label: '文本优化', description: '纠错、精简与表达优化' },
  { id: 'notice_draft', label: '通知草稿', description: '把零散要求整理成可核对的公告正文' },
  { id: 'task_breakdown', label: '任务拆解', description: '将一项工作拆成责任、步骤和时间节点' },
  { id: 'work_summary', label: '工作总结', description: '根据工作事实整理不含敏感信息的总结' },
  { id: 'chat', label: '制度问答', description: '基于校内制度原文回答常见办理问题' },
];
const currentScenario = computed(() => scenarios.find((item) => item.id === scenario.value) || scenarios[0]);
const output = computed(() => result.value?.content || result.value?.answer || '');
const isChat = computed(() => scenario.value === 'chat');
const isNotice = computed(() => scenario.value === 'notice_draft');
const placeholder = computed(() => {
  if (scenario.value === 'text_write') return '填写用途、读者、事实、目标长度与写作要求';
  if (scenario.value === 'text_polish') return '粘贴原文，并说明纠错、精简或正式表达的目标';
  if (scenario.value === 'notice_draft') return '例如：提醒 2026 级新生在本周五前完成迎新材料核验，未完成者联系辅导员…';
  if (scenario.value === 'task_breakdown') return '例如：组织本月心理健康主题班会，涉及 4 个班级和 2 位协作老师…';
  if (scenario.value === 'work_summary') return '填写已完成的工作事实、数据和下一步计划。不要粘贴身份证号、联系方式、健康或谈话原文…';
  return '例如：学生申请临时困难补助需要准备哪些材料？请引用制度名称和版本…';
});
const statusLabel = computed(() => {
  if (statusError.value) return '服务状态读取失败，可重试';
  if (!status.value) return '正在读取服务状态';
  if (status.value.modelConfigured) return `已配置${status.value.provider || '校内'}模型，仍需人工复核`;
  return status.value.localFallback === false ? 'AI 未配置' : '当前使用本地模板，未调用外部模型';
});

function resetResult() {
  result.value = null;
  error.value = '';
}
function selectScenario(value: Scenario) {
  scenario.value = value;
  resetResult();
}
function localFallback(): Result {
  if (scenario.value === 'text_write') {
    return { title: '文本写作框架', content: '主题：〔填写文稿主题〕\n\n开头：〔说明背景与目的〕\n\n正文：〔按要点填写已核实的事实〕\n\n结尾：〔明确后续安排〕', mode: 'template', requiresHumanReview: true, warning: 'AI 服务暂不可用，已显示写作框架，请补充事实后使用。' };
  }
  if (scenario.value === 'text_polish') {
    return { title: '原文已保留，尚未优化', content: form.details, mode: 'disabled', requiresHumanReview: true, warning: 'AI 服务暂不可用，以下仍为原文。网络恢复后可重新生成。' };
  }
  if (scenario.value === 'notice_draft') {
    return {
      title: '通知草稿框架',
      content: '接收对象：〔填写班级或人员范围〕\n\n办理事项：〔填写具体要求和办理入口〕\n\n截止时间：〔填写并核实〕\n\n联系人：〔填写负责老师〕',
      mode: 'template',
      requiresHumanReview: true,
      warning: 'AI 服务暂不可用，已使用本地通知模板。发布前请核对对象、时间和政策依据。',
    };
  }
  if (scenario.value === 'task_breakdown') {
    return { title: '任务拆解框架', content: '1. 明确目标与办理范围\n2. 列出责任人和协作人\n3. 设置截止时间并发布站内任务\n4. 按节点记录进展，完成后留存依据', mode: 'template', requiresHumanReview: true, warning: 'AI 服务暂不可用，已显示通用任务清单，请按实际分工调整。' };
  }
  if (scenario.value === 'work_summary') {
    return { title: '工作总结框架', content: '工作概况：〔填写可核验的完成事项〕\n\n主要成果：〔填写统计口径、数量和来源〕\n\n存在问题：〔填写不涉及个人隐私的共性情况〕\n\n下一步：〔填写负责人和时间节点〕', mode: 'template', requiresHumanReview: true, warning: 'AI 服务暂不可用，已显示总结框架。请自行核对事实，避免填写学生敏感信息。' };
  }
  return { title: '制度问答', content: '当前无法连接 AI 服务。请先在“制度与资源预约”中检索已发布制度原文，按最新版本办理并联系业务负责人确认。', mode: 'disabled', requiresHumanReview: true, warning: '未生成制度结论，不能据此直接审批或作出处理决定。' };
}
async function loadStatus() {
  if (statusLoading.value) return;
  statusLoading.value = true;
  statusError.value = '';
  try {
    status.value = await api<AiStatus>('/ai/status');
  } catch {
    statusError.value = '服务状态读取失败';
  } finally { statusLoading.value = false; }
}
async function saveConfig(){busy.value=true;try{status.value=await api<AiStatus>('/ai/config',{method:'PUT',body:config});config.apiKey='';configOpen.value=false;ElMessage.success('AI配置已保存，密钥不会回显');}catch(e:any){error.value=e.message;}finally{busy.value=false;}}
async function run() {
  if (!form.details.trim() && !form.topic.trim()) {
    ElMessage.warning(isChat.value ? '请先输入要咨询的问题。' : '请先填写主题或工作说明。');
    return;
  }
  busy.value = true;
  error.value = '';
  result.value = null;
  try {
    result.value = await api<Result>('/ai/assist', { method: 'POST', body: { scenario: scenario.value, ...form } });
  } catch (e: any) {
    error.value = e?.message || 'AI 服务暂不可用';
    if (!(e instanceof ApiError) || e.status === 0 || e.status >= 500) result.value = localFallback();
  } finally {
    busy.value = false;
  }
}
async function copyOutput() {
  if (!output.value) return;
  try {
    await navigator.clipboard.writeText(output.value);
    ElMessage.success('已复制，可粘贴到通知、任务或工作记录中。');
  } catch {
    ElMessage.warning('当前浏览器未允许访问剪贴板，请手动选择复制。');
  }
}
onMounted(loadStatus);
</script>

<template>
  <section class="ai-workbench" v-loading="busy">
    <div class="ai-intro">
      <div>
        <p class="eyebrow">AI ASSISTED OFFICE</p>
        <h2>把重复整理交给助手</h2>
        <p class="ai-subtitle">用于草拟、拆解和归纳，所有内容都需要辅导员核对后再发布或提交。</p>
      </div>
      <div class="ai-intro-meta"><el-tag type="info" effect="plain">账号：{{ props.user.name }}</el-tag><el-tag :type="statusError ? 'warning' : 'info'" effect="plain">{{ statusLabel }}</el-tag><el-button v-if="statusError" size="small" :loading="statusLoading" @click="loadStatus">重试服务状态</el-button><el-button v-if="isAdmin" size="small" @click="configOpen=true">配置 AI 服务</el-button></div>
    </div>
    <el-alert
      title="隐私边界"
      description="请仅输入必要的工作事实，不要粘贴身份证号、联系方式、健康信息、心理谈话原文或其他敏感材料。AI 不会自动审批、处分或作出心理判断。"
      type="warning"
      :closable="false"
      show-icon
    />
    <div class="ai-layout">
      <aside class="ai-scenarios" aria-label="AI 办公场景">
        <button
          v-for="item in scenarios"
          :key="item.id"
          class="ai-scenario"
          :class="{ active: scenario === item.id }"
          type="button"
          :disabled="busy"
          @click="selectScenario(item.id)"
        >
          <strong>{{ item.label }}</strong><small>{{ item.description }}</small>
        </button>
      </aside>
      <div class="ai-editor">
        <div class="ai-editor-heading"><h3>{{ currentScenario.label }}</h3><span>{{ currentScenario.description }}</span></div>
        <el-form label-position="top" :disabled="busy" @submit.prevent="run">
          <el-form-item v-if="!isChat" label="主题 / 事项名称"><el-input v-model="form.topic" maxlength="120" placeholder="给这次整理起一个清晰的名称" /></el-form-item>
          <el-form-item v-if="isNotice" label="接收对象"><el-input v-model="form.audience" maxlength="200" placeholder="例如：2026 级智能工程技术学院全体学生" /></el-form-item>
          <el-form-item v-if="isNotice" label="办理截止（可选）"><el-input v-model="form.deadline" maxlength="80" placeholder="例如：2026-10-10 18:00" /></el-form-item>
          <el-form-item v-if="!isChat" label="表达风格"><el-select v-model="form.tone" style="width: 100%"><el-option label="正式、清晰、友好" value="formal" /><el-option label="简洁、行动导向" value="concise" /><el-option label="温和、鼓励为主" value="warm" /></el-select></el-form-item>
          <el-form-item :label="isChat ? '问题' : '工作说明 / 已知事实'"><el-input v-model="form.details" type="textarea" :rows="isChat ? 7 : 9" maxlength="8000" :placeholder="placeholder" show-word-limit /></el-form-item>
          <div class="ai-actions"><el-button type="primary" native-type="submit" :loading="busy">生成建议</el-button><el-button @click="resetResult">清空结果</el-button><span>结果仅供辅助，发布前请人工复核。</span></div>
        </el-form>
      </div>
    </div>
    <el-alert v-if="error && !result" :title="error" type="error" :closable="false" show-icon class="ai-error" />
    <section v-if="result" class="ai-result" aria-live="polite">
      <div class="ai-result-heading"><div><p class="eyebrow">ASSISTED RESULT</p><h3>{{ result.title || currentScenario.label }}</h3></div><div class="ai-result-actions"><el-tag :type="result.mode === 'disabled' ? 'danger' : result.mode === 'template' ? 'warning' : 'success'">{{ result.mode === 'external' ? '模型结果' : result.mode === 'local' ? '校内模型' : result.mode === 'template' ? '本地模板' : '暂不可用' }}</el-tag><el-button size="small" @click="copyOutput">复制内容</el-button></div></div>
      <el-alert v-if="error || result.warning" :title="result.warning || error" type="warning" :closable="false" show-icon />
      <div v-if="scenario === 'text_polish' && result.mode !== 'disabled'" class="ai-original"><b>原文</b><pre class="ai-output">{{ form.details }}</pre><b>修改稿</b></div>
      <pre class="ai-output">{{ output }}</pre>
      <div v-if="result.suggestions?.length" class="ai-suggestions"><b>建议核对</b><ul><li v-for="suggestion in result.suggestions" :key="suggestion">{{ suggestion }}</li></ul></div>
      <p v-if="result.requiresHumanReview !== false" class="ai-review-note">请核对接收范围、日期、政策版本和事实依据后再使用。</p>
      <div v-if="result.sources?.length" class="ai-sources"><b>参考来源</b><span v-for="source in result.sources" :key="source.title || source.source">{{ source.title || source.source }}{{ source.version ? ` · V${source.version}` : '' }}</span></div>
    </section>
    <el-dialog v-model="configOpen" title="配置 AI 服务" width="min(94vw,560px)"><el-alert title="密钥仅用于当前后台进程调用，不会回显或写入操作审计。正式环境请使用 HTTPS 模型地址。" type="warning" :closable="false"/><el-form label-position="top"><el-form-item label="服务商"><el-input v-model="config.provider" placeholder="openai / deepseek / local"/></el-form-item><el-form-item label="API 地址"><el-input v-model="config.baseUrl" placeholder="https://api.example.com/v1"/></el-form-item><el-form-item label="模型名称"><el-input v-model="config.model" placeholder="模型标识"/></el-form-item><el-form-item label="API Key"><el-input v-model="config.apiKey" type="password" show-password placeholder="留空表示保持当前密钥"/></el-form-item><el-checkbox v-model="config.allowExternalData">允许将工作事实发送至外部模型</el-checkbox></el-form><template #footer><el-button @click="configOpen=false">取消</el-button><el-button type="primary" :loading="busy" @click="saveConfig">保存配置</el-button></template></el-dialog>
  </section>
</template>

<style scoped>
.ai-workbench { padding: 26px; }
.ai-intro, .ai-intro-meta, .ai-result-heading, .ai-actions, .ai-editor-heading, .ai-sources { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.ai-intro { margin-bottom: 20px; }
.ai-intro-meta { flex-wrap: wrap; }
.ai-intro h2 { margin-top: 7px; color: #26354b; font-size: 22px; }
.ai-subtitle, .ai-editor-heading span, .ai-actions span, .ai-review-note { color: #8190a4; font-size: 13px; line-height: 1.7; }
.ai-subtitle { margin-top: 8px; }
.ai-layout { display: grid; grid-template-columns: 220px minmax(0, 1fr); gap: 20px; margin-top: 20px; }
.ai-scenarios { display: flex; flex-direction: column; gap: 8px; }
.ai-scenario { border: 1px solid #e2e9f2; border-radius: 9px; background: #fff; color: #52657f; text-align: left; padding: 14px; transition: border-color .2s, background .2s; }
.ai-scenario strong, .ai-scenario small { display: block; }
.ai-scenario strong { font-size: 14px; }
.ai-scenario small { margin-top: 6px; color: #92a0b3; line-height: 1.5; }
.ai-scenario.active { border-color: var(--brand); background: var(--brand-soft); color: var(--brand); }
.ai-editor, .ai-result { border: 1px solid #e2e9f2; border-radius: 10px; background: #fff; padding: 20px; }
.ai-editor-heading { justify-content: flex-start; border-bottom: 1px solid #edf1f6; padding-bottom: 14px; margin-bottom: 18px; }
.ai-editor-heading h3, .ai-result-heading h3 { color: #30435f; font-size: 16px; }
.ai-actions { justify-content: flex-start; flex-wrap: wrap; }
.ai-result { margin-top: 20px; }
.ai-error { margin-top: 20px; }
.ai-result-heading { margin-bottom: 14px; }
.ai-result-actions { display: flex; align-items: center; gap: 10px; }
.ai-output { white-space: pre-wrap; overflow-wrap: anywhere; margin: 16px 0 10px; min-height: 120px; color: #3b4d66; font: inherit; line-height: 1.9; }
.ai-review-note { border-left: 3px solid var(--brand); padding-left: 10px; }
.ai-suggestions { border: 1px solid #edf1f6; border-radius: 8px; padding: 12px 14px; color: #52657f; }
.ai-suggestions ul { margin: 8px 0 0; padding-left: 20px; line-height: 1.8; }
.ai-sources { justify-content: flex-start; flex-wrap: wrap; border-top: 1px solid #edf1f6; padding-top: 12px; margin-top: 14px; }
.ai-sources span { color: #6d809b; font-size: 12px; }
@media (max-width: 760px) {
  .ai-workbench { padding: 18px; }
  .ai-intro { align-items: flex-start; flex-direction: column; }
  .ai-layout { grid-template-columns: 1fr; }
  .ai-scenarios { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .ai-scenario { min-height: 88px; }
  .ai-editor, .ai-result { padding: 16px; }
  .ai-result-heading { align-items: flex-start; flex-direction: column; }
  .ai-actions span { width: 100%; }
}
</style>
