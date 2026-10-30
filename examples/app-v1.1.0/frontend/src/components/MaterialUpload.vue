<template>
  <section class="materials" aria-label="附件材料">
    <div v-if="!readonly" class="material-actions">
      <el-button :disabled="disabled || busy" @click="fileInput?.click()">{{imagesOnly?'选择图片':'选择文件'}}</el-button>
      <el-button :disabled="disabled || busy" @click="cameraInput?.click()">拍照上传</el-button>
      <el-button v-if="!imagesOnly" :disabled="disabled || busy" @click="videoInput?.click()">上传录屏</el-button>
      <el-button v-if="canRecord&&!imagesOnly" :type="recording ? 'danger' : 'default'" :disabled="disabled || (!recording && busy)" @click="recording ? stopRecording() : startRecording()">{{ recording ? '结束录屏' : '录制屏幕' }}</el-button>
      <input ref="fileInput" hidden type="file" multiple :accept="accept" @change="select($event, 'file')" />
      <input ref="cameraInput" hidden type="file" accept="image/jpeg,image/png" capture="environment" @change="select($event, 'camera')" />
      <input ref="videoInput" hidden type="file" accept="video/mp4,video/webm,video/quicktime,.mov" @change="select($event, 'screen')" />
    </div>
    <p v-if="!readonly" class="material-help">{{imagesOnly?'JPG / PNG 配图':'文档 20 MB · 照片 10 MB · 录屏 100 MB。支持 PDF、Word、Excel、JPG、PNG、MP4、WebM、MOV。手机可先使用系统录屏，再选择上传。'}}</p>
    <p v-if="recording" class="recording" role="status">正在录制你选择的屏幕，最长 5 分钟。请避免展示无关个人信息。</p>
    <div v-if="recorded" class="recorded-preview">
      <video :src="recordedUrl" controls playsinline aria-label="录屏预览"></video>
      <p>请检查画面后上传。录制默认不包含声音。</p>
      <el-button type="primary" :disabled="pending.length > 0" @click="uploadRecording">查看录屏信息并继续</el-button>
      <el-button @click="clearRecording">放弃</el-button>
    </div>
    <div v-for="item in staging" :key="item.key" class="media-staging">
      <div class="media-heading"><strong>{{ item.file.name }}</strong><el-button size="small" :disabled="item.processing" @click="removeStaged(item)">移除此文件</el-button></div>
      <img v-if="item.kind === 'image'" :src="item.url" class="preview-image" alt="上传前照片预览" />
      <video v-else :src="item.url" controls playsinline class="preview-video" :aria-label="`上传前录屏预览：${item.file.name}`"></video>
      <p class="material-help">{{ item.width }} × {{ item.height }} 像素<span v-if="item.kind === 'video'"> · {{ durationText(item.duration) }}</span> · {{ formatSize(item.file.size) }}</p>
      <template v-if="item.kind === 'image' && !item.error">
        <div class="photo-tools"><el-button size="small" :disabled="item.processing" @click="item.rotation = (item.rotation + 90) % 360; editImage(item)">顺时针旋转 90°</el-button><el-select v-model="item.maxEdge" size="small" @change="editImage(item)"><el-option :value="2048" label="办公清晰度：最长边 2048 像素" /><el-option :value="1280" label="节省流量：最长边 1280 像素" /><el-option :value="4096" label="高清资料：最长边 4096 像素" /></el-select><el-select v-model="item.quality" size="small" @change="editImage(item)"><el-option :value="0.9" label="画质 90%" /><el-option :value="0.8" label="画质 80%" /><el-option :value="0.65" label="画质 65%" /></el-select></div>
        <details class="crop-tools"><summary>裁剪无关区域（相对原图百分比）</summary><div><label>左侧起点<el-input-number v-model="item.cropX" :min="0" :max="99" size="small" /></label><label>顶部起点<el-input-number v-model="item.cropY" :min="0" :max="99" size="small" /></label><label>保留宽度<el-input-number v-model="item.cropW" :min="1" :max="100-item.cropX" size="small" /></label><label>保留高度<el-input-number v-model="item.cropH" :min="1" :max="100-item.cropY" size="small" /></label></div><el-button size="small" :loading="item.processing" @click="editImage(item)">应用裁剪并预览</el-button></details>
      </template>
      <p v-if="item.error" role="alert" class="media-error">{{ item.error }}</p>
      <p v-else class="material-help">请检查清晰度并确认已遮挡与本事项无关的信息。{{ item.kind === 'video' ? '录屏最多 5 分钟；请检查是否含不必要的人像、声音或个人信息。' : '图片重新生成后去除原始拍摄元数据，服务端仍会再次检查。' }}</p>
      <el-button type="primary" :loading="item.processing" :disabled="!!item.error || item.processing || disabled" @click="uploadStaged(item)">确认此预览并上传</el-button>
    </div>
    <ul class="material-list">
      <li v-for="id in modelValue" :key="id">
        <span class="file-info">{{ files[id]?.name || `附件 #${id}` }} <small v-if="files[id]">{{ formatSize(files[id].size) }}</small></span>
        <el-button size="small" :loading="opening === id" @click="openFile(id)">查看 / 下载</el-button>
        <el-button v-if="!readonly" size="small" :disabled="disabled" @click="remove(id)">移除</el-button>
      </li>
      <li v-for="item in pending" :key="item.key" class="pending-file">
        <div class="file-info"><span>{{ item.file.name }}</span><el-progress :percentage="item.progress" :status="item.error ? 'exception' : undefined" /><small v-if="item.error" role="alert">{{ item.error }}</small><small v-else-if="item.uploadedId">{{ item.processingStatus === 'processing' ? '正在完成安全检查与预览处理…' : '已上传，等待安全检查…' }}</small></div>
        <el-button v-if="!item.invalid && (item.error || item.paused)" size="small" :disabled="item.checking || ['queued','processing'].includes(item.processingStatus || '')" @click="retry(item)">{{ item.uploadedId ? '重新检查' : item.paused ? '继续上传' : '重试' }}</el-button>
        <el-button v-else-if="item.uploadId && !item.uploadedId" size="small" @click="pause(item)">暂停</el-button>
        <el-button size="small" @click="cancel(item)">{{ item.error ? '移除' : '取消' }}</el-button>
      </li>
    </ul>
    <p v-if="busy" role="status" class="material-help">附件尚未全部就绪。请完成预览确认、上传及安全检查，或主动移除未完成文件，再提交事项。</p>
    <p class="material-help" v-if="!readonly&&!imagesOnly">大文件按分片续传，重新选择同一原文件可续传 24 小时内未完成的分片。文件需通过安全检查后才能提交。照片与录屏仅用于当前业务，请遮挡无关隐私信息。</p>
    <el-dialog v-model="previewOpen" title="附件预览" width="min(90vw, 900px)" destroy-on-close @closed="clearPreview">
      <img v-if="previewType.startsWith('image/')" :src="previewUrl" class="preview-image" alt="上传材料预览" />
      <video v-else-if="previewType.startsWith('video/')" :src="previewUrl" controls playsinline class="preview-video"></video>
      <p v-else>文件已准备就绪，可下载后查看。</p>
      <template #footer><a :href="previewUrl" :download="previewName" class="download-link">下载文件</a></template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { api, getToken } from '@/api';

