<template>
  <details class="sources">
    <summary class="sources-summary"><AgentIcon name="link" /><span>参考来源</span><span class="source-count">{{ citedCount }} 条引用 · {{ sources.length }} 条召回</span><AgentIcon name="down" /></summary>
    <div class="source-details">
      <div class="sources-head"><span>查看原文，核对回答依据</span><button class="toggle" type="button" @click="showAll = !showAll">{{ showAll ? '只看被引用' : '显示全部召回' }}</button></div>
      <article v-for="source in visibleSources" :key="source.index" class="source" :class="{ uncited: !source.cited }">
        <button class="source-head" :aria-expanded="expanded.has(source.index)" @click="toggle(source.index)"><span class="index">{{ source.index }}</span><AgentIcon name="file" /><span class="file">{{ source.originalFileName }}</span><span class="meta">{{ formatPages(source.pageNumberStart, source.pageNumberEnd) }}</span><AgentIcon name="down" :class="{ rotated: expanded.has(source.index) }" /></button>
        <p class="snippet" :class="{ expanded: expanded.has(source.index) }">{{ source.content }}</p>
        <div class="source-foot"><span>{{ source.cited ? '回答已引用' : '仅召回，未引用' }}</span><span title="分数含义由检索模式决定，混合检索为 RRF 排名分，不是相似度百分比">检索分数 {{ formatScore(source.score) }}</span></div>
      </article>
      <div v-if="!visibleSources.length" class="none">本轮回答未标注有效引用，可切换到全部召回查看资料。</div>
    </div>
  </details>
</template>

<script setup>
import { computed, ref } from 'vue'
import AgentIcon from './AgentIcon.vue'
import { formatPages, formatScore } from '../utils/format'
const props = defineProps({ sources: { type: Array, default: () => [] } })
const showAll = ref(false)
const expanded = ref(new Set())
const citedCount = computed(() => props.sources.filter((item) => item.cited).length)
const visibleSources = computed(() => showAll.value ? props.sources : props.sources.filter((item) => item.cited))
const toggle = (index) => {
  const next = new Set(expanded.value)
  if (next.has(index)) next.delete(index)
  else next.add(index)
  expanded.value = next
}
</script>

<style scoped>
.sources { margin-top: 19px; border: 1px solid var(--border); border-radius: 11px; background: var(--surface); }
.sources-summary { display: flex; align-items: center; gap: 8px; padding: 11px 13px; cursor: pointer; list-style: none; font-size: 11px; color: var(--text-secondary); }
.sources-summary::-webkit-details-marker { display: none; }
.sources-summary > .agent-icon:first-child { font-size: 14px; color: var(--brand); }
.sources-summary > .agent-icon:last-child { margin-left: auto; transition: transform 0.2s; }
.sources[open] .sources-summary > .agent-icon:last-child { transform: rotate(180deg); }
.sources-summary:focus-visible { outline: 2px solid var(--brand-light-3); outline-offset: 2px; border-radius: 11px; }
.source-count { color: var(--text-dim); font-size: 10px; margin-left: 3px; }
.source-details { padding: 0 12px 12px; }
.sources-head { display: flex; justify-content: space-between; align-items: center; gap: 10px; padding: 9px 0 11px; border-top: 1px solid var(--border); font-size: 10px; color: var(--text-dim); }
.toggle { background: transparent; border: 0; padding: 3px 0; font-size: 10px; color: var(--brand); cursor: pointer; flex-shrink: 0; }
.source { padding: 11px 12px; border: 1px solid var(--border); border-radius: 9px; margin-bottom: 8px; background: var(--main-bg); }
.source:last-child { margin-bottom: 0; }
.source.uncited { background: var(--surface-soft); }
.source-head { display: flex; align-items: center; gap: 7px; width: 100%; padding: 0; border: 0; background: transparent; text-align: left; color: var(--text-secondary); font-size: 11px; cursor: pointer; }
.source-head > .agent-icon { flex-shrink: 0; }
.source-head > .agent-icon:last-child { margin-left: auto; color: var(--text-dim); }
.index { display: inline-flex; align-items: center; justify-content: center; min-width: 18px; height: 18px; border-radius: 5px; background: var(--brand-soft); color: var(--brand); font-size: 10px; }
.file { font-weight: 500; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.meta { color: var(--text-dim); flex-shrink: 0; font-size: 10px; }
.rotated { transform: rotate(180deg); }
.snippet { margin: 9px 0; color: var(--text-secondary); font-size: 12px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.snippet.expanded { display: block; -webkit-line-clamp: unset; max-height: 300px; overflow-y: auto; }
.source-foot { display: flex; justify-content: space-between; gap: 8px; color: var(--text-dim); font-size: 9px; }
.none { padding: 7px 0; color: var(--text-secondary); font-size: 11px; line-height: 1.7; }
</style>
