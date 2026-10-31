<script setup lang="ts">
import { computed, getCurrentInstance, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { onHide, onShow } from '@dcloudio/uni-app'
import { api, download, getToken } from '../platform'
import MobileMaterials from './MobileMaterials.vue'
import MobileChatExpressions from './MobileChatExpressions.vue'
import { chatSticker, chatMessageText, chatMessagePreview } from '../../../shared/chat-expressions'
type Contact = { id: string; name: string; role?: string; className?: string }
type Message = { id: string; sequence: number; senderId: string; senderName?: string; content: string; stickerId?: string; attachmentIds?: string[]; createdAt?: string }
type Conversation = { id: string; peer: Contact; unreadCount: number; lastMessage?: Message; lastSequence?: number; readSequence?: number; canSend?: boolean }
type MessagePage = { items: Message[]; hasMore: boolean; nextBefore?: number }
const props = withDefaults(defineProps<{ user: any; refreshKey?: number; active?: boolean }>(), { refreshKey: 0, active: true })
const emit = defineEmits<{ (event: 'unread-change', count: number): void; (event: 'busy-change', value: boolean): void }>()
const contacts = ref<Contact[]>([]), conversations = ref<Conversation[]>([]), messages = ref<Message[]>([])
const selectedId = ref(''), selected = computed(() => conversations.value.find(item => item.id === selectedId.value))
const showContacts = ref(false), contactSearch = ref(''), draft = ref(''), attachmentIds = ref<string[]>([])
const showAttachments = ref(false), showExpressions = ref(false), stickerId = ref('')
const uploading = ref(false), sending = ref(false), busy = computed(() => uploading.value || sending.value)
const savedDraftCount = ref(0), navigationBusy = computed(() => busy.value || !!draft.value || !!stickerId.value || attachmentIds.value.length > 0 || savedDraftCount.value > 0)
const opening = ref(false), loading = ref(false), loadingMessages = ref(false), historyLoading = ref(false), hasMore = ref(false)
const nextBefore = ref<number>(), error = ref(''), downloadingId = ref(''), fileNames = ref<Record<string, string>>({})
const filteredContacts = computed(() => contacts.value.filter(contact => `${contact.name} ${contact.className || ''}`.toLowerCase().includes(contactSearch.value.trim().toLowerCase())))
const instance = getCurrentInstance(), atBottom = ref(false), historyScrollTop = ref(0)
let historyTop = 0, historyHeight = 0, reading = false, lastReadSequence = 0
let timer: ReturnType<typeof setTimeout> | undefined
let mounted = false, foreground = true, generation = 0, selectionGeneration = 0, polling = false, initializedHistory = false
let pendingAttempt: { clientMessageId: string; content: string; stickerId: string; attachmentIds: string[]; conversationId: string } | null = null
const drafts = new Map<string, { text: string; stickerId: string; attachments: string[]; attempt: typeof pendingAttempt }>()
const valid = (epoch: number, token: string) => mounted && generation === epoch && !!token && getToken() === token && foreground && props.active
const current = (epoch: number, token: string, id: string, selection: number) => valid(epoch, token) && selectedId.value === id && selectionGeneration === selection
const messageError = (value: unknown) => value instanceof Error ? value.message : '操作失败，请稍后重试'
const roleLabel = (role?: string) => ({ admin: '管理员', counselor: '辅导员', student: '学生' } as Record<string, string>)[String(role).toLowerCase()] || '教职工'
function formatTime(value?: string) { if (!value) return ''; const date = new Date(value); return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) }
function mergeMessages(items: Message[]) { const merged = new Map(messages.value.map(message => [message.id, message])); for (const item of items) merged.set(item.id, item); messages.value = [...merged.values()].sort((a,b) => a.sequence - b.sequence) }
function rememberDraft() { if (!selectedId.value) return; if (draft.value || stickerId.value || attachmentIds.value.length) drafts.set(selectedId.value, { text: draft.value, stickerId: stickerId.value, attachments: [...attachmentIds.value], attempt: pendingAttempt }); else drafts.delete(selectedId.value); savedDraftCount.value = drafts.size }
function clearDraft() { if (busy.value) return; drafts.delete(selectedId.value); savedDraftCount.value = drafts.size; draft.value = ''; stickerId.value = ''; showExpressions.value = false; attachmentIds.value = []; pendingAttempt = null; error.value = '' }
function clearAllDrafts() { if (busy.value) return; drafts.clear(); savedDraftCount.value = 0; draft.value = ''; stickerId.value = ''; showExpressions.value = false; attachmentIds.value = []; pendingAttempt = null; error.value = '' }
function jumpToLatest() { historyScrollTop.value = 10000000 + Date.now() % 100000; measureHistory() }
function onHistoryScroll(event: any) { historyTop = Number(event.detail?.scrollTop || 0); const total = Number(event.detail?.scrollHeight || 0); atBottom.value = historyHeight > 0 && historyTop + historyHeight >= total - 8; if (atBottom.value) void markVisibleRead() }
function onHistoryBottom() { atBottom.value = true; void markVisibleRead() }
function measureHistory() {
  const epoch = generation, token = getToken(), id = selectedId.value, selection = selectionGeneration
  if (!current(epoch, token, id, selection)) return
  uni.createSelectorQuery().in(instance?.proxy).select('.chat-history').boundingClientRect().select('.chat-history-content').boundingClientRect().exec((rects: any[]) => {
    if (!current(epoch,token,id,selection) || !rects?.[0] || !rects?.[1]) return
    historyHeight = Number(rects[0].height || 0); atBottom.value = historyHeight > 0 && historyTop + historyHeight >= Number(rects[1].height || 0) - 8
    if (atBottom.value) void markVisibleRead()
  })
}
async function markVisibleRead() {
  if (loadingMessages.value || historyLoading.value || !atBottom.value || reading || !messages.value.length) return
  const epoch = generation, token = getToken(), id = selectedId.value, selection = selectionGeneration
  if (!current(epoch,token,id,selection)) return
  const through = Math.max(...messages.value.map(item => item.sequence)); if (through <= lastReadSequence) return
  reading = true
  try {
    await api(`/chat/conversations/${encodeURIComponent(id)}/read`, 'POST', { throughSequence: through })
    if (!current(epoch,token,id,selection)) return
    lastReadSequence = through
    const conversation = selected.value; if (conversation && Number(conversation.lastSequence || 0) <= through) conversation.unreadCount = 0
    emit('unread-change', conversations.value.reduce((sum,item) => sum + Number(item.unreadCount || 0), 0))
  } catch { /* Retry on the next visible poll. */ }
  finally { reading = false }
}
async function loadLatest() {
  const id = selectedId.value, epoch = generation, token = getToken(), selection = selectionGeneration
  if (!id || !valid(epoch,token) || historyLoading.value) return
  const firstPage = !initializedHistory, stickToBottom = atBottom.value, lastKnown = messages.value[messages.value.length - 1]?.sequence || 0
  loadingMessages.value = true
  try {
    let page = await api(`/chat/conversations/${encodeURIComponent(id)}/messages?limit=50`) as MessagePage
    if (!current(epoch,token,id,selection)) return
    const incoming = [...(page.items || [])]
    if (!initializedHistory) { hasMore.value = page.hasMore; nextBefore.value = page.nextBefore; initializedHistory = true }
    else while (page.hasMore && page.nextBefore && page.items[0]?.sequence > lastKnown && lastKnown > 0) {
      page = await api(`/chat/conversations/${encodeURIComponent(id)}/messages?limit=50&beforeSequence=${page.nextBefore}`) as MessagePage
      if (!current(epoch,token,id,selection)) return
      incoming.push(...page.items)
    }
    mergeMessages(incoming)
    void loadFileNames(incoming, epoch, token, id, selection)
    await nextTick(); if (current(epoch,token,id,selection)) { loadingMessages.value = false; if (firstPage || stickToBottom) jumpToLatest(); measureHistory() }
  } catch (reason) { if (current(epoch,token,id,selection)) error.value = messageError(reason) }
  finally { if (selectedId.value === id && selectionGeneration === selection) loadingMessages.value = false }
}
async function loadFileNames(items: Message[], epoch: number, token: string, id: string, selection: number) {
  const ids = [...new Set(items.flatMap(message => message.attachmentIds || []))].filter(fileId => !fileNames.value[fileId])
  await Promise.allSettled(ids.map(async fileId => { const metadata = await api(`/files/${encodeURIComponent(fileId)}`) as { name?: string }; if (current(epoch,token,id,selection)) fileNames.value[fileId] = metadata.name || '附件' }))
}
async function refresh() {
  if (polling || !mounted || !foreground || !props.active || !getToken()) return
  polling = true; loading.value = true; const epoch = generation, token = getToken()
  try {
    const [people,list] = await Promise.all([api('/chat/contacts'),api('/chat/conversations')])
    if (!valid(epoch,token)) return
    contacts.value = people as Contact[]; conversations.value = list as Conversation[]
    emit('unread-change', conversations.value.reduce((sum,item) => sum + Number(item.unreadCount || 0), 0))
    if (selectedId.value && !conversations.value.some(item => item.id === selectedId.value)) { rememberDraft(); selectedId.value = ''; selectionGeneration++; messages.value = []; draft.value = ''; stickerId.value = ''; showExpressions.value = false; attachmentIds.value = []; uploading.value = false; error.value = '此会话已不可访问。' }
    else if (selectedId.value && !loadingMessages.value) await loadLatest()
  } catch (reason) { if (valid(epoch,token)) error.value = messageError(reason) }
  finally { polling = false; loading.value = false }
}
function schedule() { if (timer) clearTimeout(timer); if (!mounted || !foreground || !props.active) return; timer = setTimeout(async () => { await refresh(); schedule() },5000) }
async function selectConversation(conversation: Conversation) {
  if (busy.value) return
  rememberDraft(); selectedId.value = conversation.id; selectionGeneration++; messages.value = []; initializedHistory = false; hasMore.value = false; nextBefore.value = undefined; historyLoading.value = false; error.value = ''
  atBottom.value = false; historyScrollTop.value = 0; historyTop = 0; historyHeight = 0; lastReadSequence = Number(conversation.readSequence || 0); showAttachments.value = false
  const saved = drafts.get(conversation.id); showExpressions.value = false; stickerId.value = saved?.stickerId || ''; draft.value = saved?.text || ''; attachmentIds.value = [...(saved?.attachments || [])]; pendingAttempt = saved?.attempt || null
  await loadLatest()
}
function backToConversations() { if (busy.value) return; rememberDraft(); selectedId.value = ''; selectionGeneration++; messages.value = []; draft.value = ''; stickerId.value = ''; showExpressions.value = false; attachmentIds.value = []; error.value = '' }
async function startConversation(contact: Contact) {
  if (opening.value || busy.value) return
  const epoch = generation, token = getToken(); opening.value = true; error.value = ''
  try { const conversation = await api('/chat/conversations','POST',{ recipientId: contact.id }) as Conversation; if (!valid(epoch,token)) return; if (!conversations.value.some(item => item.id === conversation.id)) conversations.value.unshift(conversation); showContacts.value = false; await selectConversation(conversation) }
  catch (reason) { if (valid(epoch,token)) error.value = messageError(reason) }
  finally { opening.value = false }
}
async function loadOlder() {
  if (historyLoading.value || !hasMore.value || !nextBefore.value) return
  const id = selectedId.value, epoch = generation, token = getToken(), selection = selectionGeneration; historyLoading.value = true
  try { const page = await api(`/chat/conversations/${encodeURIComponent(id)}/messages?limit=50&beforeSequence=${nextBefore.value}`) as MessagePage; if (!current(epoch,token,id,selection)) return; mergeMessages(page.items || []); hasMore.value = page.hasMore; nextBefore.value = page.nextBefore; atBottom.value = false; void loadFileNames(page.items,epoch,token,id,selection); await nextTick(); measureHistory() }
  catch (reason) { if (current(epoch,token,id,selection)) error.value = messageError(reason) }
  finally { if (selectedId.value === id && selectionGeneration === selection) historyLoading.value = false }
}
function selectSticker(id: string) { if (sending.value || !chatSticker(id)) return; stickerId.value = id; showExpressions.value = false }
function insertEmoji(symbol: string) { if (sending.value) return; if ((draft.value + symbol).length > 2000) { error.value = '消息长度已达上限，请删减文字后再添加表情。'; return }; draft.value += symbol; showExpressions.value = false }
function toggleExpressions() { showExpressions.value = !showExpressions.value; if (showExpressions.value) showAttachments.value = false }
function toggleAttachments() { showAttachments.value = !showAttachments.value; showExpressions.value = false }
async function sendMessage() {
  if (busy.value || !selectedId.value || selected.value?.canSend === false) return
  const content = draft.value.trim(), selectedSticker = stickerId.value, attachments = [...attachmentIds.value]
  if ((!content && !selectedSticker && !attachments.length) || content.length > 2000 || attachments.length > 10) return
  const id = selectedId.value, epoch = generation, token = getToken(), selection = selectionGeneration
  if (!valid(epoch,token)) return
  if (!pendingAttempt || pendingAttempt.conversationId !== id || pendingAttempt.content !== content || pendingAttempt.stickerId !== selectedSticker || JSON.stringify(pendingAttempt.attachmentIds) !== JSON.stringify(attachments)) pendingAttempt = { clientMessageId: `mobile-${Date.now()}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`, content, stickerId: selectedSticker, attachmentIds: attachments, conversationId: id }
  const attempt = pendingAttempt; sending.value = true; error.value = ''
  try {
    const message = await api(`/chat/conversations/${encodeURIComponent(id)}/messages`,'POST',{ clientMessageId: attempt.clientMessageId, content: attempt.content, stickerId: attempt.stickerId, attachmentIds: attempt.attachmentIds }) as Message
    if (!mounted || getToken() !== token || selectedId.value !== id || selectionGeneration !== selection) return
    mergeMessages([message]); void loadFileNames([message], epoch, token, id, selection); draft.value = ''; stickerId.value = ''; showExpressions.value = false; attachmentIds.value = []; pendingAttempt = null; drafts.delete(id); savedDraftCount.value = drafts.size; showAttachments.value = false
    await nextTick(); if (current(epoch,token,id,selection)) { jumpToLatest(); measureHistory() }
  } catch (reason) { if (mounted && getToken() === token && selectedId.value === id && selectionGeneration === selection) error.value = `发送未完成：${messageError(reason)}。内容已保留，可重试。` }
  finally { sending.value = false }
}
async function openFile(id: string) {
  if (downloadingId.value) return
  const epoch = generation, token = getToken(), conversationId = selectedId.value, selection = selectionGeneration; downloadingId.value = id; error.value = ''
  try {
    const meta = await api(`/files/${encodeURIComponent(id)}`) as { name?: string }; if (!current(epoch,token,conversationId,selection)) return
    fileNames.value[id] = meta.name || '附件'; const path = await download(id); if (!current(epoch,token,conversationId,selection)) return
    if (/\.(png|jpe?g|gif|webp|bmp)$/i.test(meta.name || '')) { await new Promise<void>((resolve,reject) => uni.previewImage({ urls: [path], success: () => resolve(), fail: reject })); return }
    // #ifdef H5
    const anchor = document.createElement('a'); anchor.href = path; anchor.download = meta.name || '附件'; anchor.rel = 'noopener'; document.body.appendChild(anchor); anchor.click(); anchor.remove()
    // #endif
    // #ifndef H5
    await new Promise<void>((resolve,reject) => uni.openDocument({ filePath: path, showMenu: true, success: () => resolve(), fail: reject }))
    // #endif
  } catch (reason) { if (current(epoch,token,conversationId,selection)) error.value = `文件打开失败：${messageError(reason)}` }
  finally { downloadingId.value = '' }
}
function pause() { foreground = false; generation++; if (timer) clearTimeout(timer) }
function resume() { foreground = true; if (mounted) { void refresh(); schedule() } }
function visibilityChanged() {
  // #ifdef H5
  if (document.hidden) pause(); else resume()
  // #endif
}
function beforeUnload(event: BeforeUnloadEvent) { if (navigationBusy.value) { event.preventDefault(); event.returnValue = '' } }
watch(navigationBusy,value => emit('busy-change',value),{ flush: 'sync' })
watch(() => props.refreshKey,() => { void refresh() })
watch(() => props.active,active => { generation++; if (active) resume(); else pause() })
watch(() => props.user?.id,() => { generation++; selectionGeneration++; drafts.clear(); savedDraftCount.value = 0; pendingAttempt = null; selectedId.value = ''; messages.value = []; contacts.value = []; conversations.value = []; draft.value = ''; stickerId.value = ''; showExpressions.value = false; attachmentIds.value = []; fileNames.value = {}; error.value = ''; emit('unread-change',0); void refresh() })
onShow(resume); onHide(pause)
onMounted(() => {
  mounted = true
  // #ifdef H5
  foreground = !document.hidden; document.addEventListener('visibilitychange',visibilityChanged); window.addEventListener('beforeunload',beforeUnload)
  // #endif
  void refresh(); schedule()
})
onUnmounted(() => {
  mounted = false; generation++; if (timer) clearTimeout(timer)
  // #ifdef H5
  document.removeEventListener('visibilitychange',visibilityChanged); window.removeEventListener('beforeunload',beforeUnload)
  // #endif
  emit('busy-change',false)
})
</script>