interface Attachment { id: string; name: string; size: number; contentType: string; source: string; processingStatus?: string; processingMessage?: string }
interface Pending { key: number; file: File; source: string; progress: number; error: string; xhr?: XMLHttpRequest; uploadId?: string; uploadedId?:string; paused?:boolean; cancelled?:boolean; invalid?:boolean; checking?:boolean; processingStatus?:string }
interface StagedMedia { key:number; file:File; original:File; source:string; kind:'image'|'video'; url:string; width:number; height:number; duration:number; rotation:number; cropX:number; cropY:number; cropW:number; cropH:number; quality:number; maxEdge:number; error:string; processing:boolean }
const props = withDefaults(defineProps<{ modelValue: string[]; disabled?: boolean; readonly?: boolean; imagesOnly?:boolean }>(), { disabled: false, readonly: false, imagesOnly:false });
const emit = defineEmits<{ (e: 'update:modelValue', value: string[]): void; (e: 'busy', value: boolean): void }>();
const fileInput = ref<HTMLInputElement>(); const cameraInput = ref<HTMLInputElement>(); const videoInput = ref<HTMLInputElement>();
const files = ref<Record<string, Attachment>>({}); const pending = ref<Pending[]>([]);
const staging = ref<StagedMedia[]>([]);
const recording = ref(false); const recorded = ref<Blob>(); const recordedUrl = ref('');
const busy = computed(() => pending.value.length > 0 || staging.value.length > 0 || recording.value || !!recorded.value);
watch(busy, value => emit('busy', value));
const accept = computed(()=>props.imagesOnly?'.jpg,.jpeg,.png':'.pdf,.docx,.xlsx,.jpg,.jpeg,.png,.mp4,.webm,.mov');
let counter = 0;
function formatSize(size: number) { return size >= 1048576 ? `${(size / 1048576).toFixed(1)} MB` : `${Math.ceil(size / 1024)} KB`; }
watch(() => props.modelValue, async ids => {
  if (!ids.length) return;
  try {
    const list = await api('/files') as Attachment[];
    for (const file of list) files.value[file.id] = file;
  } catch { /* Explicit download requests surface access and connection errors. */ }
}, { immediate: true, deep: true });
function select(event: Event, source: string) {
  const input = event.target as HTMLInputElement;
  for (const file of Array.from(input.files || [])) enqueue(file, source);
  input.value = '';
}
function enqueue(file: File, source: string) {
  const ext = file.name.split('.').pop()?.toLowerCase() || '';
  const invalid=(message:string)=>pending.value.push({key:++counter,file,source,progress:0,error:message,invalid:true});
  if (!['pdf', 'docx', 'xlsx', 'jpg', 'jpeg', 'png', 'mp4', 'webm', 'mov'].includes(ext)) return invalid('该格式暂不支持，请移除此文件并选择列表中的格式。');
  const limit = ['mp4', 'webm', 'mov'].includes(ext) ? 100 : ['jpg', 'jpeg', 'png'].includes(ext) ? 10 : 20;
  if (!file.size || file.size > limit * 1048576) return invalid(`文件不能为空，且不能超过 ${limit} MB。请移除后重新选择。`);
  if (props.modelValue.length + pending.value.length + staging.value.length >= 10) return ElMessage.error('每个事项最多上传 10 个附件。');
  if(['jpg','jpeg','png','mp4','webm','mov'].includes(ext)){stageMedia(file,source,['jpg','jpeg','png'].includes(ext)?'image':'video');return;}
  sendFile(file,source);
}
function sendFile(file:File,source:string){
  const item: Pending = { key: ++counter, file, source, progress: 0, error: '' };
  pending.value.push(item);
  send(pending.value[pending.value.length - 1]);
}
function durationText(seconds:number){return Number.isFinite(seconds)&&seconds>0?`${Math.floor(seconds/60)} 分 ${Math.round(seconds%60)} 秒`:'时长待核验';}
function imageElement(file:Blob){return new Promise<HTMLImageElement>((resolve,reject)=>{const img=new Image();const url=URL.createObjectURL(file);img.onload=()=>{URL.revokeObjectURL(url);resolve(img)};img.onerror=()=>{URL.revokeObjectURL(url);reject(new Error('浏览器无法读取图片，请更换 JPG 或 PNG 图片。'))};img.src=url;});}
async function stageMedia(file:File,source:string,kind:'image'|'video',durationHint=0){
  const item:StagedMedia={key:++counter,file,original:file,source,kind,url:URL.createObjectURL(file),width:0,height:0,duration:0,rotation:0,cropX:0,cropY:0,cropW:100,cropH:100,quality:.9,maxEdge:2048,error:'',processing:true};staging.value.push(item);const entry=staging.value[staging.value.length-1];
  try{if(kind==='image'){const img=await imageElement(file);entry.width=img.naturalWidth;entry.height=img.naturalHeight;if(entry.width*entry.height>40000000)throw new Error('照片像素过高，请先在相册缩小到 4000 万像素以内。');await editImage(entry);}else{await new Promise<void>((resolve,reject)=>{const video=document.createElement('video');video.preload='metadata';const timeout=setTimeout(()=>{cleanup();reject(new Error('无法读取视频信息，请转为兼容的 MP4 后重新上传。'));},15000);const cleanup=()=>{clearTimeout(timeout);video.onloadedmetadata=null;video.onerror=null;video.removeAttribute('src');video.load();};video.onloadedmetadata=()=>{entry.width=video.videoWidth;entry.height=video.videoHeight;entry.duration=Number.isFinite(video.duration)?video.duration:durationHint;const valid=entry.duration>0&&entry.duration<=300&&video.videoWidth>0;cleanup();valid?resolve():reject(new Error('录屏须能读取有效时长且不超过 5 分钟，请裁剪或转换后再上传。'));};video.onerror=()=>{cleanup();reject(new Error('浏览器无法预览此视频，请改用 MP4 格式。'));};video.src=entry.url;});}}catch(e){entry.error=(e as Error).message;}finally{entry.processing=false;}
}
async function editImage(item:StagedMedia){
  if(item.processing&&item.width===0)return;item.processing=true;item.error='';
  try{const img=await imageElement(item.original);const sx=Math.floor(img.naturalWidth*item.cropX/100),sy=Math.floor(img.naturalHeight*item.cropY/100);const sw=Math.max(1,Math.floor(img.naturalWidth*Math.min(item.cropW,100-item.cropX)/100)),sh=Math.max(1,Math.floor(img.naturalHeight*Math.min(item.cropH,100-item.cropY)/100));const rotated=item.rotation%180!==0;const width=rotated?sh:sw,height=rotated?sw:sh,scale=Math.min(1,item.maxEdge/Math.max(width,height));const canvas=document.createElement('canvas');canvas.width=Math.max(1,Math.round(width*scale));canvas.height=Math.max(1,Math.round(height*scale));const ctx=canvas.getContext('2d');if(!ctx)throw new Error('当前浏览器无法编辑照片，请使用相册处理后上传。');ctx.fillStyle='#fff';ctx.fillRect(0,0,canvas.width,canvas.height);ctx.translate(canvas.width/2,canvas.height/2);ctx.rotate(item.rotation*Math.PI/180);ctx.drawImage(img,sx,sy,sw,sh,-sw*scale/2,-sh*scale/2,sw*scale,sh*scale);const blob=await new Promise<Blob>((resolve,reject)=>canvas.toBlob(b=>b?resolve(b):reject(new Error('照片处理失败，请重试。')),'image/jpeg',item.quality));const file=new File([blob],item.original.name.replace(/\.[^.]+$/,'')+'-已处理.jpg',{type:'image/jpeg'});URL.revokeObjectURL(item.url);item.url=URL.createObjectURL(file);item.file=file;item.width=canvas.width;item.height=canvas.height;}catch(e){item.error=(e as Error).message;}finally{item.processing=false;}
}
function removeStaged(item:StagedMedia){URL.revokeObjectURL(item.url);staging.value=staging.value.filter(p=>p.key!==item.key);}
function uploadStaged(item:StagedMedia){if(item.error||item.processing)return;sendFile(item.file,item.source);removeStaged(item);}
function complete(item:Pending,file:Attachment) {
  if(item.cancelled)return;
  if (file.processingStatus !== 'ready') { item.uploadedId=file.id;item.processingStatus=file.processingStatus;item.error=['queued','processing'].includes(file.processingStatus||'')?'':file.processingMessage || '文件正在隔离检查，稍后重新检查。';return; }
  files.value[file.id]=file; emit('update:modelValue',[...props.modelValue,file.id]);pending.value=pending.value.filter(p=>p.key!==item.key);
}
async function send(item:Pending) {
  item.error='';item.paused=false;
  try {
    if(item.uploadedId){if(item.checking)return;item.checking=true;try{const checked=await api(`/files/${item.uploadedId}/recheck`,{method:'POST'});complete(item,checked);}finally{item.checking=false;}return;}
    if(!crypto.subtle){sendWhole(item);return;}
    if(!item.uploadId){const digest=await crypto.subtle.digest('SHA-256',await item.file.arrayBuffer());const sha256=Array.from(new Uint8Array(digest),n=>n.toString(16).padStart(2,'0')).join('');const created=await api('/uploads',{method:'POST',body:{name:item.file.name,size:item.file.size,sha256,source:item.source}});item.uploadId=created.id;}
    const state=await api(`/uploads/${item.uploadId}`);const received=new Set<string>(state.received);const size=state.chunkBytes;
    for(let index=0;index<state.chunkCount;index++){
      if(item.paused||item.cancelled)return;
      if(!received.has(String(index)))await sendChunk(item,index,item.file.slice(index*size,Math.min(item.file.size,(index+1)*size)),index*size);
      item.progress=Math.min(99,Math.round(Math.min(item.file.size,(index+1)*size)/item.file.size*100));
    }
    if(item.paused||item.cancelled)return;
    complete(item,await api(`/uploads/${item.uploadId}/complete`,{method:'POST'}));
  }catch(error){if(!item.paused&&!item.cancelled)item.error=error instanceof Error?error.message:'上传未完成，请重试';}
}
function sendChunk(item:Pending,index:number,chunk:Blob,offset:number){return new Promise<void>((resolve,reject)=>{
  const xhr=new XMLHttpRequest();item.xhr=xhr;xhr.open('PUT',`/api/uploads/${item.uploadId}/chunks/${index}`);xhr.timeout=120000;xhr.setRequestHeader('Authorization',`Bearer ${getToken()}`);xhr.setRequestHeader('Content-Type','application/octet-stream');
  xhr.upload.onprogress=e=>{if(e.lengthComputable)item.progress=Math.min(99,Math.round((offset+e.loaded)/item.file.size*100));};
  xhr.onload=()=>{try{const result=JSON.parse(xhr.responseText);if(xhr.status>=200&&xhr.status<300&&result.code===0)resolve();else reject(new Error(result.message||'分片未保存'));}catch{reject(new Error('分片结果未确认，请继续上传'));}};
  xhr.onerror=()=>reject(new Error('网络中断，已完成分片会保留'));xhr.ontimeout=()=>reject(new Error('分片超时，可以继续上传'));xhr.onabort=()=>reject(new Error('上传已暂停'));xhr.send(chunk);
});}
function sendWhole(item: Pending) {
  item.error = ''; item.progress = 0;
  const xhr = new XMLHttpRequest(); item.xhr = xhr;
  xhr.open('POST', '/api/files'); xhr.timeout = 300000;
  xhr.setRequestHeader('Authorization', `Bearer ${getToken()}`);
  const data = new FormData(); data.append('file', item.file); data.append('source', item.source);
  xhr.upload.onprogress = event => { if (event.lengthComputable) item.progress = Math.min(99, Math.round(event.loaded / event.total * 100)); };
  xhr.onload = () => {
    try {
      const result = JSON.parse(xhr.responseText);
      if (xhr.status < 200 || xhr.status >= 300 || result.code !== 0 || !result.data?.id) throw new Error(result.message || result.msg || '附件未保存，请重试。');
      complete(item,result.data);
    } catch (error) { item.error = error instanceof Error ? error.message : '上传失败'; }
  };
  xhr.onerror = () => { item.error = '网络中断，附件尚未确认保存，请重试。'; };
  xhr.ontimeout = () => { item.error = '上传超时，请检查网络后重试。'; };
  xhr.send(data);
}
function retry(item: Pending) { send(item); }
function pause(item:Pending){item.paused=true;item.xhr?.abort();}
async function cancel(item: Pending) { item.cancelled=true;item.xhr?.abort();try{if(item.uploadId)await api(`/uploads/${item.uploadId}/cancel`,{method:'POST'});}catch(error){ElMessage.warning((error as Error).message);}pending.value = pending.value.filter(p => p.key !== item.key); }
function remove(id: string) { emit('update:modelValue', props.modelValue.filter(item => item !== id)); }

