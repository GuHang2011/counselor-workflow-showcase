<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { api, getToken } from '../api'
import MaterialUpload from './MaterialUpload.vue'
import ChatExpressions from './ChatExpressions.vue'
import ChatRecordOutput from './ChatRecordOutput.vue'
import { chatSticker, chatMessageText, chatMessagePreview } from '../../shared/chat-expressions'

type Contact = { id: string; name: string; role: string; className?: string; college?: string }
type Message = { id: string; conversationId: string; senderId: string; content: string; stickerId?: string; attachmentIds: string[]; createdAt: string; sequence: number }
type Conversation = { id: string; peer: Contact; unreadCount: number; lastMessage?: Message | null; lastSequence: number; peerReadSequence: number; readSequence: number; canSend: boolean }
type MessagePage = { items: Message[]; hasMore: boolean; nextBefore?: number; latestSequence: number }
type Draft = { stickerId: string; content: string; attachments: string[]; clientMessageId: string; signature: string; error: string }
const props = defineProps<{ user: { id: string | number; name?: string; role?: string }; refreshKey?: number | string }>()
const emit = defineEmits<{ (event: 'unread-change', count: number): void; (event: 'busy-change', busy: boolean): void }>()
const conversations = ref<Conversation[]>([]), contacts = ref<Contact[]>([]), messages = ref<Message[]>([])
const activeId = ref(''), drafts = reactive<Record<string, Draft>>({})
const search = ref(''), contactSearch = ref(''), contactDialog = ref(false)
const loading = ref(false), contactsLoading = ref(false), startingId = ref('')
const messageLoading = ref(false), historyLoading = ref(false), sending = ref(false), uploading = ref(false)
const showRecordOutput = ref(false)
const showAttachments = ref(false), hasMore = ref(false), nextBefore = ref<number>()
const listError = ref(''), contactError = ref(''), messageError = ref(''), notice = ref('')
const showExpressions = ref(false), composerEl = ref<HTMLTextAreaElement>(), expressionToggle = ref<HTMLButtonElement>()
const threadEl = ref<HTMLElement>(), composing = ref(false), atBottom = ref(true)
let pollTimer: ReturnType<typeof setInterval> | undefined
let polling = false, alive = true, authGeneration = 0, conversationGeneration = 0, readPending = false, currentToken = ''
const activeConversation = computed(() => conversations.value.find(item => item.id === activeId.value))
const draft = computed(() => activeId.value ? getDraft(activeId.value) : undefined)
const busy = computed(() => uploading.value || sending.value)
const hasDrafts = computed(() => Object.values(drafts).some(item => Boolean(item.content.trim() || item.stickerId || item.attachments.length)))
const navigationBusy = computed(() => busy.value || hasDrafts.value)
const myId = computed(() => String(props.user.id))
const filteredConversations = computed(() => conversations.value.filter(item => `${item.peer.name} ${item.peer.className || ''} ${item.peer.college || ''}`.toLowerCase().includes(search.value.trim().toLowerCase())))
const filteredContacts = computed(() => contacts.value.filter(item => `${item.name} ${item.className || ''} ${item.college || ''}`.toLowerCase().includes(contactSearch.value.trim().toLowerCase())))
const sendAllowed = computed(() => Boolean(activeConversation.value?.canSend && draft.value && (draft.value.content.trim() || draft.value.stickerId || draft.value.attachments.length) && draft.value.content.length <= 2000 && draft.value.attachments.length <= 10 && !busy.value && !messageLoading.value))
watch(navigationBusy, value => emit('busy-change', value), { flush: 'sync' })
watch(conversations, value => emit('unread-change', value.reduce((total, item) => total + Math.max(0, Number(item.unreadCount) || 0), 0)), { deep: true })
function getDraft(id: string): Draft {
  if (!drafts[id]) drafts[id] = { content: '', stickerId: '', attachments: [], clientMessageId: '', signature: '', error: '' }
  return drafts[id]
}
async function closeExpressions() { showExpressions.value = false; await nextTick(); expressionToggle.value?.focus() }
function selectSticker(id: string) { if (sending.value || !draft.value || !chatSticker(id)) return; draft.value.stickerId = id; showExpressions.value = false; notice.value = '' }
async function insertEmoji(symbol: string) {
  if (sending.value || !draft.value) return
  const input = composerEl.value, current = draft.value, start = input?.selectionStart ?? current.content.length, end = input?.selectionEnd ?? start
  const next = current.content.slice(0, start) + symbol + current.content.slice(end)
  if (next.length > 2000) { notice.value = '消息长度已达上限，请删减文字后再添加表情。'; return }
  current.content = next; notice.value = ''; showExpressions.value = false
  await nextTick(); input?.focus(); input?.setSelectionRange(start + symbol.length, start + symbol.length)
}
function toggleExpressions() { showExpressions.value = !showExpressions.value; if (showExpressions.value) showAttachments.value = false }
function toggleAttachments() { showAttachments.value = !showAttachments.value; showExpressions.value = false }
function discardDraft() { if (!busy.value && activeId.value) { Object.assign(getDraft(activeId.value), { content: '', stickerId: '', attachments: [], clientMessageId: '', signature: '', error: '' }); showAttachments.value = false; showExpressions.value = false } }
function discardAllDrafts() { if (!busy.value) { for (const id of Object.keys(drafts)) delete drafts[id]; showAttachments.value = false; showExpressions.value = false } }
function errorText(error: unknown, fallback: string) { return error instanceof Error && error.message ? error.message : fallback }
function requestGuard() { const generation = authGeneration, token = getToken(); return () => alive && generation === authGeneration && token === getToken() && token === currentToken }
function activeGuard(id: string) { const isCurrent = requestGuard(), generation = conversationGeneration; return () => isCurrent() && id === activeId.value && generation === conversationGeneration }
function roleText(role: string) { return ({ admin: '管理员', counselor: '辅导员', student: '学生' } as Record<string, string>)[role.toLowerCase()] || '教职工' }
function initials(name: string) { return name?.slice(-2) || '联系' }
function timeText(value?: string) { if (!value) return ''; const date = new Date(value); return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false }) }
function preview(conversation: Conversation) { const message = conversation.lastMessage; return message ? `${String(message.senderId) === myId.value ? '我：' : ''}${chatMessagePreview(message)}` : '暂无消息' }
function mergeMessages(items: Message[]) { const merged = new Map(messages.value.map(item => [item.id, item])); for (const item of items) merged.set(item.id, item); messages.value = [...merged.values()].sort((a, b) => a.sequence - b.sequence) }
function onScroll() { const element = threadEl.value; if (!element) return; atBottom.value = element.scrollHeight - element.scrollTop - element.clientHeight < 32; if (atBottom.value) void markRead() }
async function scrollToBottom() {
  const isCurrent = activeGuard(activeId.value)
  await nextTick()
  if (!isCurrent()) return
  if (threadEl.value) threadEl.value.scrollTop = threadEl.value.scrollHeight
  atBottom.value = true
}
async function refreshConversations() {
  const isCurrent = requestGuard()
  try {
    const result = await api<Conversation[]>('/chat/conversations')
    if (!isCurrent()) return
    conversations.value = result; listError.value = ''
    if (activeId.value && !result.some(item => item.id === activeId.value)) { conversationGeneration++; activeId.value = ''; messages.value = []; messageLoading.value = false; uploading.value = false; notice.value = '此会话已不可访问，请重新选择联系人。' }
  } catch (error) { if (isCurrent()) listError.value = errorText(error, '会话加载失败，请重试。') }
}
async function initialize() { loading.value = true; const isCurrent = requestGuard(); await refreshConversations(); if (isCurrent()) loading.value = false }
async function openContacts() {
  if (busy.value) return
  contactDialog.value = true; contactSearch.value = ''; contactError.value = ''; contactsLoading.value = true
  const isCurrent = requestGuard()
  try { const result = await api<Contact[]>('/chat/contacts'); if (isCurrent()) contacts.value = result }
  catch (error) { if (isCurrent()) contactError.value = errorText(error, '联系人加载失败，请重试。') }
  finally { if (isCurrent()) contactsLoading.value = false }
}
async function startConversation(contact: Contact) {
  if (startingId.value || busy.value) return
  startingId.value = contact.id; contactError.value = ''; const isCurrent = requestGuard()
  try {
    const conversation = await api<Conversation>('/chat/conversations', { method: 'POST', body: { recipientId: contact.id } })
    if (!isCurrent()) return
    if (!conversations.value.some(item => item.id === conversation.id)) conversations.value.unshift(conversation)
    else conversations.value = conversations.value.map(item => item.id === conversation.id ? conversation : item)
    contactDialog.value = false; await selectConversation(conversation.id)
  } catch (error) { if (isCurrent()) contactError.value = errorText(error, '会话创建失败，请重试。') }
  finally { if (isCurrent()) startingId.value = '' }
}
async function selectConversation(id: string) {
  if (id === activeId.value || busy.value) return
  showRecordOutput.value = false; conversationGeneration++; activeId.value = id; messages.value = []; messageError.value = ''; notice.value = ''; hasMore.value = false; nextBefore.value = undefined; historyLoading.value = false
  showExpressions.value = false; showAttachments.value = getDraft(id).attachments.length > 0; atBottom.value = true; await loadMessages(true)
}
function backToList() { if (!busy.value) { showRecordOutput.value = false; showExpressions.value = false; conversationGeneration++; activeId.value = ''; messages.value = []; notice.value = '' } }
async function loadMessages(initial = false) {
  const id = activeId.value; if (!id) return
  const isCurrent = activeGuard(id), wasAtBottom = atBottom.value, lastKnownSequence = messages.value[messages.value.length - 1]?.sequence || 0, replacePage = initial || messages.value.length === 0
  if (initial) messageLoading.value = true
  try {
    let page = await api<MessagePage>(`/chat/conversations/${encodeURIComponent(id)}/messages?limit=50`)
    if (!isCurrent()) return
    if (replacePage) { messages.value = page.items; hasMore.value = page.hasMore; nextBefore.value = page.nextBefore; messageLoading.value = false }
    else {
      const newItems = [...page.items]
      while (isCurrent() && page.hasMore && page.nextBefore && page.items[0]?.sequence > lastKnownSequence && lastKnownSequence > 0) {
        page = await api<MessagePage>(`/chat/conversations/${encodeURIComponent(id)}/messages?limit=50&beforeSequence=${page.nextBefore}`)
        if (!isCurrent()) return
        newItems.push(...page.items)
      }
      mergeMessages(newItems)
    }
    messageError.value = ''
    if (replacePage || (wasAtBottom && atBottom.value)) await scrollToBottom()
    if (isCurrent() && atBottom.value) await markRead()
  } catch (error) { if (isCurrent()) messageError.value = errorText(error, '消息加载失败，请重试。') }
  finally { if (isCurrent() && initial) messageLoading.value = false }
}
async function loadHistory() {
  if (!activeId.value || !hasMore.value || historyLoading.value) return
  const id = activeId.value, isCurrent = activeGuard(id), before = nextBefore.value ?? messages.value[0]?.sequence
  if (before === undefined) return
  historyLoading.value = true
  const previousHeight = threadEl.value?.scrollHeight || 0, previousTop = threadEl.value?.scrollTop || 0
  try {
    const page = await api<MessagePage>(`/chat/conversations/${encodeURIComponent(id)}/messages?limit=50&beforeSequence=${before}`)
    if (!isCurrent()) return
    mergeMessages(page.items); hasMore.value = page.hasMore; nextBefore.value = page.nextBefore; messageError.value = ''; atBottom.value = false
    await nextTick()
    if (isCurrent() && threadEl.value) threadEl.value.scrollTop = previousTop + threadEl.value.scrollHeight - previousHeight
  } catch (error) { if (isCurrent()) messageError.value = errorText(error, '历史消息加载失败，请重试。') }
  finally { if (isCurrent()) historyLoading.value = false }
}
async function markRead() {
  if (document.hidden || messageLoading.value || !activeId.value || !atBottom.value || readPending) return
  const id = activeId.value, throughSequence = messages.value[messages.value.length - 1]?.sequence || 0
  if (!throughSequence || throughSequence <= (activeConversation.value?.readSequence || 0)) return
  const isCurrent = activeGuard(id); readPending = true
  try {
    const result = await api<{ readSequence: number }>(`/chat/conversations/${encodeURIComponent(id)}/read`, { method: 'POST', body: { throughSequence } })
    if (!isCurrent()) return
    const conversation = activeConversation.value
    if (conversation) { conversation.readSequence = Math.max(conversation.readSequence || 0, result.readSequence); if (conversation.lastSequence <= conversation.readSequence) conversation.unreadCount = 0 }
  } catch { /* Retry on the next visible poll. */ }
  finally { readPending = false }
}
function newMessageId() { return globalThis.crypto?.randomUUID?.() || `chat-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}` }
async function sendMessage() {
  if (!sendAllowed.value || !draft.value) return
  const id = activeId.value, currentDraft = getDraft(id), content = currentDraft.content.trim(), stickerId = currentDraft.stickerId, attachmentIds = [...currentDraft.attachments]
  const signature = JSON.stringify({ content, attachmentIds, stickerId })
  if (!currentDraft.clientMessageId || currentDraft.signature !== signature) { currentDraft.clientMessageId = newMessageId(); currentDraft.signature = signature }
  const isCurrent = activeGuard(id), isSameUser = requestGuard(); sending.value = true; currentDraft.error = ''
  try {
    const message = await api<Message>(`/chat/conversations/${encodeURIComponent(id)}/messages`, { method: 'POST', body: { clientMessageId: currentDraft.clientMessageId, content, attachmentIds, stickerId } })
    if (!isCurrent()) return
    mergeMessages([message]); Object.assign(currentDraft, { content: '', stickerId: '', attachments: [], clientMessageId: '', signature: '', error: '' }); showAttachments.value = false; showExpressions.value = false
    await scrollToBottom(); await refreshConversations(); if (isCurrent()) await markRead()
  } catch (error) { if (isCurrent()) currentDraft.error = `${errorText(error, '发送失败。')} 内容和附件已保留，可以重试。` }
  finally { if (isSameUser()) sending.value = false }
}
function onComposerKeydown(event: KeyboardEvent) { if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !event.isComposing && !composing.value && event.keyCode !== 229) { event.preventDefault(); void sendMessage() } }
async function poll() { if (!alive || document.hidden || polling) return; if (getToken() !== currentToken) { reset(); return }; polling = true; try { await refreshConversations(); if (activeId.value && !messageLoading.value && !historyLoading.value) await loadMessages() } finally { polling = false } }
function onVisibilityChange() { if (!document.hidden) void poll() }
function beforeUnload(event: BeforeUnloadEvent) { if (navigationBusy.value) { event.preventDefault(); event.returnValue = '' } }
function reset() {
  authGeneration++; conversationGeneration++; showRecordOutput.value = false; showExpressions.value = false; currentToken = getToken(); activeId.value = ''; conversations.value = []; contacts.value = []; messages.value = []
  for (const id of Object.keys(drafts)) delete drafts[id]
  sending.value = false; uploading.value = false; messageLoading.value = false; historyLoading.value = false; contactsLoading.value = false; startingId.value = ''; contactDialog.value = false; listError.value = ''; messageError.value = ''; notice.value = ''
  if (currentToken) void initialize()
}
watch(() => String(props.user.id), reset)
watch(() => props.refreshKey, () => { if (getToken() !== currentToken) reset(); else void poll() })
onMounted(() => { reset(); pollTimer = setInterval(() => { void poll() }, 5000); document.addEventListener('visibilitychange', onVisibilityChange); window.addEventListener('beforeunload', beforeUnload) })
onBeforeUnmount(() => { alive = false; authGeneration++; conversationGeneration++; if (pollTimer) clearInterval(pollTimer); document.removeEventListener('visibilitychange', onVisibilityChange); window.removeEventListener('beforeunload', beforeUnload); emit('busy-change', false) })
</script>

