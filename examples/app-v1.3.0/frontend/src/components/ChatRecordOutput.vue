<script setup lang="ts">
import { ref, onBeforeUnmount } from 'vue'
import { api, getToken } from '../api'
import { chatOutput, clearPrintTable, exportTable, exportTableText, printTable } from '../../shared/table-output'
const props = defineProps<{ conversationId: string }>()
const from = ref(''), through = ref(''), busy = ref(false), error = ref('')
const emit = defineEmits<{ (event: 'close'): void }>()
let alive = true
onBeforeUnmount(() => { alive = false; clearPrintTable() })
async function output(kind: 'csv' | 'text' | 'print') {
  if (busy.value) return
  error.value = ''
  if (from.value && through.value && from.value > through.value) { error.value = '开始日期不能晚于结束日期'; return }
  busy.value = true
  const token = getToken(), id = props.conversationId
  try {
    const report = await api(`/chat/conversations/${encodeURIComponent(id)}/export?${new URLSearchParams({ from: from.value, through: through.value })}`)
    if (!alive || token !== getToken() || id !== props.conversationId) return
    if (!report.items.length) { error.value = '这个日期范围内没有聊天记录，可调整日期后重试。'; return }
    const table = chatOutput(report)
    if (kind === 'print') printTable(table); else if (kind === 'csv') exportTable(table); else exportTableText(table)
  } catch (e: any) { if (alive && token === getToken()) error.value = e.message || '操作失败，请重试' }
  finally { if (alive) busy.value = false }
}
</script>
<template>
  <el-dialog :model-value="true" title="导出 / 打印聊天记录" width="min(94vw, 580px)" :close-on-click-modal="false" @close="emit('close')">
    <p class="output-hint">留空导出全部记录，最多 5000 条；日期按北京时间计算。</p>
    <div class="output-dates"><label>开始日期<input v-model="from" type="date" :disabled="busy" aria-label="聊天记录开始日期" /></label><label>结束日期<input v-model="through" type="date" :disabled="busy" aria-label="聊天记录结束日期" /></label></div>
    <p class="output-hint">包含文字、表情说明和附件名称。附件原文件请在会话中下载；打印窗口可选择“另存为 PDF”。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template #footer><div class="output-actions"><el-button :loading="busy" @click="output('text')">导出文字 TXT</el-button><el-button :loading="busy" @click="output('csv')">导出表格 CSV</el-button><el-button type="primary" :loading="busy" @click="output('print')">打印 / 存为 PDF</el-button></div></template>
  </el-dialog>
</template>
<style scoped>
.output-hint{line-height:1.7;color:var(--muted,#64748b)}.output-dates{display:grid;grid-template-columns:1fr 1fr;gap:16px}.output-dates label{display:grid;gap:8px;min-width:0}.output-dates input{box-sizing:border-box;width:100%;min-width:0;padding:10px;border:1px solid var(--line,#dbe3ed);border-radius:8px;background:var(--surface,#fff);color:inherit}.output-actions{display:flex;gap:8px;flex-wrap:wrap;justify-content:flex-end}.output-actions .el-button{margin:0}@media(max-width:400px){.output-dates{grid-template-columns:1fr}.output-actions .el-button{flex:1 1 120px}}
</style>