const previewOpen = ref(false); const previewUrl = ref(''); const previewType = ref(''); const previewName = ref(''); const opening = ref<string>();
let downloadController: AbortController | undefined;
function clearPreview() { if (previewUrl.value) URL.revokeObjectURL(previewUrl.value); previewUrl.value = ''; }
async function openFile(id: string) {
  downloadController?.abort();
  const controller = new AbortController(); downloadController = controller;
  const identity = getToken();
  opening.value = id;
  try {
    const response = await fetch(`/api/files/${encodeURIComponent(id)}/download`, { headers: { Authorization: `Bearer ${identity}` }, signal: controller.signal });
    if (!response.ok) throw new Error(response.status === 403 ? '当前账号没有访问此附件的权限。' : '附件暂时无法下载，请重新登录或稍后重试。');
    const blob = await response.blob();
    if (!mounted || controller !== downloadController || identity !== getToken()) return;
    clearPreview();
    previewType.value = blob.type; previewName.value = files.value[id]?.name || `附件-${id}`;
    previewUrl.value = URL.createObjectURL(blob); previewOpen.value = true;
  } catch (error) { if (mounted && !controller.signal.aborted && identity === getToken()) ElMessage.error((error as Error).message); }
  finally { if (controller === downloadController) { opening.value = undefined; downloadController = undefined; } }
}