<template>
  <section class="internal-chat" :class="{ 'has-active': activeId }" aria-label="内部聊天">
    <div v-if="notice" class="chat-notice" role="status">{{ notice }}</div>
    <ChatRecordOutput v-if="showRecordOutput && activeId" :key="activeId" :conversation-id="activeId" @close="showRecordOutput = false" />
    <aside class="chat-sidebar" aria-label="会话列表">
      <div class="chat-sidebar-heading"><h2>内部聊天</h2><el-button data-testid="chat-start" type="primary" :disabled="busy" @click="openContacts">发起会话</el-button></div>
      <div class="chat-search"><el-input v-model="search" clearable placeholder="搜索姓名、班级或学院" aria-label="搜索会话" /></div>
      <div v-if="listError" class="chat-error" role="alert">{{ listError }} <el-button link type="primary" @click="initialize">重试</el-button></div>
      <div v-if="loading && !conversations.length" class="chat-state" role="status">正在加载会话...</div>
      <div v-else-if="!filteredConversations.length" class="chat-state">{{ search ? '没有匹配的会话' : '暂无会话' }}</div>
      <div v-else class="chat-conversations">
        <button v-for="conversation in filteredConversations" :key="conversation.id" class="chat-conversation" :class="{ selected: activeId === conversation.id }" :aria-pressed="activeId === conversation.id" :disabled="busy" :data-testid="`chat-conversation-${conversation.id}`" @click="selectConversation(conversation.id)">
          <span class="chat-avatar" aria-hidden="true">{{ initials(conversation.peer.name) }}</span>
          <span class="chat-conversation-detail"><span class="chat-conversation-title"><strong>{{ conversation.peer.name }}</strong><time>{{ timeText(conversation.lastMessage?.createdAt) }}</time></span><span class="chat-peer-context">{{ roleText(conversation.peer.role) }}<template v-if="conversation.peer.className"> · {{ conversation.peer.className }}</template></span><span class="chat-preview">{{ preview(conversation) }}</span></span>
          <span v-if="conversation.unreadCount" class="chat-unread">{{ conversation.unreadCount > 99 ? '99+' : conversation.unreadCount }}</span>
          <span v-else-if="drafts[conversation.id]?.content || drafts[conversation.id]?.stickerId || drafts[conversation.id]?.attachments.length" class="chat-draft-label">草稿</span>
        </button>
      </div>
      <div v-if="hasDrafts" class="chat-draft-actions"><el-button link :disabled="busy" @click="discardAllDrafts">清空全部草稿</el-button></div>
    </aside>
    <main v-if="activeConversation" class="chat-main">
      <header class="chat-main-heading"><el-button class="chat-back" :disabled="busy" @click="backToList">返回</el-button><span class="chat-avatar" aria-hidden="true">{{ initials(activeConversation.peer.name) }}</span><div><h3>{{ activeConversation.peer.name }}</h3><p>{{ roleText(activeConversation.peer.role) }}<template v-if="activeConversation.peer.className"> · {{ activeConversation.peer.className }}</template><template v-if="activeConversation.peer.college"> · {{ activeConversation.peer.college }}</template></p></div><el-button class="chat-record-output" :disabled="messageLoading || busy" @click="showRecordOutput = true">导出 / 打印</el-button></header>
      <div v-if="messageError" class="chat-error" role="alert">{{ messageError }} <el-button link type="primary" @click="loadMessages(messages.length === 0)">重新加载</el-button></div>
      <div ref="threadEl" class="chat-thread" data-testid="chat-thread" role="log" aria-label="聊天消息" aria-live="polite" :aria-busy="messageLoading" @scroll="onScroll">
        <div v-if="messageLoading" class="chat-state" role="status">正在加载消息...</div>
        <template v-else>
          <div v-if="hasMore" class="chat-history"><el-button data-testid="chat-history" link type="primary" :loading="historyLoading" @click="loadHistory">加载更早消息</el-button></div>
          <div v-if="!messages.length && !messageError" class="chat-state">暂无消息</div>
          <article v-for="message in messages" :key="message.id" class="chat-message" :class="{ mine: String(message.senderId) === myId }" :data-testid="`chat-message-${message.id}`">
            <div class="chat-message-meta"><span>{{ String(message.senderId) === myId ? '我' : activeConversation.peer.name }}</span><time :datetime="message.createdAt">{{ timeText(message.createdAt) }}</time></div>
            <img v-if="chatSticker(message.stickerId)" class="chat-sticker-image" :src="chatSticker(message.stickerId)?.src" :alt="'表情包：' + chatSticker(message.stickerId)?.label" width="168" height="168" />
            <div v-if="chatMessageText(message) || message.attachmentIds?.length" class="chat-bubble"><p v-if="chatMessageText(message)" class="chat-message-text">{{ chatMessageText(message) }}</p><MaterialUpload v-if="message.attachmentIds?.length" :model-value="message.attachmentIds" readonly /></div>
            <span v-if="String(message.senderId) === myId" class="chat-receipt">{{ message.sequence <= activeConversation.peerReadSequence ? '对方已读' : '已发送' }}</span>
          </article>
        </template>
      </div>
      <div v-if="!atBottom && messages.length" class="chat-bottom-link"><el-button link type="primary" @click="scrollToBottom().then(markRead)">回到最新消息</el-button></div>
      <footer v-if="draft" class="chat-compose">
        <div v-if="!activeConversation.canSend" role="status">当前联系人已不可联系。</div>
        <template v-else>
          <div class="chat-compose-tools"><button ref="expressionToggle" type="button" class="chat-expression-toggle" data-testid="chat-expression-toggle" :aria-expanded="showExpressions" aria-controls="chat-expression-panel" :disabled="sending || messageLoading" @click="toggleExpressions"><span aria-hidden="true">☺</span> 表情</button><el-button data-testid="chat-file-toggle" :disabled="sending" :aria-expanded="showAttachments" @click="toggleAttachments">{{ showAttachments ? '收起文件' : '导入文件' }}<template v-if="draft.attachments.length">（{{ draft.attachments.length }}）</template></el-button><el-button v-if="draft.content.trim() || draft.stickerId || draft.attachments.length || draft.error" data-testid="chat-discard" link :disabled="busy" @click="discardDraft">清空草稿</el-button></div>
          <div v-if="showExpressions" id="chat-expression-panel" class="chat-expression-popover"><ChatExpressions :selected="draft.stickerId" :disabled="sending" @sticker="selectSticker" @emoji="insertEmoji" @close="closeExpressions" /></div>
          <div v-if="chatSticker(draft.stickerId)" class="chat-sticker-draft" data-testid="chat-sticker-draft"><img :src="chatSticker(draft.stickerId)?.src" alt="" width="60" height="60" /><span>待发送：{{ chatSticker(draft.stickerId)?.label }}</span><button type="button" :disabled="sending" aria-label="移除表情包" @click="draft.stickerId = ''">×</button></div>
          <div v-show="showAttachments" class="chat-upload"><p class="chat-import-hint">选择本地文件导入，上传完成后点击“发送”才会发给对方。</p><MaterialUpload :key="activeId" v-model="draft.attachments" :disabled="sending" @busy="uploading = $event" /></div>
          <textarea ref="composerEl" v-model="draft.content" class="chat-composer" data-testid="chat-composer" aria-label="输入聊天消息" placeholder="输入消息" maxlength="2000" :disabled="sending" rows="3" @compositionstart="composing = true" @compositionend="composing = false" @keydown="onComposerKeydown" />
          <div v-if="draft.error" class="chat-send-error" role="alert">{{ draft.error }}</div>
          <div v-if="uploading" class="chat-upload-status" role="status">附件正在上传或处理...</div>
          <div v-if="draft.attachments.length > 10" class="chat-send-error" role="alert">附件超过 10 个，请移除多余附件后发送。</div>
          <div class="chat-compose-footer"><span class="chat-character-count">{{ draft.content.length }}/2000</span><el-button data-testid="chat-send" type="primary" :disabled="!sendAllowed" :loading="sending" @click="sendMessage">{{ draft.error ? '重试发送' : '发送' }}</el-button></div>
        </template>
      </footer>
    </main>
    <div v-else class="chat-main chat-welcome"><h3>选择一个会话</h3><el-button type="primary" :disabled="busy" @click="openContacts">选择联系人</el-button></div>
    <el-dialog v-model="contactDialog" title="发起会话" width="min(540px, 94vw)" :close-on-click-modal="!startingId" :close-on-press-escape="!startingId" :show-close="!startingId" append-to-body>
      <el-input v-model="contactSearch" clearable placeholder="搜索姓名、班级或学院" aria-label="搜索联系人" />
      <div v-if="contactError" class="chat-error" role="alert">{{ contactError }} <el-button link type="primary" @click="openContacts">重试</el-button></div>
      <div v-if="contactsLoading" class="chat-state" role="status">正在加载联系人...</div><div v-else-if="!filteredContacts.length" class="chat-state">暂无可联系人员</div>
      <div v-else class="chat-contacts"><button v-for="contact in filteredContacts" :key="contact.id" class="chat-contact" :data-testid="`chat-contact-${contact.id}`" :disabled="!!startingId" @click="startConversation(contact)"><span class="chat-avatar" aria-hidden="true">{{ initials(contact.name) }}</span><span><strong>{{ contact.name }}</strong><small>{{ roleText(contact.role) }}<template v-if="contact.className"> · {{ contact.className }}</template><template v-if="contact.college"> · {{ contact.college }}</template></small></span><span class="chat-contact-action">{{ startingId === contact.id ? '正在进入...' : '发消息' }}</span></button></div>
    </el-dialog>
  </section>
