<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '../api';

type Preferences = { enabled: boolean; interval: number; leadHours: number };
type Reminder = { id: string; title: string; module: 'tasks' | 'care' | 'party'; dueAt: string };
const emit = defineEmits<{ (e: 'navigate', page: string, filter: string, recordId: string): void }>();
const prefs = ref<Preferences | null>(null), items = ref<Reminder[]>([]);
const show = ref(false), settings = ref(false), loading = ref(false), saving = ref(false), error = ref('');
let timer: ReturnType<typeof setInterval> | undefined;
let stopped = false;

async function check() {
  if (stopped || document.hidden || show.value || document.querySelector('.el-overlay:not([style*="display: none"])')) return;
  try {
    const list = await api<Reminder[]>('/college-work/reminders');
    if (!stopped && list.length) { items.value = list; show.value = true; }
  } catch { /* Retry on the next poll without interrupting the current workflow. */ }
}
async function openSettings() {
  if (loading.value || saving.value) return;
  show.value = false;
  settings.value = true;
  loading.value = true;
  error.value = '';
  prefs.value = null;
  try { prefs.value = await api<Preferences>('/college-work/preferences'); }
  catch (e: any) { error.value = e.message || '提醒设置暂未读取，请重试。'; }
  finally { loading.value = false; }
}
async function save() {
  if (!prefs.value || loading.value || saving.value) return;
  saving.value = true;
  error.value = '';
  try {
    prefs.value = await api<Preferences>('/college-work/preferences', { method: 'POST', body: prefs.value });
    settings.value = false;
    ElMessage.success('任务提醒设置已保存');
  } catch (e: any) { error.value = e.message; }
  finally { saving.value = false; }
}
function openItem(item: Reminder) { show.value = false; emit('navigate', 'college-' + item.module, 'all', item.id); }
onMounted(async () => { await check(); if (!stopped) timer = setInterval(check, 60000); });
onUnmounted(() => { stopped = true; if (timer) clearInterval(timer); });
</script>

<template>
  <el-button class="reminder-settings" text @click="openSettings">任务提醒设置</el-button>
  <el-dialog v-model="show" title="待办提醒" width="min(92vw, 500px)" :close-on-click-modal="false">
    <ul class="reminder-items">
      <li v-for="item in items" :key="item.id">
        <div><strong>{{ item.title }}</strong><small>时间：{{ new Date(item.dueAt).toLocaleString('zh-CN') }}</small></div>
        <el-button :aria-label="'查看：' + item.title" @click="openItem(item)">查看事项</el-button>
      </li>
    </ul>
    <template #footer><el-button @click="show = false">稍后提醒</el-button><el-button @click="openSettings">提醒设置</el-button></template>
  </el-dialog>
  <el-dialog v-model="settings" title="任务提醒设置" width="min(92vw, 480px)" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
    <p v-if="loading" role="status">正在读取你的提醒设置…</p>
    <el-form v-if="prefs" label-position="top" :disabled="saving">
      <el-form-item label="开启弹窗提醒"><el-switch v-model="prefs.enabled" /></el-form-item>
      <el-form-item label="每隔多少分钟提醒一次"><el-input-number v-model="prefs.interval" :min="10" :max="60" /></el-form-item>
      <el-form-item label="到期前多少小时开始提醒"><el-input-number v-model="prefs.leadHours" :min="1" :max="48" /></el-form-item>
    </el-form>
    <p class="reminder-help">例如：提前 24 小时开始提醒，每隔 30 分钟提示一次。关闭弹窗后站内消息仍保留，22:00～08:00 不弹窗。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-button v-if="error && !prefs" :loading="loading" @click="openSettings">重新读取</el-button>
    <template #footer><el-button :disabled="saving" @click="settings = false">取消</el-button><el-button type="primary" :disabled="!prefs || loading" :loading="saving" @click="save">保存</el-button></template>
  </el-dialog>
</template>

<style scoped>
.reminder-settings { margin: 8px 0; }
.reminder-items { list-style: none; padding: 0; margin: 0; max-height: 50vh; overflow: auto; }
.reminder-items li { display: flex; align-items: center; gap: 12px; padding: 14px 0; border-bottom: 1px solid var(--border); }
.reminder-items li > div { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.reminder-items strong, .reminder-items small { display: block; }
.reminder-items small { margin-top: 6px; color: var(--muted); }
.reminder-help { margin: 14px 0; line-height: 1.7; }
@media print { .reminder-settings { display: none; } }
</style>
