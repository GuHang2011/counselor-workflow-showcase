<script setup lang="ts">
import { ref } from 'vue';
import { CHAT_EMOJI, CHAT_STICKERS } from '../../shared/chat-expressions';
defineProps<{ selected?: string; disabled?: boolean }>();
const emit = defineEmits<{ (event: 'sticker', id: string): void; (event: 'emoji', symbol: string): void; (event: 'close'): void }>();
const tab = ref('stickers');
</script>

<template>
  <section class="expression-panel" aria-label="选择表情" @keydown.esc.stop.prevent="emit('close')">
    <header>
      <div class="expression-tabs" aria-label="表情类型">
        <button type="button" :aria-pressed="tab === 'stickers'" @click="tab = 'stickers'">办公表情包</button>
        <button type="button" :aria-pressed="tab === 'emoji'" @click="tab = 'emoji'">常用表情</button>
      </div>
      <button type="button" class="expression-close" aria-label="关闭表情面板" @click="emit('close')">×</button>
    </header>
    <div v-if="tab === 'stickers'" class="expression-grid sticker-grid">
      <button v-for="item in CHAT_STICKERS" :key="item.id" type="button" :aria-label="'选择表情包：' + item.label" :aria-pressed="selected === item.id" :disabled="disabled" @click="emit('sticker', item.id)">
        <img :src="item.src" alt="" width="72" height="72" /><span>{{ item.label }}</span>
      </button>
    </div>
    <div v-else class="expression-grid emoji-grid">
      <button v-for="item in CHAT_EMOJI" :key="item.label" type="button" :aria-label="'插入表情：' + item.label" :title="item.label" :disabled="disabled" @click="emit('emoji', item.symbol)">{{ item.symbol }}</button>
    </div>
    <p>{{ tab === 'stickers' ? '选择后可预览，点击发送才会发给对方。' : '点击加入文字消息，可与文字一起发送。' }}</p>
  </section>
</template>

<style scoped>
.expression-panel{background:var(--el-bg-color);border:1px solid var(--el-border-color-light);border-radius:14px;box-shadow:0 8px 28px #1c35571c;padding:10px;width:100%;max-width:496px;color:var(--el-text-color-primary)}
header{display:flex;align-items:center;gap:8px;margin-bottom:8px}.expression-tabs{display:flex;gap:4px;flex:1}.expression-panel button{font:inherit;cursor:pointer;color:inherit;border:1px solid transparent;background:transparent;border-radius:8px;margin:0}
.expression-tabs button{min-height:38px;padding:8px 12px;font-size:13px}.expression-tabs button[aria-pressed=true]{background:var(--el-color-primary-light-9);color:var(--el-color-primary);font-weight:600}.expression-close{width:38px;height:38px;font-size:24px!important}
.expression-grid{display:grid;gap:6px;max-height:min(234px,35dvh);overflow:auto;overscroll-behavior:contain;padding:2px}.sticker-grid{grid-template-columns:repeat(4,minmax(0,1fr))}.sticker-grid button{display:flex;flex-direction:column;align-items:center;padding:4px;font-size:12px;gap:3px;min-width:0}.sticker-grid img{max-width:100%;height:auto}.sticker-grid button[aria-pressed=true]{border-color:var(--el-color-primary);background:var(--el-color-primary-light-9)}
.emoji-grid{grid-template-columns:repeat(8,minmax(0,1fr))}.emoji-grid button{font-size:25px;min-height:44px;padding:5px 0}.expression-panel button:hover{background:var(--el-fill-color-light)}.expression-panel button:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:-2px}.expression-panel button:disabled{opacity:.5;cursor:default}p{font-size:11px;color:var(--el-text-color-secondary);margin:8px 3px 0;line-height:1.6}
@media(max-width:460px){.emoji-grid{grid-template-columns:repeat(6,minmax(0,1fr))}.sticker-grid img{width:60px}.expression-tabs button{padding-inline:8px}}
</style>
