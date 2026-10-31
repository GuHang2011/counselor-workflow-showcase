<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue';
import { api, getToken } from '../api';
import { ElMessageBox } from 'element-plus';
import MaterialUpload from './MaterialUpload.vue';
const props=defineProps<{records:Record<string,any>[];user:Record<string,any>}>();
const tab=ref('statistics'),opened=ref(false),busy=ref(false),error=ref(''),stats=ref<any>(null),report=ref<any>(null),news=ref<any>(null);
const statisticsLoading=ref(false),statisticsError=ref('');
let statisticsRequest=0;
const confirming=ref(false),reportScope=ref('');
const currentScope=computed(()=>props.records.map(record=>`${record.id}:${record.version||0}:${record.status}:${record.progress}`).sort().join('|'));
const reportOutdated=computed(()=>!!report.value&&reportScope.value!==currentScope.value);
async function confirmRegenerate(kind:string){
 if(!(kind==='report'?report.value:news.value))return true;
 confirming.value=true;
 try{await ElMessageBox.confirm('重新生成会替换当前文字，手动修改也会被覆盖。建议先下载留存。','重新生成并替换？',{confirmButtonText:'替换并重新生成',cancelButtonText:'保留当前内容',type:'warning'});return true;}
 catch{return false;}finally{confirming.value=false;}
}

