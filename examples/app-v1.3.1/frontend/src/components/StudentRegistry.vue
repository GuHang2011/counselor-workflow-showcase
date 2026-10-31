<template>
  <section class="registry" v-loading="busy">
    <header><div><h2>{{ user.role === 'student' ? '档案资料' : '学生名单' }}</h2><p class="registry-count" aria-live="polite">当前列表 {{ students.length }} 人</p></div><div class="registry-output"><el-button :loading="busy" @click="load"><el-icon><Refresh /></el-icon>刷新</el-button><el-button :disabled="busy || outputBusy || !!error || !students.length" :loading="outputBusy" @click="output(false)"><el-icon><Download /></el-icon>导出名单</el-button><el-button :disabled="busy || outputBusy || !!error || !students.length" @click="output(true)"><el-icon><Printer /></el-icon>打印名单</el-button><el-button v-if="canMaintain" type="primary" :disabled="busy" @click="importOpen=true"><el-icon><Upload /></el-icon>批量导入</el-button></div></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <form v-if="user.role !== 'student'" class="registry-filters" aria-label="筛选学生" @submit.prevent="load">
      <div class="filter-fields">
        <label for="student-query">查找学生<el-input id="student-query" v-model="filter.q" placeholder="姓名 / 学号" prefix-icon="Search" clearable /></label>
        <label for="student-class">班级<el-input id="student-class" v-model="filter.className" placeholder="班级" clearable /></label>
        <label for="student-grade">年级<el-input id="student-grade" v-model="filter.grade" placeholder="年级" clearable /></label>
        <div class="filter-field"><label for="student-status">学籍状态</label><el-select id="student-status" v-model="filter.status" aria-label="学籍状态" clearable placeholder="学籍状态"><el-option v-for="s in statuses" :key="s" :value="s" :label="s" /></el-select></div>
      </div>
      <div class="filter-footer"><span>填写条件后查询，留空表示不限。</span><div><el-button :disabled="busy" @click="resetFilters">清空筛选</el-button><el-button native-type="submit" type="primary" :loading="busy"><el-icon><Search /></el-icon>查询</el-button></div></div>
      <el-collapse class="saved-filters"><el-collapse-item title="常用筛选（可选）"><div class="saved-filter-actions"><el-select aria-label="常用筛选" placeholder="常用筛选" :disabled="busy" @change="applyFilter"><el-option v-for="f in filters" :key="f.id" :value="f.id" :label="f.name" /></el-select><el-button :disabled="busy" @click="saveFilter">保存常用筛选</el-button></div></el-collapse-item></el-collapse>
    </form>
    <p class="hint output-hint">导出和打印使用当前列表，包含学籍信息，不含私人联系方式。</p>
    <el-table :data="students" @row-click="openStudent" :empty-text="hasFilters?'没有找到符合条件的学生，可清空筛选后重试':'当前没有可查看的学生档案'"><el-table-column prop="studentNo" label="学号" min-width="140" /><el-table-column prop="name" label="姓名" min-width="100" /><el-table-column prop="college" label="学院" min-width="180" /><el-table-column prop="major" label="专业" min-width="130" /><el-table-column prop="grade" label="年级" width="90" /><el-table-column prop="className" label="班级" min-width="140" /><el-table-column prop="enrollmentStatus" label="学籍" width="100" /><el-table-column label="操作" width="110" fixed="right"><template #default="{row}"><el-button link type="primary" @click.stop="openStudent(row)">查看档案</el-button></template></el-table-column></el-table>
    <template v-if="handovers.length"><h3>学生交接记录</h3><article v-for="h in handovers" :key="h.id" class="card"><strong>{{ h.fromClass }} → {{ h.toClass }}</strong><p>状态：{{ h.status === 'accepted' ? '已接收' : '待确认接替' }} · {{ h.items.length }} 项未结事项</p><p>保密记录保留原权限，须单独授权；历史不会因转班删除。</p><el-button v-if="h.counselorId===user.id && h.status!=='accepted'" @click="accept(h)">确认交接</el-button><el-table :data="h.items"><el-table-column label="事项类型"><template #default="{row}">{{handoverKinds[row.kind] || '学生事务'}}</template></el-table-column><el-table-column prop="id" label="事项编号" /><el-table-column prop="disposition" label="交接方式" /></el-table></article></template>
    <el-dialog v-model="detailOpen" title="学生档案" width="min(96vw, 900px)" :close-on-click-modal="false">
      <template v-if="detail"><p class="hint">最近更新：{{ fmt(detail.profile.updatedAt) }}</p><el-descriptions :column="2" border><el-descriptions-item v-for="f in fields" :key="f.key" :label="f.label">{{displayValue(f.key,detail.profile[f.key])}}<small v-if="detail.profile.fieldSources?.[f.key]">{{ sourceLabel(detail.profile.fieldSources[f.key].source) }} · {{ fmt(detail.profile.fieldSources[f.key].updatedAt) }}</small></el-descriptions-item></el-descriptions>
        <h3>{{ user.role==='student' ? '申请更正联系方式' : '修改学生资料' }}</h3><el-form label-position="top" @submit.prevent><div class="grid"><el-form-item v-for="f in editableFields" :key="f.key" :label="f.label"><el-select v-if="f.key==='enrollmentStatus'" v-model="edit[f.key]"><el-option v-for="s in statuses" :key="s" :value="s" :label="s" /></el-select><el-input v-else v-model="edit[f.key]" /></el-form-item></div><el-form-item label="变更原因" required><el-input v-model="reason" type="textarea" /></el-form-item><el-button type="primary" :loading="busy" @click="saveProfile">{{ user.role==='student' ? '提交变更申请' : '保存修改' }}</el-button></el-form>
        <template v-if="canMaintain"><h3>转班与分工交接</h3><div class="grid"><el-input v-model="transfer.className" placeholder="转入班级（接替辅导员须负责该班）" /><el-select v-model="transfer.counselorId" filterable placeholder="接替辅导员"><el-option v-for="u in counselors" :key="u.id" :value="u.id" :label="`${u.name} · ${u.className}`" /></el-select><el-input v-model="transfer.reason" placeholder="交接原因" /><el-button :loading="busy" @click="transferStudent">确认转班并生成交接清单</el-button></div></template>
        <template v-if="detail.changes.length"><h3>资料变更申请</h3><article v-for="c in detail.changes" :key="c.id" class="card"><p>{{ c.reason }} · {{changeStates[c.status] || '处理中'}}</p><p v-for="(v,k) in c.values" :key="k">{{ label(String(k)) }}：{{ v }}</p><p v-if="c.reviewReason">审核说明：{{ c.reviewReason }}</p><template v-if="user.role!=='student' && c.status==='pending'"><el-button @click="review(c,true)">通过</el-button><el-button @click="review(c,false)">驳回并说明</el-button></template></article></template>
        <el-collapse v-if="detail.history.length"><el-collapse-item title="查看历史版本"><article class="history" v-for="h in detail.history" :key="h.id"><strong>{{ fmt(h.createdAt) }} · {{ sourceLabel(h.source) }}</strong><p>{{ h.reason }}</p><p v-for="f in changedFields(h)" :key="f.key">{{ f.label }}：{{displayValue(f.key,h.before[f.key])}} → {{displayValue(f.key,h.after[f.key])}}</p></article></el-collapse-item></el-collapse>
      </template>
    </el-dialog>
    <el-dialog v-model="importOpen" title="导入学生名单" width="min(96vw, 1000px)" :close-on-click-modal="false"><p>① 下载模板并填写学生资料 → ② 选择文件，检查内容 → ③ 确认导入。检查通过前不会修改学生档案。</p><p class="hint">支持 Excel（.xlsx，第一个工作表）或 CSV 文件；学号请设为文本，班级名称须与系统一致。新增学生账号需由管理员启用后才能登录。</p><el-button @click="downloadTemplate">下载 CSV 模板</el-button><input type="file" accept=".csv,.xlsx" :disabled="busy" aria-label="选择学生档案导入文件" @change="selectFile" /><el-form-item label="文件列名格式"><el-select v-model="columnStyle" @change="batch=undefined"><el-option label="中文列名（当前下载的模板）" value="chinese" /><el-option label="英文列名（旧版模板）" value="legacy" /></el-select></el-form-item><el-collapse><el-collapse-item title="我的表格列名不同（可选设置）"><div class="grid"><label v-for="f in fields" :key="f.key">{{ f.label }}<el-input v-model="mapping[f.key]" @input="batch=undefined" :placeholder="columnStyle==='chinese'?importLabel(f):f.key" /></label></div></el-collapse-item></el-collapse><el-button :loading="busy" type="primary" @click="previewImport">检查导入内容</el-button><template v-if="batch"><p class="counts">新增 {{batch.counts.create}} · 更新 {{batch.counts.update}} · 跳过 {{batch.counts.skip}} · 错误 {{batch.counts.error}}</p><el-table :data="batch.rows" max-height="360"><el-table-column prop="row" label="行号" width="70" /><el-table-column label="学号"><template #default="{row}">{{row.values.studentNo}}</template></el-table-column><el-table-column label="姓名"><template #default="{row}">{{row.values.name}}</template></el-table-column><el-table-column label="处理方式" width="100"><template #default="{row}">{{importStates[row.action]||'需修正'}}</template></el-table-column><el-table-column prop="error" label="错误原因" min-width="260" /></el-table><el-input v-model="importReason" placeholder="填写导入原因，例如：2026 级新生名单核对" /><el-checkbox v-model="validOnly">只导入检查通过的学生，错误行本次不导入</el-checkbox><div><el-button @click="exportErrors" :disabled="!batch.counts.error">导出错误清单</el-button><el-button type="primary" :disabled="batch.committed || (!validOnly && batch.counts.error>0) || !(batch.counts.create+batch.counts.update)" :loading="busy" @click="commitImport">{{batch.committed?'已完成导入':'确认导入'}}</el-button></div></template></el-dialog>
  </section>
