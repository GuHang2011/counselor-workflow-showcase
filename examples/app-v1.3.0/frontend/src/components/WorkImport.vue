<script setup lang="ts">
import { computed, ref, onBeforeUnmount } from 'vue'
import { api, getToken } from '../api'
import { exportTable } from '../../shared/table-output'
const props = defineProps<{ module: 'tasks' | 'beds' }>()
const emit = defineEmits<{ (event: 'close'): void; (event: 'imported'): void }>()
const file = ref<File>(), batch = ref<any>(), busy = ref(false), error = ref('')
const title = computed(() => props.module === 'tasks' ? '导入工作任务' : '导入宿舍床位')
let alive = true
function beforeClose(done: () => void) { if (!busy.value) done() }
onBeforeUnmount(() => { alive = false })
function choose(event: Event) { file.value = (event.target as HTMLInputElement).files?.[0]; batch.value = undefined; error.value = '' }
function template() {
  exportTable(props.module === 'tasks' ? { title: '工作任务导入模板', headers: ['任务标题', '任务要求', '接收人账号', '截止时间', '开始时间', '需要交文件', '需要审核'], rows: [['核对班级资料', '请核对并提交资料', '请替换为实际工号或学号', '请填未来日期，如2026-12-01 17:00', '', '是', '是']] } : { title: '宿舍床位导入模板', headers: ['校区', '楼栋', '房间', '床号', '住宿条件', '来源'], rows: [['主校区', '1号楼', '101', '1', '女生', '宿舍台账']] })
}
async function run(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { await action() } catch (e: any) { if (alive) error.value = e.message || '操作失败，请重试' } finally { if (alive) busy.value = false }
}
async function preview() {
  batch.value = undefined
  await run(async () => {
    if (!file.value) throw new Error('请选择 CSV 或 Excel 文件')
    const token = getToken(), body = new FormData(); body.append('file', file.value)
    const result = await api(`/college-work/bulk-import/${props.module}/preview`, { method: 'POST', body })
    if (alive && token === getToken()) batch.value = result
  })
}
async function commit() {
  if (!batch.value || batch.value.errorCount || batch.value.committed) return
  await run(async () => {
    const token = getToken(), id = batch.value.id
    const result = await api(`/college-work/bulk-import/${id}/commit`, { method: 'POST' })
    if (alive && token === getToken()) { batch.value = result; emit('imported') }
  })
}
async function recipients() {
  await run(async () => {
    const token = getToken(), rows = await api('/college-work/bulk-import/tasks/recipients')
    if (alive && token === getToken()) exportTable({ title: '可选接收人账号', headers: ['接收人账号', '姓名', '班级'], rows: rows.map((r: any) => [r.account, r.name, r.className]) })
  })
}
function errors() { exportTable({ title: title.value + '错误清单', headers: ['行号', '内容', '错误原因'], rows: batch.value.rows.filter((r: any) => r.error).map((r: any) => [r.row, Object.values(r.values).join(' / '), r.error]) }) }
</script>
<template>
  <el-dialog :model-value="true" :title="title" width="min(96vw, 920px)" :close-on-click-modal="false" :before-close="beforeClose" @close="emit('close')">
    <p class="import-guide">① 下载模板并填写 → ② 选择文件并检查 → ③ 确认导入</p>
    <p class="import-help">支持 CSV（UTF-8）和 Excel（.xlsx 第一个工作表），每次最多 500 行、10 MB。检查不会修改数据，错误行修正后再整批导入。</p>
    <p v-if="module === 'tasks'" class="import-help">每行一项任务、一名接收人。账号及日期请设为文本，日期按北京时间填写。确认后正式发布任务；不导入进度、审核结果或已完成状态。</p>
    <p v-else class="import-help">导入新床位；重复床位不覆盖已有入住信息。旧版英文列名模板仍可使用。</p>
    <div class="import-actions"><el-button :disabled="busy" @click="template">下载导入模板</el-button><el-button v-if="module === 'tasks'" :loading="busy" @click="recipients">下载接收人账号清单</el-button></div>
    <label class="import-file">选择导入文件<input type="file" accept=".csv,.xlsx" :disabled="busy" :aria-label="title + '文件'" @change="choose" /></label>
    <el-button type="primary" :disabled="!file" :loading="busy" @click="preview">检查导入内容</el-button>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-if="batch"><p role="status">共 {{ batch.count }} 行 · 检查通过 {{ batch.validCount }} 行 · 需修正 {{ batch.errorCount }} 行</p><el-table :data="batch.rows" max-height="320"><el-table-column prop="row" label="行号" width="70"/><el-table-column :label="module === 'tasks' ? '任务 / 接收人' : '床位'" min-width="200"><template #default="{ row }">{{ module === 'tasks' ? `${row.values.title || ''} / ${row.values.assigneeName || row.values.account || ''}` : [row.values.campus,row.values.building,row.values.room,row.values.bed].join(' / ') }}</template></el-table-column><el-table-column prop="error" label="检查结果" min-width="240"><template #default="{ row }">{{ row.error || '检查通过' }}</template></el-table-column></el-table><el-button v-if="batch.errorCount" @click="errors">导出错误清单</el-button><el-alert v-if="batch.committed" title="导入完成，列表已刷新。" type="success" :closable="false" /></template>
    <template #footer><el-button :disabled="busy" @click="emit('close')">关闭</el-button><el-button type="primary" :disabled="!batch || !!batch.errorCount || batch.committed" :loading="busy" @click="commit">{{ batch?.committed ? '已完成导入' : module === 'tasks' ? '确认导入并发布任务' : '确认导入床位' }}</el-button></template>
  </el-dialog>
</template>
<style scoped>
.import-guide{font-weight:600;line-height:1.8}.import-help{color:var(--muted,#64748b);line-height:1.7}.import-actions{display:flex;flex-wrap:wrap;gap:8px}.import-actions .el-button{margin:0}.import-file{display:grid;gap:8px;margin:18px 0}.import-file input{max-width:100%}.el-alert{margin-top:12px}
</style>