<template>
  <view class="mobile-chat" data-testid="mobile-chat">
    <view class="chat-intro" v-if="!selectedId"><text class="chat-title">内部聊天</text></view>
    <view v-if="error" class="chat-error" role="alert">{{ error }}<button size="mini" @click="refresh">重试</button></view>
    <template v-if="!selectedId">
      <view class="chat-toolbar"><text>会话 · {{ conversations.length }}</text><button size="mini" :disabled="busy" @click="showContacts = !showContacts">{{ showContacts ? '收起联系人' : '发起聊天' }}</button></view>
      <view v-if="showContacts" class="chat-contacts"><input v-model="contactSearch" placeholder="搜索联系人姓名或班级" class="chat-search" /><text v-if="!filteredContacts.length" class="chat-empty">暂无可联系人员</text><button v-for="contact in filteredContacts" :key="contact.id" class="chat-contact" :data-testid="'mobile-chat-contact-' + contact.id" :disabled="busy || opening" @click="startConversation(contact)"><text>{{ contact.name }}</text><text class="chat-muted">{{ contact.className || roleLabel(contact.role) }}</text></button></view>
      <text v-if="loading && !conversations.length" class="chat-empty">正在加载会话...</text><text v-else-if="!conversations.length" class="chat-empty">暂无会话</text>
      <button v-for="conversation in conversations" :key="conversation.id" class="chat-conversation" :data-testid="'mobile-chat-conversation-' + conversation.id" :disabled="busy" @click="selectConversation(conversation)"><view class="chat-row"><text class="chat-peer">{{ conversation.peer?.name || '联系人' }}</text><text v-if="conversation.unreadCount" class="chat-unread">{{ conversation.unreadCount > 99 ? '99+' : conversation.unreadCount }}</text></view><text class="chat-preview">{{ chatMessagePreview(conversation.lastMessage) }}</text></button>
      <button v-if="savedDraftCount" size="mini" :disabled="busy" @click="clearAllDrafts">清空全部草稿（{{ savedDraftCount }}）</button><button v-if="error" size="mini" :disabled="loading" @click="refresh">重新加载</button>
    </template>
    <template v-else>
      <view class="chat-toolbar"><button size="mini" :disabled="busy" @click="backToConversations">返回会话</button><text class="chat-peer">{{ selected?.peer?.name || '联系人' }}</text></view>
      <scroll-view class="chat-history" data-testid="mobile-chat-history" scroll-y :scroll-top="historyScrollTop" @scroll="onHistoryScroll" @scrolltolower="onHistoryBottom">
        <view class="chat-history-content"><button v-if="hasMore" size="mini" :disabled="historyLoading" @click="loadOlder">{{ historyLoading ? '加载中...' : '加载更早消息' }}</button><text v-if="loadingMessages && !messages.length" class="chat-empty">正在加载消息...</text><text v-else-if="!messages.length" class="chat-empty">暂无消息</text>
          <view v-for="message in messages" :key="message.id" :class="['chat-message', { mine: String(message.senderId) === String(user?.id) }]" :data-testid="'mobile-chat-message-' + message.id"><text class="chat-message-meta">{{ String(message.senderId) === String(user?.id) ? '我' : selected?.peer?.name || message.senderName || '联系人' }} · {{ formatTime(message.createdAt) }}</text><image v-if="chatSticker(message.stickerId)" class="chat-sticker-image" :src="chatSticker(message.stickerId)?.src" :aria-label="'表情包：' + chatSticker(message.stickerId)?.label" mode="aspectFit" /><text v-if="chatMessageText(message)" class="chat-bubble">{{ chatMessageText(message) }}</text><button v-for="fileId in message.attachmentIds || []" :key="fileId" class="chat-file" :disabled="downloadingId === String(fileId)" @click="openFile(String(fileId))">{{ downloadingId === String(fileId) ? '正在打开...' : '打开文件：' + (fileNames[String(fileId)] || '附件') }}</button></view>
        </view>
      </scroll-view>
      <button v-if="messages.length && !atBottom" size="mini" @click="jumpToLatest">最新消息</button>
      <view v-if="selected?.canSend === false" class="chat-muted">当前联系人已不可联系。</view>
      <view v-else class="chat-composer"><view v-if="showExpressions" class="chat-expression-popover"><MobileChatExpressions :selected="stickerId" :disabled="sending" @sticker="selectSticker" @emoji="insertEmoji" @close="showExpressions = false" /></view><view v-if="chatSticker(stickerId)" class="chat-sticker-draft" data-testid="mobile-chat-sticker-draft"><image :src="chatSticker(stickerId)?.src" mode="aspectFit" /><text>待发送：{{chatSticker(stickerId)?.label}}</text><button size="mini" :disabled="sending" @click="stickerId = ''">移除</button></view><textarea v-model="draft" placeholder="输入消息" data-testid="mobile-chat-composer" :maxlength="2000" :disabled="sending" class="chat-textarea" auto-height /><view class="chat-counter"><text>{{ draft.length }}/2000</text><text>最多 10 个附件</text></view><view class="chat-compose-tools"><button size="mini" data-testid="mobile-chat-expression-toggle" :disabled="sending || loadingMessages" @click="toggleExpressions">☺ 表情</button><button class="chat-file-toggle" size="mini" data-testid="mobile-chat-file-toggle" :disabled="sending" @click="toggleAttachments">{{ showAttachments ? '收起附件' : '添加附件' }}<text v-if="attachmentIds.length">（{{ attachmentIds.length }}）</text></button></view><view v-if="showAttachments" :class="{ 'chat-upload-locked': sending }"><MobileMaterials :key="selectedId" v-model="attachmentIds" @busy="uploading = $event" /></view><view class="chat-send-row"><button v-if="draft || stickerId || attachmentIds.length" size="mini" :disabled="busy" @click="clearDraft">清空草稿</button><button type="primary" data-testid="mobile-chat-send" :disabled="busy || (!draft.trim() && !stickerId && !attachmentIds.length) || attachmentIds.length > 10" @click="sendMessage">{{ sending ? '发送中...' : '发送' }}</button></view><text v-if="uploading" class="chat-muted">附件处理中</text></view>
    </template>
  </view>