</template>

<style scoped>
.chat-main-heading{flex-wrap:wrap}.chat-record-output{margin-left:auto}.chat-import-hint{font-size:12px;line-height:1.6;color:var(--muted,#64748b)}
.internal-chat { display: grid; grid-template-columns: 310px minmax(0,1fr); position: relative; min-height: 560px; height: min(760px,calc(100dvh - 160px)); overflow: hidden; border: 1px solid var(--el-border-color-light); border-radius: 8px; background: var(--el-bg-color); color: var(--el-text-color-primary); }
.chat-sidebar { min-width: 0; display: flex; flex-direction: column; border-right: 1px solid var(--el-border-color-light); overflow: hidden; }
.chat-sidebar-heading { display: flex; justify-content: space-between; align-items: center; gap: 10px; padding: 20px 16px; }
.chat-sidebar-heading h2 { font-size: 18px; margin: 0; }
.chat-search { padding: 0 16px 16px; }
.chat-conversations { flex: 1; overflow-y: auto; padding: 0 8px; }
.chat-conversation { display: flex; position: relative; align-items: center; gap: 11px; width: 100%; padding: 14px 10px; border: 1px solid transparent; border-radius: 8px; background: transparent; text-align: left; cursor: pointer; color: inherit; font: inherit; margin-bottom: 4px; }
.chat-conversation:hover,.chat-contact:hover { background: var(--el-fill-color-light); }
.chat-conversation.selected { background: var(--el-color-primary-light-9); border-color: var(--el-color-primary-light-7); }
.chat-conversation:focus-visible,.chat-contact:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: -2px; }
.chat-avatar { display: inline-flex; flex: 0 0 40px; height: 40px; align-items: center; justify-content: center; border-radius: 8px; background: var(--el-color-primary-light-9); color: var(--el-color-primary); font-size: 14px; font-weight: 600; }
.chat-conversation-detail { min-width: 0; flex: 1; }
.chat-conversation-title { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
.chat-conversation-title strong { font-size: 14px; overflow-wrap: anywhere; flex: 1; }
.chat-conversation-title time { color: var(--el-text-color-secondary); font-size: 10px; }
.chat-peer-context,.chat-preview { display: block; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; color: var(--el-text-color-secondary); font-size: 12px; margin-top: 5px; }
.chat-preview { padding-right: 24px; }
.chat-unread,.chat-draft-label { position: absolute; right: 10px; bottom: 13px; font-size: 11px; }
.chat-unread { background: var(--el-color-danger); color: white; border-radius: 20px; padding: 0 5px; min-width: 18px; text-align: center; }
.chat-draft-label { color: var(--el-color-warning); }
.chat-draft-actions { border-top: 1px solid var(--el-border-color-light); padding: 12px 16px; }
.chat-main { min-width: 0; min-height: 0; display: flex; flex-direction: column; overflow: hidden; background: var(--el-fill-color-lighter); }
.chat-main-heading { display: flex; align-items: center; gap: 12px; padding: 16px 24px; border-bottom: 1px solid var(--el-border-color-light); background: var(--el-bg-color); }
.chat-main-heading h3 { margin: 0; font-size: 16px; overflow-wrap: anywhere; }
.chat-main-heading p { margin: 5px 0 0; font-size: 12px; color: var(--el-text-color-secondary); }
.chat-back { display: none; }
.chat-thread { flex: 1; min-height: 0; overflow-y: auto; padding: 18px 24px; overscroll-behavior: contain; }
.chat-history,.chat-bottom-link { text-align: center; padding: 6px; }
.chat-message { display: flex; flex-direction: column; align-items: flex-start; margin-bottom: 20px; }
.chat-message.mine { align-items: flex-end; }
.chat-message-meta { display: flex; gap: 12px; color: var(--el-text-color-secondary); font-size: 11px; margin-bottom: 7px; }
.chat-bubble { max-width: min(86%,680px); min-width: 48px; border: 1px solid var(--el-border-color-light); border-radius: 4px 8px 8px; padding: 11px 15px; background: var(--el-bg-color); overflow-wrap: anywhere; }
.mine .chat-bubble { background: var(--el-color-primary-light-9); border-color: var(--el-color-primary-light-8); }
.chat-message-text { margin: 0; white-space: pre-wrap; font-size: 14px; line-height: 1.8; }
.chat-receipt { color: var(--el-text-color-secondary); font-size: 11px; margin-top: 5px; }
.chat-sticker-image{display:block;width:168px;height:168px;object-fit:contain;flex-shrink:0;margin:2px 0 6px}.chat-sticker-draft{display:flex;align-items:center;gap:10px;padding:6px 10px;margin-bottom:8px;background:var(--el-fill-color-lighter);border:1px solid var(--el-border-color-light);border-radius:10px;font-size:13px}.chat-sticker-draft img{width:60px;height:60px}.chat-sticker-draft button{margin-left:auto;background:transparent;border:0;cursor:pointer;width:36px;height:36px;font-size:22px;color:inherit}.chat-expression-toggle{display:inline-flex;align-items:center;gap:5px;min-height:36px;border:1px solid var(--el-border-color);border-radius:8px;background:var(--el-bg-color);color:inherit;font:inherit;font-size:13px;padding:6px 12px;cursor:pointer}.chat-expression-toggle span{font-size:20px;line-height:1}.chat-expression-toggle[aria-expanded=true]{color:var(--el-color-primary);border-color:var(--el-color-primary)}.chat-expression-toggle:focus-visible,.chat-sticker-draft button:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}.chat-expression-toggle:disabled{opacity:.5;cursor:default}.chat-expression-popover{position:absolute;bottom:calc(100% + 5px);left:12px;right:12px;max-width:496px;max-height:clamp(160px,calc(100dvh - 440px),340px);overflow:auto;z-index:5;border-radius:14px}
.chat-compose { position:relative;flex-shrink:0; padding: 12px 20px 16px; background: var(--el-bg-color); border-top: 1px solid var(--el-border-color-light); }
.chat-compose-tools { flex-wrap:wrap;display: flex; align-items: center; gap: 8px; margin-bottom: 9px; }
.chat-upload { max-height: 220px; overflow-y: auto; margin-bottom: 10px; }
.chat-composer { display: block; width: 100%; box-sizing: border-box; padding: 10px 12px; border: 1px solid var(--el-border-color-light); border-radius: 8px; resize: vertical; min-height: 76px; max-height: 160px; background: var(--el-fill-color-blank); color: inherit; font: inherit; font-size: 14px; line-height: 1.6; }
.chat-composer:focus { outline: 2px solid var(--el-color-primary-light-5); }
.chat-compose-footer { display: flex; justify-content: flex-end; align-items: center; gap: 12px; margin-top: 10px; }
.chat-character-count,.chat-upload-status { color: var(--el-text-color-secondary); font-size: 12px; }
.chat-error,.chat-send-error { font-size: 12px; line-height: 1.6; color: var(--el-color-danger); padding: 10px 14px; background: var(--el-color-danger-light-9); overflow-wrap: anywhere; }
.chat-notice { position: absolute; z-index: 3; top: 8px; left: 10%; max-width: 80%; padding: 10px 16px; background: var(--el-color-warning-light-9); font-size: 13px; }
.chat-state { padding: 35px 20px; color: var(--el-text-color-secondary); font-size: 13px; text-align: center; }
.chat-welcome { align-items: center; justify-content: center; padding: 24px; }
.chat-welcome h3 { font-size: 18px; font-weight: 500; margin-bottom: 20px; }
.chat-contacts { max-height: 380px; overflow-y: auto; margin-top: 14px; }
.chat-contact { display: flex; align-items: center; gap: 12px; width: 100%; padding: 14px 8px; border: 0; border-bottom: 1px solid var(--el-border-color-lighter); background: transparent; text-align: left; color: inherit; cursor: pointer; font: inherit; }
.chat-contact strong { display: block; font-size: 14px; }
.chat-contact small { display: block; color: var(--el-text-color-secondary); margin-top: 5px; font-size: 12px; }
.chat-contact-action { margin-left: auto; color: var(--el-color-primary); font-size: 12px; white-space: nowrap; }
.chat-compose-tools .el-button{margin-left:0}
@media (max-width: 980px) { .internal-chat { grid-template-columns: 260px minmax(0,1fr); } .chat-sidebar-heading { flex-wrap: wrap; } .chat-thread { padding: 16px; } }
@media (max-width: 700px) { .internal-chat { display: flex; min-height: 520px; height: calc(100dvh - 135px); } .chat-sidebar { width: 100%; border-right: 0; } .chat-main { display: none; width: 100%; } .has-active .chat-sidebar { display: none; } .has-active .chat-main { display: flex; } .chat-back { display: inline-flex; } .chat-main-heading { padding: 12px; } .chat-main-heading .chat-avatar { display: none; }
.chat-compose { position:relative;flex-shrink:0; padding: 10px 12px; } .chat-bubble { max-width: 90%; padding: 9px 12px; } }
</style>
