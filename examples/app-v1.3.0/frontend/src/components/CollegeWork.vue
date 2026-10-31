<script setup lang="ts">
import { exportTable, printTable, outputTime, clearPrintTable } from '../../shared/table-output';
import WorkImport from './WorkImport.vue';
import { isRecordOverdue, compareTaskPriority } from '../../shared/college-work';
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Download, Printer, Share, Upload, Refresh } from '@element-plus/icons-vue';
import { api, getToken } from '../api';
import MaterialUpload from './MaterialUpload.vue';
import CollegeReports from './CollegeReports.vue';
type Row=Record<string,any>;
const props=defineProps<{user:Row;section:string;initialFilter?:string;initialRecordId?:string;recordRequest?:number}>();
const rows=ref<Row[]>([]),people=ref<Row[]>([]),beds=ref<Row[]>([]),housing=ref<Row[]>([]);
const reviewSelection=ref<Row[]>([]);
const taskTable=ref<any>(null);
const clock=ref(Date.now());
const clockTimer=window.setInterval(()=>{clock.value=Date.now();},60000);
onUnmounted(()=>window.clearInterval(clockTimer));
const canReview=(row:Row)=>row.status==='submitted'&&(row.ownerId===props.user.id||props.user.role==='admin');
const isOverdue=(row:Row)=>isRecordOverdue(row,clock.value);
async function bulkReview(action:string){
 if(saving.value)return;
 const records=reviewSelection.value.filter(canReview).map(r=>({id:r.id,version:r.version}));
 if(!records.length)return ElMessage.warning('请选择需要审核的任务');
 saving.value=true;
 try{
  const {value}=await ElMessageBox.prompt(action==='return'?'填写退回原因与修改要求':'填写审核意见',`审核选中的 ${records.length} 项任务`,{inputType:'textarea',inputValidator:v=>!!v?.trim()||'请填写审核意见',confirmButtonText:'保存审核',cancelButtonText:'取消'});
  await api('/college-work/review',{method:'POST',body:{records,action,reason:value}});
  reviewSelection.value=[];await load();ElMessage.success('审核已保存');
 }catch(e:any){if(e?.message){error.value=e.message;ElMessage.error(e.message);}}finally{saving.value=false;}
}
const defaultFilter=()=>props.section==='tasks'?'open':['archive','dorms'].includes(props.section)?'all':'current';
const loadError=ref('');
const loading=ref(false),saving=ref(false),error=ref(''),query=ref(''),filter=ref(props.initialFilter||defaultFilter());
const taskScope=ref(['admin','leader'].includes(props.user.role)?'all':'received'),uploadBusy=ref(false),archiveSources=ref<Row[]>([]),archiveLoading=ref(false);
let lastOpenedId='',archiveLoad=0;
const dialog=ref(''),form=ref<Row>({}),selected=ref<Row|null>(null),preview=ref<Row[]>([]);
let formSnapshot = '';
const closePrompt = ref(false);
async function requestClose(done?: () => void) {
 if (saving.value || uploadBusy.value || closePrompt.value) return;
 if (dialog.value !== 'detail' && JSON.stringify(form.value) !== formSnapshot) {
  closePrompt.value = true;
  try {
   await ElMessageBox.confirm('本次填写的内容尚未保存，关闭后需要重新填写。', '放弃本次修改？', { confirmButtonText: '放弃修改', cancelButtonText: '继续填写', type: 'warning', distinguishCancelAndClose: true });
  } catch { return; } finally { closePrompt.value = false; }
 }
 if (done) done(); else dialog.value = '';
}
const dialogOpen=computed({get:()=>!!dialog.value,set:(value:boolean)=>{if(!value)dialog.value='';}});
const labels:Row={tasks:'工作任务',party:'入团入党',care:'谈话与学生跟进',hygiene:'卫生检查',dorms:'宿舍管理',archive:'工作材料归档'};
const states:Row={pending:'待办理',in_progress:'进行中',submitted:'待审核',completed:'已完成',returned:'退回修改',cancelled:'已取消',open:'跟进中',closed:'已结束跟进',archived:'已归档',rectifying:'待整改',available:'空床',reserved:'预留',occupied:'入住',maintenance:'维修',approved:'待交接',rejected:'已拒绝'};
const actionNames:Row={create:'创建记录',progress:'保存进度',submit:'提交完成',approve:'审核通过',return:'退回修改',extend:'延长截止时间',cancel:'取消任务',followup:'记录跟进',close:'结束跟进',advance:'更新发展阶段',rectify:'完成卫生整改'};
const stages:Row={league:['申请入团','培养考察','组织审批','入团登记'],party:['入党申请','积极分子','发展对象','预备党员','转正审查','正式党员']};
const isManager=computed(()=>['admin','counselor','mentor','leader','business','logistics'].includes(props.user.role));
const students=computed(()=>people.value.filter(p=>p.role==='student'));
const scopedRows=computed(()=>rows.value.filter(r=>props.section!=='tasks'||taskScope.value==='all'||(taskScope.value==='received'?r.assigneeId===props.user.id:r.ownerId===props.user.id)));
function changeTaskScope(){query.value='';filter.value=taskScope.value==='received'?'open':'all';reviewSelection.value=[];}
const hasFilters=computed(()=>!!query.value||filter.value!=='all');
const emptyHint=computed(()=>!scopedRows.value.length?(props.section==='tasks'?'这个范围内还没有任务':'还没有记录'):hasFilters.value?'没有符合筛选条件的记录，可清空筛选后查看':'还没有记录');
const dateColumnLabel=computed(()=>props.section==='tasks'?'任务截止时间':['care','party'].includes(props.section)?'下次跟进时间':props.section==='archive'?'归档时间':'检查时间');
function clearFilters(){query.value='';filter.value='all';}
const visible=computed(()=>{const result=scopedRows.value.filter(r=>{
 const isEnded=ended(r);
 const inFilter=filter.value==='all'||(filter.value==='history'?isEnded:filter.value==='overdue'?isOverdue(r):filter.value==='review'?canReview(r):filter.value==='submitted'?r.status==='submitted':filter.value==='scheduled'?isScheduled(r):filter.value==='open'?(!isEnded&&r.status!=='submitted'&&!isScheduled(r)):!isEnded);
 return inFilter&&[r.title,r.content,r.studentName,r.assigneeName,r.className,r.room].some(v=>String(v||'').toLowerCase().includes(query.value.trim().toLowerCase()));
});return props.section==='tasks'?result.sort((a,b)=>compareTaskPriority(a,b,clock.value)):result;});
function actionsFor(r:Row){
 if(ended(r))return [];
 const actions:{value:string;label:string}[]=[];
 if(r.assigneeId===props.user.id&&r.status!=='submitted'&&!isScheduled(r))actions.push({value:'progress',label:'保存进度'},{value:'submit',label:'提交完成'});
 if(r.ownerId===props.user.id||props.user.role==='admin'){
  if(r.status==='submitted')actions.push({value:'approve',label:'审核通过'},{value:'return',label:'退回修改'});
  actions.push({value:'extend',label:'延长截止时间'},{value:'cancel',label:'取消任务'});
 }
 return actions;
}
const taskActions=computed(()=>selected.value?actionsFor(selected.value):[]);
watch(filter,v=>{if(v==='review'&&props.section==='tasks')taskScope.value='all';},{immediate:true});
const returnReason=computed(()=>selected.value?.status==='returned'?(selected.value.history||[]).filter((h:Row)=>h.action==='return').slice(-1)[0]?.reason||'':'');
const taskActionHint=computed(()=>form.value.action==='progress'?'保存进度不会结束任务；全部完成后，请选择“提交完成”。':form.value.action==='submit'?(selected.value?.needsReview?'提交后进入待审核，审核通过后才记为已完成。':'提交后任务将直接记为已完成。'):'');
const saveLabel=computed(()=>{
 if(dialog.value==='allocation')return preview.value.length?'确认预留':'生成分配预览';
 if(dialog.value==='action'&&props.section==='tasks')return actionNames[form.value.action]||'保存办理';
 if(dialog.value==='action'&&props.section==='care')return form.value.action==='close'?'结束本次跟进':'保存本次跟进';
 if(dialog.value==='create'&&props.section==='care')return '保存谈话或跟进';
 return dialog.value==='create'&&props.section==='tasks'?'发布任务':'保存';
});
const archiveOptions=computed(()=>archiveSources.value.filter(r=>['completed','closed','cancelled'].includes(r.status)&&!rows.value.some(a=>a.sourceModule===form.value.sourceModule&&a.sourceId===r.id)));
async function loadArchiveSources(){const request=++archiveLoad;archiveLoading.value=true;archiveSources.value=[];try{const result=await api<Row[]>(`/college-work/${form.value.sourceModule}`);if(request===archiveLoad)archiveSources.value=result;}catch(e:any){if(request===archiveLoad)error.value=e.message;}finally{if(request===archiveLoad)archiveLoading.value=false;}}
function chooseArchive(id:string){const source=archiveOptions.value.find(r=>r.id===id);if(source){form.value.title=source.title;form.value.content=source.content||'';}}
watch(()=>form.value.sourceModule,()=>{if(dialog.value==='create'&&props.section==='archive'){form.value.sourceId='';loadArchiveSources();}});
const taskTotals=computed(()=>{const all=visible.value;return {total:all.length,done:all.filter(r=>r.status==='completed').length,overdue:all.filter(isOverdue).length,review:all.filter(r=>r.status==='submitted').length};});
const chart=computed(()=>Object.entries(states).map(([key,label])=>({label,count:visible.value.filter(r=>r.status===key).length})).filter(r=>r.count));
const classChart=computed(()=>{const m=new Map<string,{label:string;total:number;done:number}>();for(const r of visible.value.filter(r=>r.status!=='cancelled')){const label=r.className||r.college||'未分组',bar=m.get(label)||{label,total:0,done:0};bar.total++;if(r.status==='completed')bar.done++;m.set(label,bar);}return [...m.values()];});
const trend=computed(()=>{const counts=new Map<string,number>();for(const r of rows.value)if(r.status==='completed'&&r.completedAt){const date=new Date(r.completedAt).toLocaleDateString('zh-CN');counts.set(date,(counts.get(date)||0)+1);}return [...counts].map(([date,count])=>({date,count}));});
const roomStats=computed(()=>{const m=new Map<string,{room:string;count:number;score:number}>();for(const r of (props.section==='dorms'?rows.value:visible.value)){const v=m.get(r.room)||{room:r.room,count:0,score:0};v.count++;v.score+=Number(r.score);m.set(r.room,v);}return [...m.values()];});
const time=(v:string)=>v?new Date(v).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}):'—';
const ended=(r:Row)=>['completed','cancelled','closed','archived'].includes(r.status);
const isScheduled=(r:Row)=>!ended(r)&&r.status!=='submitted'&&!!r.startsAt&&new Date(r.startsAt).getTime()>clock.value;
const taskStatus=(r:Row)=>isScheduled(r)?'未开始':states[r.status]||r.status;
const taskNextStep=(r:Row)=>isScheduled(r)?`将于 ${time(r.startsAt)} 开始办理`:r.status==='submitted'?(canReview(r)?'请核对完成情况和附件，再通过或退回。':'已提交，等待审核，无须重复提交。'):r.status==='returned'?'请按退回要求修改，再提交完成。':!ended(r)&&r.assigneeId===props.user.id?'可随时保存进度，办完后点击“提交完成”。':'';
const reviewableRows=computed(()=>visible.value.filter(canReview));
const allReviewsSelected=computed(()=>reviewableRows.value.length>0&&reviewableRows.value.every(row=>reviewSelection.value.some(selected=>selected.id===row.id)));
function selectAllReviews(checked:boolean){taskTable.value?.clearSelection();if(checked)for(const row of reviewableRows.value)taskTable.value?.toggleRowSelection(row,true);reviewSelection.value=checked?[...reviewableRows.value]:[];}
function selectReview(row:Row,checked:boolean){taskTable.value?.toggleRowSelection(row,checked);}
watch(visible,async records=>{const available=new Map(records.filter(canReview).map(r=>[r.id,r]));const retained=reviewSelection.value.map(r=>available.get(r.id)).filter((r):r is Row=>!!r);await nextTick();taskTable.value?.clearSelection();for(const row of retained)taskTable.value?.toggleRowSelection(row,true);reviewSelection.value=retained;});
async function load(){loading.value=true;error.value='';loadError.value='';try{people.value=await api('/college-work/people');if(props.section==='dorms'){[beds.value,housing.value,rows.value]=await Promise.all([api('/college-work/dorms'),api('/college-work/housing'),api('/college-work/hygiene')]);}else rows.value=await api(`/college-work/${props.section}`);loading.value=false;openInitial();}catch(e:any){loadError.value=e.message||'记录暂未读取';rows.value=[];beds.value=[];housing.value=[];reviewSelection.value=[];}finally{loading.value=false;}}
function setDueInDays(days:number){form.value.dueAt=new Date(Date.now()+days*86400000).toISOString();}
function validateFollowup(){
 if(!['care','party'].includes(props.section))return '';
 if(dialog.value==='create'&&!form.value.studentId)return '请选择需要记录的学生';
 const needsDate=dialog.value==='create'||(dialog.value==='action'&&['followup','advance'].includes(form.value.action));
 if(needsDate&&!form.value.dueAt)return '请选择下次跟进时间';
 if(needsDate&&!Number.isFinite(new Date(form.value.dueAt).getTime()))return '请选择有效的下次跟进时间';
 if(dialog.value==='action'&&['followup','advance'].includes(form.value.action)&&new Date(form.value.dueAt).getTime()<=Date.now())return '下次跟进时间应晚于现在；无需继续跟进时请选择结束跟进';
 return '';
}
function openInitial(){const id=props.initialRecordId;if(!id||id===lastOpenedId)return;if(loading.value)return;const row=rows.value.find(r=>r.id===id);if(!row){ElMessage.info('事项已变化，请在列表中查看最新记录');lastOpenedId=id;return;}lastOpenedId=id;if(props.section==='tasks')taskScope.value=canReview(row)?'all':row.assigneeId===props.user.id?'received':'all';open(!ended(row)&&(props.section!=='tasks'||actionsFor(row).length)?'action':'detail',row);}
function open(kind:string,r:Row|null=null){selected.value=r;dialog.value=kind;preview.value=[];error.value='';uploadBusy.value=false;form.value={clientId:crypto.randomUUID(),title:'',content:'',assigneeIds:[],requiresFiles:false,needsReview:true,studentId:'',track:'league',type:'talk',level:'一般',dueAt:kind==='action'&&props.section==='care'?'':r?.dueAt||'',startsAt:'',basis:r?.basis||'',bedId:'',score:80,checkedAt:'',campus:'',building:'',room:'',bed:'',conditions:'',source:'',studentIds:[],bedIds:[],reservedUntil:'',conditionsVerified:false,actualHandover:false,handoverReference:'',targetBedId:'',recipientIds:[],sourceModule:'tasks',sourceId:'',attachmentIds:[...(r?.attachmentIds||[])],reason:'',progress:r?.progress||0,verified:false,action:kind==='action'?(props.section==='tasks'?actionsFor(r!)[0]?.value||'':props.section==='care'?'followup':props.section==='party'?'advance':'rectify'):'',...((kind==='action')?{version:r?.version}: {})};formSnapshot=JSON.stringify(form.value);if(kind==='create'&&props.section==='archive')loadArchiveSources();}
async function save(){if(saving.value)return;const validationMessage=validateFollowup();if(validationMessage)return ElMessage.warning(validationMessage);if(uploadBusy.value)return ElMessage.warning('请等待附件上传完成，或移除未完成的附件');if(dialog.value==='create'&&!String(form.value.title||'').trim())return ElMessage.warning('请填写事项标题');if(dialog.value==='create'&&props.section==='tasks'){if(!form.value.content.trim())return ElMessage.warning('请填写任务要求');if(!form.value.assigneeIds.length)return ElMessage.warning('请选择接收人');if(!form.value.dueAt)return ElMessage.warning('请选择截止时间');const due=new Date(form.value.dueAt).getTime();const start=form.value.startsAt?new Date(form.value.startsAt).getTime():Date.now();if(!Number.isFinite(due)||!Number.isFinite(start)||due<=Date.now()||due<=start)return ElMessage.warning('截止时间必须晚于当前时间和开始时间');}if(dialog.value==='create'&&props.section==='archive'&&!form.value.sourceId)return ElMessage.warning('请选择要归档的已办结事项');if(dialog.value==='action'){if(!form.value.action)return ElMessage.warning('请选择办理操作');if(!form.value.reason.trim())return ElMessage.warning(form.value.action==='return'?'请填写退回原因与修改要求':'请填写办理说明');if(form.value.action==='submit'&&selected.value?.requiresFiles&&!form.value.attachmentIds.length)return ElMessage.warning('此任务需要上传文件，请先选择材料');if(form.value.action==='extend'&&new Date(form.value.dueAt).getTime()<=new Date(selected.value!.dueAt).getTime())return ElMessage.warning('新的截止时间必须晚于原截止时间');}saving.value=true;error.value='';try{const b={...form.value};let url=`/college-work/${props.section}`;
 for(const key of ['dueAt','startsAt','reservedUntil','checkedAt'])if(b[key]){const date=new Date(b[key]);if(!Number.isFinite(date.getTime()))throw new Error('请选择有效的日期和时间');b[key]=date.toISOString();}
 if(dialog.value==='action')url+=`/${selected.value!.id}/action`;
 if(dialog.value==='bed')url='/college-work/beds';
 if(dialog.value==='hygiene')url='/college-work/hygiene';
 if(dialog.value==='share'){url='/college-work/share';Object.assign(b,{module:props.section,recordId:selected.value!.id});}
 if(dialog.value==='allocation'){url='/college-work/allocation';b.confirm=!!preview.value.length;const result=await api<Row[]>(url,{method:'POST',body:b});if(!b.confirm){preview.value=result;return;}}
 else if(dialog.value==='housing-action')await api(`/college-work/housing/${selected.value!.id}`,{method:'POST',body:{...b,version:selected.value!.version}});
 else if(dialog.value==='housing')await api('/college-work/housing',{method:'POST',body:b});
 else await api(url,{method:'POST',body:b});
 const publishingTask=dialog.value==='create'&&props.section==='tasks';
 dialog.value='';
 if(publishingTask){taskScope.value='published';filter.value='all';query.value='';ElMessage.success('任务已发布，已切换到“我发布的”查看进度');}
 else ElMessage.success('已保存');
 await load();}catch(e:any){error.value=e.message;}finally{saving.value=false;}}
