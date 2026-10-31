<script setup lang="ts">
import { ref } from 'vue';
import { CHAT_EMOJI, CHAT_STICKERS } from '../../../shared/chat-expressions';
defineProps<{ selected?: string; disabled?: boolean }>();
const emit = defineEmits<{ (event: 'sticker', id: string): void; (event: 'emoji', symbol: string): void; (event: 'close'): void }>();
const tab = ref('stickers');
</script>
<template>
  <view class="expression-panel" aria-label="选择表情">
    <view class="expression-tabs">
      <button size="mini" :class="{active:tab === 'stickers'}" @click="tab = 'stickers'">办公表情包</button>
      <button size="mini" :class="{active:tab === 'emoji'}" @click="tab = 'emoji'">常用表情</button>
      <button size="mini" class="expression-close" @click="emit('close')">收起</button>
    </view>
    <scroll-view scroll-y class="expression-scroll">
      <view v-if="tab === 'stickers'" class="expression-grid sticker-grid">
        <button v-for="item in CHAT_STICKERS" :key="item.id" :data-testid="'mobile-sticker-' + item.id" :class="{chosen:selected === item.id}" :disabled="disabled" @click="emit('sticker', item.id)"><image :src="item.src" mode="aspectFit" /><text>{{ item.label }}</text></button>
      </view>
      <view v-else class="expression-grid emoji-grid">
        <button v-for="item in CHAT_EMOJI" :key="item.label" :aria-label="'插入表情：' + item.label" :disabled="disabled" @click="emit('emoji', item.symbol)">{{ item.symbol }}</button>
      </view>
    </scroll-view>
    <text class="expression-hint">{{tab === 'stickers' ? '选择后预览，点击发送才会发给对方。' : '点击加入文字消息，可与文字一起发送。'}}</text>
  </view>
</template>
<style scoped>
.expression-panel{border:1px solid #dce5f1;background:white;border-radius:12px;padding:10px;box-sizing:border-box;color:#24324a}.expression-tabs{display:flex;gap:6px;margin-bottom:8px;align-items:center}.expression-tabs button{margin:0;background:#f4f7fc;padding:0 9px;font-size:12px;line-height:36px}.expression-tabs .active{background:#e9f0ff;color:#225bd4}.expression-tabs .expression-close{margin-left:auto}.expression-scroll{height:220px;max-height:35vh}.expression-grid{display:grid;gap:5px;padding:2px}.sticker-grid{grid-template-columns:repeat(4,minmax(0,1fr))}.expression-grid button{margin:0;padding:5px 2px;background:white;min-width:0;line-height:1.6;border:1px solid transparent;border-radius:8px}.expression-grid button::after{border:0}.sticker-grid button{display:flex;flex-direction:column;align-items:center;font-size:11px}.sticker-grid image{width:60px;height:60px;max-width:100%}.sticker-grid .chosen{border-color:#2763de;background:#edf3ff}.emoji-grid{grid-template-columns:repeat(6,minmax(0,1fr))}.emoji-grid button{font-size:25px;min-height:44px}.expression-hint{display:block;font-size:11px;color:#69768b;margin-top:8px;line-height:1.6}
</style>