</template>
<script setup lang="ts">
import { csvCell } from '../../shared/csv';
import { exportTable, printTable, clearPrintTable } from '../../shared/table-output';
import { getToken } from '@/api';
import {computed,onMounted,onBeforeUnmount,reactive,ref} from 'vue';import {ElMessage,ElMessageBox} from 'element-plus';import {api} from '@/api';
const handoverKinds:Record<string,string>={task:'工作任务',request:'学生申请',worklog:'学生工作记录'};
const changeStates:Record<string,string>={pending:'待审核',approved:'已通过',rejected:'已退回'};
const importStates:Record<string,string>={create:'新增学生',update:'更新资料',skip:'无需更新',error:'需修正'};
const props=defineProps<{user:Record<string,any>;users:Record<string,any>[]}>();const user=computed(()=>props.user),canMaintain=computed(()=>['admin','counselor'].includes(user.value.role)),counselors=ref<any[]>([]);
const fields=[{key:'studentNo',label:'学号'},{key:'name',label:'姓名'},{key:'college',label:'学院'},{key:'major',label:'专业'},{key:'grade',label:'年级'},{key:'className',label:'班级'},{key:'enrollmentStatus',label:'学籍状态'},{key:'phone',label:'手机号'},{key:'email',label:'邮箱'},{key:'counselorId',label:'辅导员'}];const statuses=['在籍','休学','毕业','退学','归档'];const editableFields=computed(()=>fields.filter(f=>user.value.role==='student'?['phone','email'].includes(f.key):!['className','counselorId',...(user.value.role==='admin'?['phone','email']:[])].includes(f.key)));
const busy=ref(false),error=ref(''),students=ref<any[]>([]),filters=ref<any[]>([]),handovers=ref<any[]>([]),detail=ref<any>(),detailOpen=ref(false),reason=ref(''),edit=reactive<Record<string,any>>({}),filter=reactive({q:'',className:'',grade:'',status:''}),transfer=reactive({className:'',counselorId:'',reason:''});
const hasFilters=computed(()=>Object.values(filter).some(Boolean));
const outputBusy=ref(false);let alive=true;onBeforeUnmount(()=>{alive=false;clearPrintTable()});
async function output(printing:boolean){if(outputBusy.value||busy.value||error.value||!students.value.length)return;const token=getToken(),ids=students.value.map(s=>s.id);outputBusy.value=true;try{const fresh=await api('/students/export',{method:'POST',body:{recordIds:ids}});if(!alive||token!==getToken())return;const cols=fields.filter(f=>!['phone','email','counselorId'].includes(f.key));const table={title:'学生名单',headers:cols.map(f=>f.label),rows:fresh.map((row:any)=>cols.map(f=>row[f.key]||'')),note:'按当前列表范围生成'};if(printing)printTable(table);else exportTable(table)}catch(e:any){if(alive&&token===getToken())ElMessage.error(e.message||'输出失败，请重试')}finally{if(alive)outputBusy.value=false}}
function resetFilters(){Object.assign(filter,{q:'',className:'',grade:'',status:''});load()}
function displayValue(key:string,value:any){if(key==='counselorId')return value?(counselors.value.find(c=>c.id===value)?.name||props.users.find(u=>u.id===value)?.name||'已分配辅导员'):'未分配';return value===undefined||value===null||value===''?'未填写':String(value)}
function sourceLabel(value:string){return value?.startsWith('表格导入')?'表格导入':value||'学生档案'}
async function run(fn:()=>Promise<void>){busy.value=true;error.value='';try{await fn()}catch(e:any){error.value=e.message;ElMessage.error(e.message)}finally{busy.value=false}}
async function load(){await run(async()=>{students.value=await api('/students?'+new URLSearchParams(filter));filters.value=await api('/student-filters');if(user.value.role!=='student'){handovers.value=await api('/student-handovers');counselors.value=(await api('/organization-directory')).counselors}})}
async function openStudent(p:any){Object.assign(transfer,{className:'',counselorId:'',reason:''});await run(async()=>{detail.value=await api(`/students/${p.id}`);for(const k of Object.keys(edit))delete edit[k];for(const f of editableFields.value)edit[f.key]=detail.value.profile[f.key]||'';reason.value='';detailOpen.value=true})}
async function saveProfile(){if(!reason.value.trim())return ElMessage.warning('请填写变更原因，便于后续核对');await run(async()=>{const id=detail.value.profile.id;await api(user.value.role==='student'?`/students/${id}/change-requests`:`/students/${id}`,{method:user.value.role==='student'?'POST':'PUT',body:{values:{...edit},reason:reason.value}});detail.value=await api(`/students/${id}`);students.value=await api('/students?'+new URLSearchParams(filter));reason.value='';ElMessage.success(user.value.role==='student'?'更正申请已提交，审核通过后更新档案':'学生资料已更新')})}
async function transferStudent(){if(!transfer.className.trim()||!transfer.counselorId||!transfer.reason.trim())return ElMessage.warning('请填写转入班级、选择接替辅导员，并说明交接原因');await run(async()=>{await api(`/students/${detail.value.profile.id}/transfer`,{method:'POST',body:transfer});detailOpen.value=false;students.value=await api('/students?'+new URLSearchParams(filter));handovers.value=await api('/student-handovers');ElMessage.success('已生成交接清单')})}
async function accept(h:any){try{const{value}=await ElMessageBox.prompt('填写交接确认说明','确认接替',{inputValidator:v=>!!v?.trim()||'请填写说明'});await run(async()=>{await api(`/student-handovers/${h.id}/accept`,{method:'POST',body:{reason:value}});handovers.value=await api('/student-handovers')})}catch{}}
async function review(c:any,approved:boolean){try{const{value}=await ElMessageBox.prompt('填写审核说明','资料变更审核',{inputValidator:v=>!!v?.trim()||'请填写说明'});await run(async()=>{await api(`/students/change-requests/${c.id}/decide`,{method:'POST',body:{approved,reason:value}});detail.value=await api(`/students/${detail.value.profile.id}`)})}catch{}}
async function saveFilter(){try{const{value}=await ElMessageBox.prompt('给当前筛选起个名字，例如：2026级在籍学生','保存常用筛选',{inputValidator:v=>!!v?.trim()||'请填写筛选名称'});await run(async()=>{await api('/student-filters',{method:'POST',body:{...filter,name:value}});filters.value=await api('/student-filters')})}catch{}}
function applyFilter(id:string){const f=filters.value.find(x=>x.id===id);if(f){for(const k of Object.keys(filter)) (filter as any)[k]=f[k]||'';load()}}
function fmt(t:string){return t?new Date(t).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'}):'—'}function label(k:string){return fields.find(f=>f.key===k)?.label||k}function changedFields(h:any){return fields.filter(f=>String(h.before[f.key]||'')!==String(h.after[f.key]||''))}
const columnStyle=ref('chinese');
const importOpen=ref(false),importFile=ref<File>(),mapping=reactive<Record<string,string>>({}),batch=ref<any>(),validOnly=ref(false),importReason=ref('');
function selectFile(e:Event){importFile.value=(e.target as HTMLInputElement).files?.[0];batch.value=undefined}
async function previewImport(){await run(async()=>{if(!importFile.value)throw new Error('请选择导入文件');const body=new FormData();body.append('file',importFile.value);body.append('mapping',JSON.stringify(Object.fromEntries(fields.map(f=>[f.key,mapping[f.key]?.trim()||(columnStyle.value==='chinese'?importLabel(f):f.key)]))));batch.value=await api('/students/import/preview',{method:'POST',body})})}
async function commitImport(){if(!importReason.value.trim())return ElMessage.warning('请填写导入原因');await run(async()=>{batch.value=await api(`/students/import/${batch.value.id}/commit`,{method:'POST',body:{validOnly:validOnly.value,reason:importReason.value}});students.value=await api('/students?'+new URLSearchParams(filter));ElMessage.success('学生名单已导入')})}
function csv(name:string,rows:any[][]){const body='\ufeff'+rows.map(r=>r.map(csvCell).join(',')).join('\r\n');const u=URL.createObjectURL(new Blob([body],{type:'text/csv;charset=utf-8'}));const a=document.createElement('a');a.href=u;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(u),1000)}
function importLabel(f:{key:string;label:string}){return f.key==='counselorId'?'辅导员编号（选填）':f.label}
function downloadTemplate(){columnStyle.value='chinese';batch.value=undefined;for(const key of Object.keys(mapping))delete mapping[key];csv('学生档案模板.csv',[fields.filter(f=>f.key!=='counselorId').map(importLabel),['20260001','示例学生','智能工程技术学院','智能工程','2026','请填已建班级','在籍','','']])}function exportErrors(){csv('学生导入错误清单.csv',[['行号','学号','姓名','错误原因'],...batch.value.rows.filter((r:any)=>r.error).map((r:any)=>[r.row,r.values.studentNo,r.values.name,r.error])])}onMounted(load);
</script>
<style scoped>
.registry { min-width: 0; }
.registry header { display:flex; flex-wrap:wrap; justify-content:space-between; gap:16px; align-items:center; margin-bottom:20px; }
.registry h2 { margin:0; font-size:18px; font-weight:600; }
.registry p,.hint { color:var(--muted); line-height:1.7; font-size:13px; }
.registry-count { margin-top:4px; }
.registry-output,.saved-filter-actions { display:flex; flex-wrap:wrap; gap:8px; }
.registry-output .el-button,.saved-filter-actions .el-button { margin:0; }
.registry-output :deep(.el-button>span),.filter-footer :deep(.el-button>span) { gap:6px; }
.registry-filters { padding:18px 20px 0; border:1px solid var(--line); border-radius:8px; background:#f8fafc; margin-bottom:18px; }
.filter-fields { display:grid; grid-template-columns:1.4fr 1fr .8fr 1fr; gap:16px; }
.filter-fields label { display:block; font-size:14px; color:var(--ink); line-height:1.5; }
.filter-fields .el-input,.filter-fields .el-select { display:flex; width:100%; margin-top:8px; }
.filter-field { min-width:0; }
.filter-footer { display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:12px; padding:16px 0; }
.filter-footer>span { color:var(--muted); font-size:12px; }
.saved-filters { border-bottom:0; }
.saved-filters :deep(.el-collapse-item__header) { background:transparent; font-size:13px; color:var(--muted); height:42px; }
.saved-filters :deep(.el-collapse-item__wrap) { background:transparent; border:0; }
.saved-filters :deep(.el-collapse-item__content) { padding-bottom:16px; }
.saved-filter-actions .el-select { width:240px; max-width:100%; }
.output-hint { margin-bottom:12px; }
.grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:14px; margin:16px 0; }
.grid .el-select { width:100%; }
.card { border:1px solid var(--line); padding:16px; border-radius:8px; margin:12px 0; }
.history { padding:12px; border-bottom:1px solid var(--line); }
.registry small { display:block; color:var(--muted); font-size:12px; margin-top:4px; }
.counts { font-weight:600; }
.registry :deep(.el-alert) { margin:16px 0; }
.registry input[type=file] { display:block; margin:16px 0; }
@media(max-width:1100px) { .filter-fields { grid-template-columns:repeat(2,minmax(0,1fr)); } }
@media(max-width:640px) {
 .grid { grid-template-columns:1fr; }.registry header { align-items:flex-start; }.registry-output { width:100%; display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); }.registry-output .el-button { min-height:40px; }
 .registry-filters { padding:14px 14px 0; }.filter-fields { gap:14px 12px; }.filter-footer>span { width:100%; }.filter-footer>div { margin-left:auto; }.filter-footer .el-button { min-height:40px; }
 .saved-filter-actions .el-select { width:100%; }
}
</style>