onBeforeUnmount(()=>{++statisticsRequest;});
const title=ref('任务工作汇报'),useAi=ref(false),facts=ref(''),pictures=ref<string[]>([]),includeImages=ref(false);
const imageUrls=ref<string[]>([]),layout=ref('stacked'),uploadBusy=ref(false);
function clearImages(){imageUrls.value.forEach(URL.revokeObjectURL);imageUrls.value=[];}
watch(news,async(value,_,cleanup)=>{let cancelled=false;cleanup(()=>{cancelled=true;});clearImages();if(!value)return;for(const id of (value.attachmentIds||[])){try{const response=await fetch(`/api/files/${encodeURIComponent(id)}/download`,{headers:{Authorization:`Bearer ${getToken()}`}});if(!response.ok)throw new Error('配图加载失败');const url=URL.createObjectURL(await response.blob());if(cancelled)URL.revokeObjectURL(url);else imageUrls.value.push(url);}catch(e:any){if(!cancelled)error.value=e.message;}}});onBeforeUnmount(clearImages);
async function loadStatistics(){
 if(statisticsLoading.value)return;
 const request=++statisticsRequest;stats.value=null;statisticsError.value='';statisticsLoading.value=true;
 try{const result=await api('/college-content/statistics');if(request===statisticsRequest)stats.value=result;}
 catch(e:any){if(request===statisticsRequest)statisticsError.value=e.message||'任务统计暂未读取';}
 finally{if(request===statisticsRequest)statisticsLoading.value=false;}
}
function open(view='statistics'){tab.value=view;opened.value=true;error.value='';if(view==='statistics')loadStatistics();}
async function generate(kind:string){if(busy.value||uploadBusy.value||confirming.value)return;error.value='';if(kind==='report'&&!title.value.trim()){error.value='请填写汇报标题';return;}if(kind==='report'&&!props.records.length){error.value='当前没有可汇总的任务，请调整任务筛选后再生成';return;}if(!await confirmRegenerate(kind))return;busy.value=true;const scope=currentScope.value;try{if(kind==='report'){report.value=await api('/college-content/report',{method:'POST',body:{recordIds:props.records.map(r=>r.id),title:title.value.trim(),useAi:useAi.value}});reportScope.value=scope;}else news.value=await api('/college-content/news',{method:'POST',body:{facts:facts.value,attachmentIds:pictures.value,includeImagesInAi:includeImages.value}});}catch(e:any){error.value=e.message;}finally{busy.value=false;}}
function textDownload(content:string,name:string){const url=URL.createObjectURL(new Blob([content],{type:'text/plain;charset=utf-8'}));const link=document.createElement('a');link.href=url;link.download=name+'.txt';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
async function ppt(){if(!report.value)return;busy.value=true;try{const {default:pptxgen}=await import('pptxgenjs');const deck=new pptxgen();deck.layout='LAYOUT_WIDE';deck.author=props.user.name;deck.subject='任务汇报';const cover=deck.addSlide();cover.background={color:'F3F5F7'};cover.addText(report.value.title,{x:0.6,y:1.8,w:12,h:1,fontSize:30,color:'234E42',fontFace:'Microsoft YaHei'});cover.addText(new Date(report.value.generatedAt).toLocaleString('zh-CN'),{x:0.6,y:3,w:12,h:0.5,fontSize:16});for(const section of report.value.slides){const lines=section.lines.length?section.lines:['暂无事项'];for(let offset=0;offset<lines.length;offset+=8){const slide=deck.addSlide();slide.addText(section.title,{x:0.5,y:0.35,w:12,h:0.6,fontSize:24,color:'234E42'});slide.addText(lines.slice(offset,offset+8).join('\n\n'),{x:0.5,y:1.2,w:12,h:5.4,fontSize:16,breakLine:false,fit:'shrink',fontFace:'Microsoft YaHei'});}}await deck.writeFile({fileName:'任务综合汇报.pptx'});}catch(e:any){error.value=e.message;}finally{busy.value=false;}}
function fmt(value:string){return value?new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'}):'未设置';}
</script>
<template>
 <div class="report-tools"><el-button @click="open('statistics')">我的任务统计</el-button><el-button @click="open('report')">生成工作汇报</el-button><el-button v-if="['admin','counselor','mentor','leader','business','logistics'].includes(user.role)" @click="open('news')">撰写新闻</el-button></div>
 <el-dialog class="reports-dialog" v-model="opened" :show-close="!busy && !uploadBusy && !confirming" :close-on-press-escape="!busy && !uploadBusy && !confirming" :title="tab==='statistics'?'我的任务统计':tab==='report'?'生成工作汇报':'撰写新闻'" width="min(94vw, 900px)" :close-on-click-modal="false">
  <el-tabs v-model="tab" v-loading="busy" @tab-change="tab==='statistics' && loadStatistics()">
   <el-tab-pane label="我的任务统计" name="statistics">
    <p v-if="statisticsLoading" role="status">正在读取任务统计…</p>
    <div v-else-if="statisticsError"><el-alert :title="statisticsError" type="error" :closable="false"/><el-button @click="loadStatistics">重新读取统计</el-button></div>
    <template v-else-if="stats"><p>只统计我收到的任务；已取消任务不计入总数，待审核不计入已完成。</p><div class="statline"><span>收到任务 <strong>{{stats.total}}</strong></span><span>已完成 <strong>{{stats.completed}}</strong></span><span>已提交待审核 <strong>{{stats.submitted}}</strong></span><span :class="{'has-overdue':stats.overdue>0}">已逾期 <strong>{{stats.overdue}}</strong></span></div><el-progress :percentage="stats.completionRate"/><el-table :data="(stats.tasks||[]).filter((task:any)=>task.status!=='cancelled')" empty-text="你还没有收到有效任务"><el-table-column prop="title" label="任务"/><el-table-column prop="progress" label="进度 %"/><el-table-column label="截止时间（北京时间）" min-width="170"><template #default="{row}">{{fmt(row.dueAt)}}</template></el-table-column></el-table></template>
   </el-tab-pane>
   <el-tab-pane label="任务汇报" name="report"><el-alert v-if="reportOutdated" title="任务范围或进度已变化，下方保留的是上次汇报。需要最新结果时请重新生成。" type="warning" :closable="false" show-icon/><el-input v-model="title" label="汇报标题" aria-label="汇报标题" placeholder="填写汇报标题" maxlength="120"/><p>将汇总当前任务列表的 {{records.length}} 条任务。需要调整范围时，请关闭此窗口修改任务筛选。</p><el-alert v-if="!records.length" title="当前没有可汇总的任务，请先调整任务筛选。" type="info" :closable="false"/><el-checkbox v-model="useAi">使用 AI 优化文字</el-checkbox><el-button type="primary" :loading="busy" :disabled="!records.length||confirming" @click="generate('report')">生成汇报</el-button><template v-if="report"><p>{{report.mode==='external'?'AI生成':'根据任务记录整理'}} · {{fmt(report.generatedAt)}}<span v-if="report.warning"> · {{report.warning}}</span></p><label for="report-writing" class="editor-label">文字汇报（可修改后下载）</label><el-input id="report-writing" v-model="report.text" type="textarea" :rows="14" label="文字汇报" aria-label="文字汇报"/><p>文字可直接修改后下载；PPT 按生成时的任务提纲整理。</p><div class="actions"><el-button @click="textDownload(report.text,report.title)">下载文字总结</el-button><el-button :loading="busy" @click="ppt">下载综合 PPT</el-button></div></template></el-tab-pane>
   <el-tab-pane v-if="['admin','counselor','mentor','leader','business','logistics'].includes(user.role)" label="新闻初稿" name="news">
    <label for="news-facts" class="editor-label">活动事实</label><el-input id="news-facts" v-model="facts" type="textarea" :rows="5" placeholder="活动时间、地点、参与人员、具体过程和已核实成果" label="新闻事实" aria-label="新闻事实"/><MaterialUpload v-model="pictures" images-only :disabled="busy" @busy="uploadBusy=$event"/>
    <el-checkbox v-model="includeImages">允许 AI 识别配图</el-checkbox><el-button type="primary" :loading="busy" :disabled="!facts.trim()||uploadBusy||confirming" @click="generate('news')">{{uploadBusy?'配图上传中…':'生成新闻初稿'}}</el-button>
    <template v-if="news"><p>{{news.mode==='external'?'AI生成':'根据活动信息整理'}}<span v-if="news.warning"> · {{news.warning}}</span></p><el-input v-model="news.title" label="新闻标题" aria-label="新闻标题"/><label for="news-writing" class="editor-label">新闻正文（可修改后下载）</label><el-input id="news-writing" v-model="news.content" type="textarea" :rows="14" label="新闻正文" aria-label="新闻正文"/><el-radio-group v-model="layout" aria-label="配图排版"><el-radio-button label="stacked">单图通栏</el-radio-button><el-radio-button label="grid">双图并排</el-radio-button></el-radio-group><article><h2>{{news.title}}</h2><p v-for="(line,i) in (news.content||'').split('\n')" :key="i">{{line}}</p><div :class="['news-images',layout]"><img v-for="(url,i) in imageUrls" :key="url" :src="url" :alt="'活动配图 '+(i+1)"/></div></article><MaterialUpload :model-value="news.attachmentIds||[]" readonly/><el-button @click="textDownload(news.title+'\n\n'+news.content,news.title)">下载新闻文字</el-button><p>文字与配图请分别下载；排版效果可在上方预览。</p></template>
   </el-tab-pane>
  </el-tabs><el-alert v-if="error" :title="error" type="error" :closable="false"/>
 </el-dialog>
</template>
<style scoped>.report-tools{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:16px}.report-tools .el-button{margin:0}.statline,.actions{display:flex;gap:16px;flex-wrap:wrap;margin:16px 0}article{padding:20px 0;border-top:1px solid #ddd}article p{line-height:1.8;white-space:pre-wrap;overflow-wrap:anywhere}h2{font-size:22px}.el-input{margin-bottom:12px}.news-images{display:grid;gap:12px}.news-images.grid{grid-template-columns:repeat(2,minmax(0,1fr))}.news-images img{width:100%;height:auto;object-fit:contain}@media(max-width:500px){.news-images.grid{grid-template-columns:1fr}}
.editor-label{display:block;font-size:13px;font-weight:500;color:var(--ink);margin:16px 0 9px}
.statline{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}.statline>span{display:flex;flex-direction:column;gap:10px;padding:16px;background:#f4f7fb;border:1px solid var(--line);border-radius:12px;font-size:12px;color:var(--muted)}.statline strong{font-size:27px;font-weight:600;color:var(--ink);font-variant-numeric:tabular-nums}.statline .has-overdue strong{color:#b73547}
:deep(.el-tab-pane)>p{font-size:13px;color:var(--muted);line-height:1.8;margin:12px 0}:deep(.el-tab-pane)>.el-alert{margin-bottom:16px}:deep(.el-tabs__nav-wrap){margin-bottom:10px}
.report-tools>.el-button{min-height:40px}
@media(max-width:600px){.statline{grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.statline>span{padding:13px}.statline strong{font-size:24px}.actions .el-button{flex:1;margin:0}}
</style>