const canRecord = Boolean(navigator.mediaDevices && 'getDisplayMedia' in navigator.mediaDevices && typeof MediaRecorder !== 'undefined');
let recorder: MediaRecorder | undefined; let stream: MediaStream | undefined; let timer: ReturnType<typeof setTimeout> | undefined;
let recordStartedAt=0,recordedSeconds=0;
let mounted = true;
const processingPoll=setInterval(async()=>{await Promise.allSettled(pending.value.filter(item=>item.uploadedId&&!item.cancelled&&!item.checking&&['queued','processing'].includes(item.processingStatus||'')).map(async item=>{item.checking=true;try{const checked=await api<Attachment>(`/files/${item.uploadedId}`);if(mounted&&!item.cancelled)complete(item,checked);}catch(e){if(mounted&&!item.cancelled)item.error=(e as Error).message;}finally{item.checking=false;}}));},3000);
async function startRecording() {
  try {
    clearRecording();
    stream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false });
    if (!mounted) { stream.getTracks().forEach(t => t.stop()); return; }
    const mime = ['video/webm;codecs=vp9', 'video/webm;codecs=vp8', 'video/webm', 'video/mp4'].find(t => MediaRecorder.isTypeSupported(t));
    if (!mime) throw new Error('浏览器不支持录屏编码，请使用系统录屏后上传。');
    const chunks: Blob[] = []; let size = 0;
    recorder = new MediaRecorder(stream, { mimeType: mime, videoBitsPerSecond: 1500000 });
    recorder.ondataavailable = event => { if (event.data.size) { chunks.push(event.data); size += event.data.size; if (size >= 95 * 1048576) stopRecording(); } };
    recorder.onstop = () => {
      recordedSeconds=(Date.now()-recordStartedAt)/1000;
      recording.value = false; clearTimeout(timer); stream?.getTracks().forEach(t => t.stop());
      if (!mounted) return;
      recorded.value = new Blob(chunks, { type: mime }); recordedUrl.value = URL.createObjectURL(recorded.value);
    };
    stream.getVideoTracks()[0].onended = stopRecording;
    recorder.start(1000);recordStartedAt=Date.now();recording.value = true;timer = setTimeout(stopRecording, 299000);
  } catch (error) {
    stream?.getTracks().forEach(t => t.stop());
    if ((error as Error).name !== 'NotAllowedError') ElMessage.error('无法开始录屏，可使用系统录屏后上传文件。');
  }
}
function stopRecording() { if (recorder && recorder.state !== 'inactive') recorder.stop(); }
function clearRecording() { if (recordedUrl.value) URL.revokeObjectURL(recordedUrl.value); recordedUrl.value = ''; recorded.value = undefined; }
function uploadRecording() {
  if (!recorded.value) return;
  const ext = recorded.value.type.includes('mp4') ? 'mp4' : 'webm';
  stageMedia(new File([recorded.value], `录屏-${new Date().toISOString().replace(/[:.]/g, '-')}.${ext}`, { type: recorded.value.type }), 'screen','video',recordedSeconds);
  clearRecording();
}
onBeforeUnmount(() => { mounted = false;downloadController?.abort();clearInterval(processingPoll);pending.value.forEach(p => p.xhr?.abort()); staging.value.forEach(p=>URL.revokeObjectURL(p.url));stopRecording(); stream?.getTracks().forEach(t => t.stop()); clearTimeout(timer); clearRecording(); clearPreview(); });
defineExpose({ busy });
</script>