</template>

<style scoped>
.mobile-chat { padding: 14px 0; color: #24324a; }
.chat-intro { margin-bottom: 16px; }.chat-title { font-size: 20px; font-weight: 700; }.chat-muted { color: #69768b; font-size: 12px; line-height: 1.6; }
.chat-toolbar,.chat-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; }.chat-toolbar { margin-bottom: 12px; }.chat-toolbar button { margin: 0; }.chat-peer { font-size: 15px; font-weight: 600; overflow-wrap: anywhere; }
.chat-error { padding: 10px; margin-bottom: 12px; background: #fff1f0; color: #a32920; font-size: 13px; overflow-wrap: anywhere; }.chat-empty { display: block; padding: 24px 10px; text-align: center; color: #69768b; font-size: 13px; }
.chat-conversation { display: block; text-align: left; background: white; padding: 13px; border-radius: 8px; margin: 0 0 8px; line-height: 1.5; }.chat-preview { display: block; color: #69768b; font-size: 13px; margin-top: 5px; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }.chat-unread { background: #c43c41; color: white; border-radius: 20px; padding: 1px 7px; font-size: 12px; }
.chat-contacts { margin-bottom: 12px; }.chat-search { border: 1px solid #d3dce8; border-radius: 7px; padding: 8px; font-size: 14px; margin-bottom: 10px; }.chat-contact { display: flex; align-items: center; justify-content: space-between; gap: 10px; text-align: left; margin: 5px 0; font-size: 14px; line-height: 2.6; background: #f4f7fc; }
.chat-history { height: 42vh; min-height: 120px; }.chat-history-content { padding: 8px 1px; }.chat-message { display: flex; flex-direction: column; align-items: flex-start; margin: 12px 0; gap: 5px; }.chat-message.mine { align-items: flex-end; }.chat-message-meta { font-size: 11px; color: #69768b; }.chat-bubble { padding: 9px 12px; background: white; border: 1px solid #e1e6ec; border-radius: 8px; max-width: 88%; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 14px; line-height: 1.6; }.mine .chat-bubble { background: #dceafd; }.chat-file { margin: 0; max-width: 90%; font-size: 12px; padding: 6px 10px; color: #205b98; background: #edf4fc; line-height: 1.6; overflow-wrap: anywhere; }
.chat-sticker-image{width:168px;height:168px;flex-shrink:0}.chat-sticker-draft{display:flex;align-items:center;gap:8px;border:1px solid #dce5f1;border-radius:10px;padding:6px;background:#f4f7fc;font-size:12px;margin-bottom:8px}.chat-sticker-draft image{width:56px;height:56px}.chat-sticker-draft button{margin:0 0 0 auto}.chat-compose-tools{display:flex;gap:8px;margin-bottom:10px}.chat-compose-tools button{margin:0;background:#edf3ff;color:#245ac0;line-height:36px}.chat-expression-popover{position:absolute;left:0;right:0;bottom:calc(100% + 8px);max-height:340px;overflow:auto;z-index:5;border-radius:12px}
.chat-composer { position:relative;border-top: 1px solid #dce3ed; margin-top: 12px; padding-top: 12px; }.chat-textarea { width: 100%; box-sizing: border-box; min-height: 80px; padding: 10px; background: white; border: 1px solid #d3dce8; border-radius: 8px; font-size: 14px; }.chat-counter { display: flex; justify-content: space-between; font-size: 11px; color: #69768b; margin: 6px 0; }.chat-upload-locked { pointer-events: none; opacity: .6; }.chat-send-row { display: flex; gap: 10px; align-items: center; }.chat-send-row button { margin: 0; }.chat-send-row button[type=primary] { flex: 1; }
</style>
