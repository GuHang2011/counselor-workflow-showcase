<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';

type Entry = { id: string; title: string; desc: string; icon: string };
const props = defineProps<{ modelValue: boolean; entries: Entry[]; aliases: Record<string, string> }>();
const emit = defineEmits<{ (event: 'update:modelValue', value: boolean): void; (event: 'choose', id: string): void }>();
const query = ref(''), selected = ref(0);
const normalize = (value: string) => value.toLocaleLowerCase().replace(/\s+/g, '');
const results = computed(() => props.entries.filter(item => normalize(item.title + item.desc + (props.aliases[item.id] || '')).includes(normalize(query.value))));
const activeId = computed(() => results.value[selected.value] ? 'feature-result-' + results.value[selected.value].id : undefined);
watch(() => props.modelValue, open => { if (open) { query.value = ''; selected.value = 0; } });
watch(results, () => { selected.value = 0; });
function choose(id: string) { emit('update:modelValue', false); emit('choose', id); }
function onKey(event: KeyboardEvent) {
  if (event.isComposing || event.keyCode === 229) return;
  if (event.key === 'Enter') {
    event.preventDefault();
    const entry = results.value[selected.value];
    if (entry) choose(entry.id);
  } else if (['ArrowDown', 'ArrowUp'].includes(event.key)) {
    event.preventDefault();
    if (!results.value.length) return;
    selected.value = (selected.value + (event.key === 'ArrowDown' ? 1 : -1) + results.value.length) % results.value.length;
    nextTick(() => document.getElementById(activeId.value!)?.scrollIntoView({ block: 'nearest' }));
  }
}
function focusInput() { document.getElementById('command-search')?.focus(); }
</script>

<template>
  <el-dialog :model-value="modelValue" title="查找功能" width="min(92vw, 560px)" class="command-dialog" @update:model-value="emit('update:modelValue', $event)" @opened="focusInput">
    <p class="command-dialog-intro">输入“查学生”“收材料”“改密码”，快速找到办理入口。</p>
    <el-input id="command-search" v-model="query" clearable placeholder="搜索功能名称或用途" aria-label="搜索功能名称或用途" role="combobox" aria-autocomplete="list" :aria-expanded="modelValue" aria-controls="command-results" :aria-activedescendant="activeId" size="large" autocomplete="off" @keydown="onKey"><template #prefix><el-icon><Search /></el-icon></template></el-input>
    <p class="command-result-count" role="status">{{ query.trim() ? '找到' : '可用功能' }} {{ results.length }} 项</p>
    <div id="command-results" class="command-list" role="listbox" aria-label="功能搜索结果">
      <button v-for="(item, index) in results" :id="'feature-result-' + item.id" :key="item.id" type="button" class="command-item" :class="{ 'is-selected': index === selected }" role="option" :aria-selected="index === selected" @click="choose(item.id)">
        <span class="command-item-icon" aria-hidden="true"><el-icon><component :is="item.icon" /></el-icon></span><span><strong>{{ item.title }}</strong><small>{{ item.desc }}</small></span><el-icon class="command-arrow" aria-hidden="true"><ArrowRight /></el-icon>
      </button>
    </div>
    <p v-if="!results.length" class="command-empty" role="status">没有找到相关功能，可以试试“任务”“学生”“材料”或“设置”。</p>
    <div class="command-dialog-foot"><span>↑ ↓ 选择 · Enter 打开</span><span>Esc 关闭</span></div>
  </el-dialog>
</template>