<style scoped>
.materials { width: 100%; }
.material-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.material-actions .el-button { margin: 0; }
.material-help, .file-info small { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.7; margin-top: 8px; }
.material-list { list-style: none; padding: 0; margin: 8px 0; }
.material-list li { display: flex; align-items: center; gap: 8px; padding: 10px; border: 1px solid var(--el-border-color-light); border-radius: 8px; margin-bottom: 6px; }
.file-info { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.file-info small { display: block; }
.recording { color: var(--el-color-danger); }
.preview-image, .preview-video, .recorded-preview video { display: block; max-width: 100%; max-height: 60vh; margin: auto; }
.recorded-preview { padding: 12px; border: 1px solid var(--el-border-color); border-radius: 8px; margin-top: 12px; }
.download-link { color: var(--el-color-primary); }
.media-staging{border:1px solid var(--el-border-color);border-radius:10px;padding:14px;margin:12px 0;background:var(--el-fill-color-extra-light)}.media-heading{display:flex;justify-content:space-between;gap:12px;align-items:center;margin-bottom:12px;overflow-wrap:anywhere}.media-heading strong{font-size:13px}.photo-tools{display:flex;flex-wrap:wrap;gap:8px;margin:12px 0}.photo-tools .el-select{min-width:150px;flex:1}.crop-tools{font-size:12px;line-height:1.8;margin:10px 0}.crop-tools>div{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin:10px 0}.crop-tools label{display:flex;flex-direction:column;gap:4px}.crop-tools :deep(.el-input-number){width:100%}.media-error{color:var(--el-color-danger);font-size:13px;line-height:1.8}
@media (max-width: 600px) { .material-list li { flex-wrap: wrap; } .file-info { flex-basis: 100%; } }
</style>