function allocationChanged(){preview.value=[];}
const workImportOpen=ref(false),outputBusy=ref(false);
let outputAlive=true;onUnmounted(()=>{outputAlive=false;clearPrintTable()});
const filteredBeds=computed(()=>beds.value.filter(r=>(filter.value==='all'||r.status===filter.value)&&[r.title,r.campus,r.building,r.room,r.conditions,people.value.find(p=>p.id===(r.studentId||r.reservedFor))?.name].some(v=>String(v||'').toLowerCase().includes(query.value.trim().toLowerCase()))));
const outputRows=computed(()=>props.section==='dorms'?filteredBeds.value:visible.value);
async function output(records:Row[],module:string,printing=false){
 if(outputBusy.value||loading.value||loadError.value||!records.length)return;
 outputBusy.value=true;const token=getToken(),section=props.section;
 try{
  const fresh=await api<Row[]>('/college-work/export',{method:'POST',body:{module,recordIds:records.map(r=>r.id)}});
  if(!outputAlive||token!==getToken()||section!==props.section)return;
  const columns:Record<string,string[][]>={
   tasks:[['title','任务标题'],['assigneeName','接收人'],['className','班级'],['content','任务要求'],['status','状态'],['progress','进度%'],['progressNote','进度说明'],['dueAt','截止时间'],['completedAt','完成时间']],
   beds:[['campus','校区'],['building','楼栋'],['room','房间'],['bed','床号'],['status','状态'],['studentId','入住学生'],['reservedFor','预留学生'],['conditions','住宿条件'],['source','来源']],
   housing:[['title','办理事项'],['studentName','学生'],['type','办理类型'],['status','状态'],['content','说明'],['createdAt','创建时间'],['confirmedAt','交接时间']],
   care:[['title','标题'],['studentName','学生'],['content','谈话或跟进内容'],['status','状态'],['dueAt','下次跟进时间']],
   party:[['title','标题'],['studentName','学生'],['track','类别'],['stage','发展阶段'],['basis','核验依据'],['status','状态'],['dueAt','下次跟进时间']],
   hygiene:[['room','宿舍'],['title','标题'],['score','卫生分数'],['content','检查说明'],['status','状态'],['checkedAt','检查时间']],
   archive:[['title','标题'],['content','归档说明'],['sourceModule','来源事项'],['status','状态'],['archivedAt','归档时间']]
  };
  const cols=columns[module],title=module==='beds'?'宿舍床位':module==='housing'?'住宿人员变动':labels[module]||module;
  const cell=(row:Row,key:string)=>{const value=row[key];if(key==='status')return states[value]||value;if(key.endsWith('At'))return outputTime(value);if(['studentId','reservedFor'].includes(key))return people.value.find(p=>p.id===value)?.name||(value?'已分配（无姓名查看权限）':'');if(key==='type')return ({checkin:'入住',move:'调宿',checkout:'退宿'} as Row)[value]||value;if(key==='track')return value==='league'?'入团':'入党';if(key==='stage')return stages[row.track]?.[value]||'';if(key==='sourceModule')return labels[value]||value;return value??''};
  const table={title,headers:cols.map(c=>c[1]),rows:fresh.map(row=>cols.map(([key])=>cell(row,key))),note:'按当前列表范围生成；时间为北京时间'};
  if(printing)printTable(table);else exportTable(table);
 }catch(e:any){if(outputAlive&&token===getToken())ElMessage.error(e.message||'输出失败，请重试')}finally{if(outputAlive)outputBusy.value=false;}
}
function download(records:Row[]=outputRows.value){if(!Array.isArray(records))records=outputRows.value;return output(records,props.section==='dorms'?'beds':props.section)}
function print(){return output(outputRows.value,props.section==='dorms'?'beds':props.section,true)}
async function imported(){if(props.section==='tasks'){taskScope.value='published';filter.value='all';query.value='';}await load()}
watch(()=>props.section,()=>{workImportOpen.value=false;clearPrintTable();query.value='';filter.value=props.initialFilter||defaultFilter();dialog.value='';lastOpenedId='';load();});watch(()=>props.initialFilter,v=>{filter.value=v||defaultFilter();});watch(()=>[props.initialRecordId,props.recordRequest],()=>{lastOpenedId='';openInitial();});onMounted(load);
</script>
<template>
 <section class="college-work" :class="{'task-workspace':section==='tasks'}" v-loading="loading">
  <div v-if="section==='tasks'" class="toolbar task-scope-toolbar"><el-radio-group v-model="taskScope" aria-label="任务范围" @change="changeTaskScope"><el-radio-button label="received">我收到的</el-radio-button><el-radio-button v-if="isManager" label="published">我发布的</el-radio-button><el-radio-button v-if="isManager" label="all">全部可见任务</el-radio-button></el-radio-group><el-button v-if="isManager" type="primary" :icon="Plus" @click="open('create')">安排工作任务</el-button><el-button v-if="isManager" :icon="Upload" @click="workImportOpen=true">导入工作任务</el-button></div>
  <div class="toolbar record-filters"><el-input v-model="query" placeholder="搜索姓名、任务或记录" label="搜索姓名、任务或记录" aria-label="搜索姓名、任务或记录" prefix-icon="Search" clearable/><el-select v-model="filter" aria-label="事项状态"><template v-if="section==='tasks'"><el-option label="待办理" value="open"/><el-option label="未开始" value="scheduled"/><el-option label="已提交待审核" value="submitted"/><el-option v-if="isManager" label="待我审核" value="review"/></template><template v-else-if="section==='dorms'"><el-option label="空床" value="available"/><el-option label="入住" value="occupied"/><el-option label="预留" value="reserved"/><el-option label="维修" value="maintenance"/></template><el-option v-else-if="section!=='archive'" :label="['care','party'].includes(section)?'待跟进':'待整改'" value="current"/><el-option label="全部记录" value="all"/><el-option v-if="section!=='dorms'" label="历史记录" value="history"/><el-option v-if="!['archive','dorms','hygiene'].includes(section)" :label="['care','party'].includes(section)?'已到跟进时间':'逾期未完成'" value="overdue"/></el-select><el-button v-if="hasFilters" @click="clearFilters">清空筛选</el-button><el-button class="record-refresh" :icon="Refresh" title="刷新" aria-label="刷新记录" :loading="loading" @click="load">刷新</el-button><el-button class="desktop-record-tool" :disabled="!!loadError||loading||outputBusy||!outputRows.length" :icon="Download" title="导出筛选结果" aria-label="导出筛选结果" @click="download">导出</el-button><el-button class="desktop-record-tool" :disabled="!!loadError||loading||outputBusy||!outputRows.length" :icon="Printer" title="打印筛选结果" aria-label="打印筛选结果" @click="print">打印</el-button><el-dropdown class="mobile-record-tools" trigger="click"><el-button :disabled="!!loadError||loading||outputBusy||!outputRows.length" aria-label="更多列表操作">更多</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="download" @click="download()">导出筛选结果</el-dropdown-item><el-dropdown-item command="print" @click="print">打印筛选结果</el-dropdown-item></el-dropdown-menu></template></el-dropdown><el-button v-if="isManager && !['dorms','tasks'].includes(section)" type="primary" :icon="Plus" @click="open('create')">{{section==='tasks'?'安排工作任务':section==='archive'?'归档已办结事项':section==='care'?'记录谈话或跟进':'新增记录'}}</el-button></div>
  <div v-if="loadError" class="records-unavailable" role="alert"><el-icon><Warning /></el-icon><h3>暂时无法读取{{labels[section]}}</h3><p>{{loadError}}</p><el-button type="primary" :loading="loading" @click="load">重新读取记录</el-button></div>
  <template v-if="!loadError">
  <div v-if="section==='tasks'&&reviewableRows.length" class="toolbar review-toolbar"><el-checkbox :model-value="allReviewsSelected" :indeterminate="reviewSelection.length>0&&!allReviewsSelected" :disabled="saving" @change="selectAllReviews(Boolean($event))">全选当前待审核（{{reviewableRows.length}} 项）</el-checkbox><el-button :disabled="!reviewSelection.length" :loading="saving" @click="bulkReview('approve')">审核通过选中 {{reviewSelection.length}} 项</el-button><el-button :disabled="!reviewSelection.length" :loading="saving" @click="bulkReview('return')">退回并填写批注</el-button></div>
  <el-alert v-if="error && !dialog" :title="error" type="error" :closable="false"/>
  <template v-if="section==='tasks'"><div class="metrics"><span>筛选结果 {{taskTotals.total}} 项</span><span>已完成 {{taskTotals.done}}</span><span>待审核 {{taskTotals.review}}</span><span :class="{danger:taskTotals.overdue>0}">逾期未完成 {{taskTotals.overdue}}</span></div><details class="task-charts"><summary>查看筛选结果统计图</summary><div class="charts"><div class="chart" aria-label="任务状态分布"><h3>任务状态</h3><div v-for="bar in chart" :key="bar.label"><span>{{bar.label}}</span><el-progress :percentage="Math.round(bar.count/Math.max(visible.length,1)*100)" :format="()=>String(bar.count)+' 项'"/></div></div><div v-if="taskScope!=='received'" class="chart" aria-label="班级完成率"><h3>班级完成率</h3><div v-for="bar in classChart" :key="bar.label"><span>{{bar.label}}</span><el-progress :percentage="Math.round(bar.done/bar.total*100)" :format="()=>bar.done+'/'+bar.total"/></div></div></div></details></template>
  <WorkImport v-if="section==='tasks'&&isManager&&workImportOpen" module="tasks" @close="workImportOpen=false" @imported="imported" /><template v-if="section==='dorms'"><div class="toolbar"><el-button :icon="Plus" @click="open('bed')">新增床位</el-button><el-button @click="open('allocation')">一键分配</el-button><el-button @click="open('housing')">入住 / 调宿 / 退宿</el-button><el-button @click="open('hygiene')">卫生检查</el-button><el-button :icon="Upload" v-if="['counselor','leader','logistics'].includes(user.role)" @click="workImportOpen=true">导入床位</el-button></div><WorkImport v-if="workImportOpen" module="beds" @close="workImportOpen=false" @imported="imported" /><p class="output-scope">当前显示 {{ filteredBeds.length }} 个床位，顶部导出和打印使用这些床位。住宿变动、卫生记录请使用对应列表旁的按钮。</p><el-table :data="filteredBeds" empty-text="没有符合条件的床位"><el-table-column prop="title" label="床位" min-width="180"/><el-table-column label="状态"><template #default="{row}">{{states[row.status]}}</template></el-table-column><el-table-column label="入住 / 预留人员"><template #default="{row}">{{people.find(p=>p.id===(row.studentId||row.reservedFor))?.name||'—'}}</template></el-table-column><el-table-column prop="conditions" label="住宿条件"/></el-table><h3>住宿人员变动</h3><div class="toolbar"><el-button :disabled="outputBusy||loading||!!loadError||!housing.length" @click="output(housing,'housing')">导出住宿变动</el-button><el-button :disabled="outputBusy||loading||!!loadError||!housing.length" @click="output(housing,'housing',true)">打印住宿变动</el-button></div><el-table :data="housing"><el-table-column prop="title" label="办理事项"/><el-table-column label="学生"><template #default="{row}">{{people.find(p=>p.id===row.studentId)?.name||row.studentId}}</template></el-table-column><el-table-column label="状态"><template #default="{row}">{{states[row.status]}}</template></el-table-column><el-table-column label="操作" min-width="160"><template #default="{row}"><el-button v-if="!ended(row)" text @click="open('housing-action',row)">办理</el-button></template></el-table-column></el-table><h3>卫生统计</h3><div class="toolbar"><el-button :disabled="outputBusy||loading||!!loadError||!rows.length" @click="output(rows,'hygiene')">导出卫生记录</el-button><el-button :disabled="outputBusy||loading||!!loadError||!rows.length" @click="output(rows,'hygiene',true)">打印卫生记录</el-button></div><div class="metrics"><span v-for="r in roomStats" :key="r.room">{{r.room}} · {{r.count}} 次 · 均分 {{(r.score/r.count).toFixed(1)}}</span></div></template>
  <p v-if="section==='tasks'&&visible.length" class="list-order">优先显示逾期与临期任务 · 共 {{visible.length}} 项</p>
  <ul v-if="section==='tasks'" class="mobile-task-list">
   <li v-for="row in visible" :key="row.id" :class="{'task-card-overdue':isOverdue(row),'task-card-review':canReview(row)}">
    <div class="task-card-top"><span class="task-person">{{row.assigneeId===user.id?'交给我的任务':row.assigneeName}}</span><el-tag :type="isOverdue(row)?'danger':row.status==='completed'?'success':row.status==='submitted'?'warning':'info'" size="small">{{taskStatus(row)}}{{isOverdue(row)?' · 逾期':''}}</el-tag></div>
    <h3>{{row.title}}</h3><p class="task-requirement" v-if="row.requiresFiles">需提交文件材料</p>
    <el-progress :percentage="Number(row.progress)||0" :stroke-width="5"/><p class="task-deadline"><el-icon><Clock /></el-icon>截止 {{time(row.dueAt)}}</p>
    <p v-if="taskNextStep(row)" class="task-hint">{{taskNextStep(row)}}</p>
    <div class="mobile-task-actions"><el-checkbox v-if="canReview(row)" :model-value="reviewSelection.some(r=>r.id===row.id)" @change="selectReview(row,Boolean($event))">选中审核</el-checkbox><el-button text @click="open('detail',row)">查看详情</el-button><el-button :icon="Share" title="校内分享" aria-label="校内分享" text @click="open('share',row)"/><el-button v-if="actionsFor(row).length" type="primary" @click="open('action',row)">{{canReview(row)?'审核任务':row.assigneeId===user.id&&row.status!=='submitted'&&!isScheduled(row)?'办理任务':'管理任务'}}</el-button></div>
   </li>
   <li v-if="!visible.length" class="mobile-task-empty"><span>{{emptyHint}}</span><el-button v-if="hasFilters&&scopedRows.length" text type="primary" @click="clearFilters">清空筛选</el-button></li>
  </ul>
  <el-table ref="taskTable" :class="{'desktop-task-table':section==='tasks'}" :data="visible" empty-text="暂无符合条件的记录" row-key="id" @selection-change="reviewSelection=$event">
   <template #empty><span>{{emptyHint}}</span><el-button v-if="hasFilters&&scopedRows.length" text type="primary" @click="clearFilters">清空筛选</el-button></template>
   <el-table-column v-if="section==='tasks'&&visible.some(canReview)" type="selection" :selectable="canReview" width="45"/>
   <el-table-column prop="title" label="事项" min-width="240"><template #default="{row}"><strong class="record-title">{{row.title}}</strong><small v-if="row.requiresFiles" class="task-requirement">需提交文件材料</small></template></el-table-column>
   <el-table-column v-if="section==='tasks'" prop="assigneeName" label="接收人" min-width="120"/>
   <el-table-column v-if="['care','party'].includes(section)" prop="studentName" label="学生"/>
   <el-table-column v-if="section==='party'" label="发展阶段" min-width="130"><template #default="{row}">{{row.track==='league'?'入团':'入党'}} · {{stages[row.track]?.[row.stage]}}</template></el-table-column>
   <el-table-column v-if="section==='tasks'" label="进度" min-width="130"><template #default="{row}"><el-progress :percentage="Number(row.progress)||0"/></template></el-table-column>
   <el-table-column v-if="section==='dorms'" prop="room" label="宿舍" min-width="160"/><el-table-column v-if="section==='dorms'" prop="score" label="卫生分"/>
   <el-table-column label="状态" min-width="135"><template #default="{row}"><el-tag :type="isOverdue(row)?'danger':row.status==='completed'?'success':row.status==='submitted'?'warning':'info'">{{section==='tasks'?taskStatus(row):states[row.status]||row.status}}{{isOverdue(row)?' · 逾期':''}}</el-tag></template></el-table-column>
   <el-table-column :label="dateColumnLabel" min-width="165"><template #default="{row}">{{time(row.dueAt||row.checkedAt||row.archivedAt)}}</template></el-table-column>
   <el-table-column label="操作" min-width="200" class-name="no-print"><template #default="{row}"><el-button text @click="open('detail',row)">查看详情</el-button><el-button v-if="!ended(row)&&(section!=='tasks'||actionsFor(row).length)" text type="primary" @click="open('action',row)">{{section==='tasks'?canReview(row)?'审核任务':row.assigneeId===user.id&&row.status!=='submitted'&&!isScheduled(row)?'办理任务':'管理任务':section==='care'?'记录跟进':'办理'}}</el-button><el-button v-if="section!=='care'" :icon="Share" title="分享给有权查看的同事" aria-label="校内分享" text @click="open('share',row)"/></template></el-table-column>
  </el-table>
  </template>
  <div v-if="section==='tasks'" class="task-report-section"><div><h3>统计与汇报</h3><p>办完工作后，在这里整理成果。</p></div><CollegeReports :records="loadError?[]:visible" :user="user"/></div>
  <el-dialog class="college-dialog" v-model="dialogOpen" :before-close="requestClose" :close-on-press-escape="!saving && !uploadBusy" :show-close="!saving && !uploadBusy" :title="dialog==='detail'?'查看事项':dialog==='allocation'?'批量分配床位':dialog==='share'?'校内分享':dialog==='create'?section==='tasks'?'安排工作任务':section==='archive'?'归档已办结事项':section==='care'?'记录谈话或跟进':'新增记录':section==='tasks'?'办理工作任务':'办理事项'" width="min(94vw, 720px)" :close-on-click-modal="false" @closed="error=''">
   <template v-if="dialog==='detail'"><h3>{{selected?.title}}</h3><p>{{selected?.content}}</p><template v-if="section==='tasks'&&selected"><p>{{taskStatus(selected)}} · 截止 {{time(selected.dueAt)}}</p><p v-if="taskNextStep(selected)" class="task-hint">{{taskNextStep(selected)}}</p><p v-if="returnReason" class="danger">退回要求：{{returnReason}}</p></template><p v-if="selected?.progressNote">进度说明：{{selected.progressNote}}</p><p v-if="selected?.basis">核验依据：{{selected.basis}}</p><p v-if="selected?.snapshot">归档来源：{{labels[selected.sourceModule]}} · {{selected.snapshot.title}}</p><p v-for="h in selected?.history" :key="h.time+h.action">{{time(h.time)}} · {{h.operator}} · {{actionNames[h.action]||'办理记录'}} · {{h.reason}}</p><MaterialUpload :model-value="selected?.attachmentIds||[]" readonly/></template>
   <el-form v-else label-position="top">
    <div v-if="dialog==='action'&&selected" class="action-summary"><strong>{{selected.title}}</strong><p>{{selected.content}}</p><p v-if="section==='tasks'">{{taskStatus(selected)}} · 截止 {{time(selected.dueAt)}}</p><p v-if="section==='tasks'&&isScheduled(selected)" class="task-hint">{{taskNextStep(selected)}}</p><p v-if="returnReason" class="danger">退回要求：{{returnReason}}</p><MaterialUpload v-if="section==='tasks'&&['approve','return'].includes(form.action)" :model-value="selected.attachmentIds||[]" readonly/></div>
    <p v-if="dialog==='create'&&section==='tasks'" class="form-intro">写清要求、选择接收人和截止时间，即可发布。</p>
    <template v-if="dialog==='create'"><el-form-item label="标题" required><el-input v-model="form.title" placeholder="例如：本周班级学生情况汇总" :maxlength="120"/></el-form-item><el-form-item label="任务要求 / 内容" :required="section==='tasks'"><el-input v-model="form.content" type="textarea" :rows="3"/></el-form-item>
     <template v-if="section==='tasks'"><el-form-item label="接收人" required><el-select v-model="form.assigneeIds" multiple filterable><el-option v-for="p in people" :key="p.id" :value="p.id" :label="p.name+' · '+p.className"/></el-select></el-form-item><el-form-item label="开始时间"><el-date-picker v-model="form.startsAt" type="datetime" placeholder="留空则立即开始" value-format="YYYY-MM-DDTHH:mm:ssZ"/><small class="field-help">不需要定时发布时，无需填写。</small></el-form-item></template>
     <template v-if="['party','care'].includes(section)"><el-form-item label="学生" required><el-select v-model="form.studentId" filterable><el-option v-for="p in students" :key="p.id" :value="p.id" :label="p.name+' · '+p.className"/></el-select></el-form-item><el-form-item v-if="section==='party'" label="发展类型"><el-radio-group v-model="form.track"><el-radio-button label="league">入团</el-radio-button><el-radio-button label="party">入党</el-radio-button></el-radio-group></el-form-item><template v-else><el-form-item label="记录类型"><el-radio-group v-model="form.type"><el-radio-button label="talk">师生谈话</el-radio-button><el-radio-button label="followup">重点关怀</el-radio-button></el-radio-group></el-form-item><el-form-item label="关注级别"><el-select v-model="form.level"><el-option v-for="v in ['一般','加强','紧急']" :key="v" :value="v"/></el-select></el-form-item></template></template>
     <template v-if="section==='archive'"><el-form-item label="归档内容来自"><el-select v-model="form.sourceModule"><el-option v-for="v in ['tasks','party','care','hygiene']" :key="v" :label="labels[v]" :value="v"/></el-select></el-form-item><el-form-item label="选择已办结事项" required><el-select v-model="form.sourceId" filterable :loading="archiveLoading" placeholder="按标题或姓名搜索" @change="chooseArchive"><el-option v-for="r in archiveOptions" :key="r.id" :value="r.id" :label="r.title+' · '+(r.studentName||r.assigneeName||states[r.status])"/></el-select></el-form-item><el-empty v-if="!archiveLoading&&!archiveOptions.length" description="该类暂无可归档事项" :image-size="50"/></template>
    </template>
    <template v-if="dialog==='bed'"><el-form-item v-for="[key,label] in [['campus','校区'],['building','楼栋'],['room','房间'],['bed','床号'],['conditions','住宿条件（如女生、男生、无障碍）'],['source','来源依据']]" :key="key" :label="label" required><el-input v-model="form[key]"/></el-form-item></template>
    <template v-if="dialog==='allocation'"><el-form-item label="学生"><el-select v-model="form.studentIds" multiple filterable @change="allocationChanged"><el-option v-for="p in students" :key="p.id" :value="p.id" :label="p.name"/></el-select></el-form-item><el-form-item label="备选空床"><el-select v-model="form.bedIds" multiple filterable @change="allocationChanged"><el-option v-for="p in beds.filter(b=>b.status==='available')" :key="p.id" :value="p.id" :label="p.title"/></el-select></el-form-item><el-form-item label="住宿条件"><el-input v-model="form.conditions" @input="allocationChanged"/></el-form-item><el-form-item label="预留截止"><el-date-picker v-model="form.reservedUntil" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ"/></el-form-item><el-checkbox v-model="form.conditionsVerified">已人工核验性别、无障碍及住宿条件</el-checkbox><el-table v-if="preview.length" :data="preview"><el-table-column prop="studentName" label="学生"/><el-table-column prop="bedTitle" label="拟分配床位"/></el-table></template>
    <template v-if="dialog==='housing'"><el-form-item label="学生"><el-select v-model="form.studentId" filterable><el-option v-for="p in students" :key="p.id" :value="p.id" :label="p.name"/></el-select></el-form-item><el-form-item label="办理类型"><el-select v-model="form.type"><el-option label="入住" value="checkin"/><el-option label="调宿" value="move"/><el-option label="退宿" value="checkout"/></el-select></el-form-item><el-form-item label="办理说明"><el-input v-model="form.content" type="textarea"/></el-form-item></template>
    <template v-if="dialog==='hygiene'"><el-form-item label="检查宿舍"><el-select v-model="form.bedId" filterable><el-option v-for="p in beds" :key="p.id" :value="p.id" :label="p.title"/></el-select></el-form-item><el-form-item label="检查标题"><el-input v-model="form.title"/></el-form-item><el-form-item label="评分"><el-input-number v-model="form.score" :min="0" :max="100"/></el-form-item><el-form-item label="检查时间"><el-date-picker v-model="form.checkedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ"/></el-form-item><el-form-item label="检查情况"><el-input v-model="form.content" type="textarea"/></el-form-item></template>
    <template v-if="dialog==='action' || dialog==='housing-action'">
     <el-form-item label="办理操作"><el-radio-group v-if="section==='tasks'" v-model="form.action" class="task-action-choices"><el-radio-button v-for="a in taskActions" :key="a.value" :label="a.value">{{a.label}}</el-radio-button></el-radio-group><el-select v-else v-model="form.action"><template v-if="section==='party'"><el-option label="核验后进入下一阶段" value="advance"/><el-option label="结束本次跟进" value="close"/></template><template v-else-if="section==='care'"><el-option label="记录跟进并设置下次提醒" value="followup"/><el-option label="结束本次跟进" value="close"/></template><template v-else-if="dialog==='housing-action'"><el-option label="批准 / 安排床位" value="approve"/><el-option label="确认实际交接" value="confirm_handover"/><el-option label="取消" value="cancel"/></template><el-option v-else label="确认卫生整改完成" value="rectify"/></el-select></el-form-item>
     <p v-if="section==='tasks'&&taskActionHint" class="task-hint">{{taskActionHint}}</p>
     <p v-if="section==='care'" class="task-hint">{{form.action==='close'?'结束后停止这条记录的跟进提醒，可在历史记录中查看。':'请记录本次情况，并重新选择下次跟进时间；无需继续跟进时，可选择结束本次跟进。'}}</p>
     <el-form-item :label="section==='care'?(form.action==='close'?'结束跟进的情况说明':'本次谈话或跟进记录'):form.action==='return'?'退回原因与修改要求':form.action==='extend'?'延长截止时间的原因':form.action==='approve'?'审核意见':form.action==='submit'?'完成情况':form.action==='progress'?'目前完成了哪些工作':'办理说明 / 实际核验情况'" required><el-input v-model="form.reason" type="textarea" :rows="3"/></el-form-item>
     <el-form-item v-if="section==='tasks'&&form.action==='progress'" label="完成进度（%）"><el-slider v-model="form.progress" :min="0" :max="100" show-input/></el-form-item>
     <template v-if="dialog==='housing-action'"><el-form-item label="目标空床（退宿不需要）"><el-select v-model="form.targetBedId"><el-option v-for="p in beds.filter(b=>b.status==='available')" :key="p.id" :value="p.id" :label="p.title"/></el-select></el-form-item><el-form-item label="预留截止时间"><el-date-picker v-model="form.reservedUntil" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ"/></el-form-item><el-form-item label="交接凭据编号"><el-input v-model="form.handoverReference"/></el-form-item><el-checkbox v-model="form.actualHandover">已完成实际搬迁或退宿交接</el-checkbox></template><el-checkbox v-if="section==='party'&&form.action==='advance'" v-model="form.verified">已核验该阶段规定程序和材料</el-checkbox>
    </template>
    <el-form-item v-if="(dialog==='create'&&section!=='archive')||(dialog==='action'&&['extend','advance','followup'].includes(form.action))" :label="section==='tasks'?form.action==='extend'?'新的截止时间':'任务截止时间':'下次跟进时间'" required><el-date-picker v-model="form.dueAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ"/><div v-if="dialog==='create'&&section==='tasks'" class="date-presets"><el-button size="small" @click="setDueInDays(1)">1 天后</el-button><el-button size="small" @click="setDueInDays(3)">3 天后</el-button><el-button size="small" @click="setDueInDays(7)">1 周后</el-button></div></el-form-item><div v-if="dialog==='create'&&section==='tasks'" class="task-delivery-options"><h4>提交要求</h4><el-checkbox v-model="form.requiresFiles">必须提交文件材料</el-checkbox><el-checkbox v-model="form.needsReview">提交后需要发布人验收</el-checkbox><p>{{form.needsReview?'接收人提交后，由发布人审核，审核通过才算完成。':'接收人提交后直接完成，无需另行审核。'}}</p></div><el-form-item v-if="section==='party'&&dialog!=='share'&&form.action!=='close'" label="制度与材料核验依据"><el-input v-model="form.basis" type="textarea"/></el-form-item>
    <el-form-item v-if="dialog==='share'" label="接收人（需已有查看权限）"><el-select v-model="form.recipientIds" multiple filterable><el-option v-for="p in people" :key="p.id" :value="p.id" :label="p.name"/></el-select></el-form-item>
    <el-form-item v-if="(dialog==='create'&&!['tasks','archive'].includes(section))||(dialog==='action'&&form.action==='submit')" :label="dialog==='action'&&selected?.requiresFiles?'提交材料（必填）':'附件材料'"><MaterialUpload v-model="form.attachmentIds" :disabled="saving" @busy="uploadBusy=$event"/></el-form-item>
   </el-form>
   <el-alert v-if="error" :title="error" type="error" :closable="false"/>
   <template #footer><el-button :disabled="saving || uploadBusy" @click="requestClose()">关闭</el-button><el-button v-if="dialog==='detail' && section==='tasks' && selected && actionsFor(selected).length" type="primary" @click="open('action',selected)">{{canReview(selected)?'审核任务':selected.assigneeId===user.id && selected.status!=='submitted' && !isScheduled(selected)?'办理任务':'管理任务'}}</el-button><el-button v-if="dialog!=='detail'" type="primary" :loading="saving" :disabled="uploadBusy" @click="save">{{saveLabel}}</el-button></template>
  </el-dialog>
 </section>
</template>
<style scoped src="./college-work.css"></style>
