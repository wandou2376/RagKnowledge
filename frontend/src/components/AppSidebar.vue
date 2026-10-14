<template>
  <aside id="workspace-sidebar" class="sidebar" :class="{ collapsed }" aria-label="工作区导航">
    <div class="sidebar-inner">
      <div class="brand">
        <div class="brand-mark"><AgentIcon name="spark" /></div>
        <div class="brand-text"><span class="brand-title">知序</span><span class="brand-sub">KNOWLEDGE WORKSPACE</span></div>
      </div>

      <div class="section">
        <button class="new-chat" type="button" :disabled="!currentKnowledgeBaseId || sending" @click="onNewConversation"><AgentIcon name="plus" /><span>新建对话</span><span class="button-detail">NEW</span></button>
        <div class="section-label"><span>当前知识库</span><button class="section-action" title="管理知识库" aria-label="管理知识库" @click="openKnowledgeBases"><AgentIcon name="settings" /></button></div>
        <div class="kb-picker">
          <AgentIcon name="book" />
          <el-select :model-value="currentKnowledgeBaseId" :loading="loadingKnowledgeBases" :disabled="sending" placeholder="选择或创建知识库" class="kb-select" aria-label="当前知识库" @change="onSelectKnowledgeBase">
            <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
            <template #empty><div class="kb-empty">还没有知识库，请先创建</div></template>
          </el-select>
        </div>
      </div>

      <div class="section section-grow">
        <div class="section-label history-label"><span>最近对话</span><span class="history-count">{{ conversations.length }}</span></div>
        <div class="conversation-list">
          <div v-if="!conversations.length" class="conversation-empty"><AgentIcon name="chat" /><span>从一个问题开始</span><small>你的对话会显示在这里</small></div>
          <div v-for="item in conversations" :key="item.id" class="conversation" :class="{ active: item.id === currentConversationId }">
            <button class="conversation-open" :title="item.title" :disabled="sending" :aria-current="item.id === currentConversationId ? 'true' : undefined" @click="onOpenConversation(item.id)"><AgentIcon name="chat" /><span>{{ item.title }}</span></button>
            <button class="conversation-remove" type="button" :disabled="sending" title="仅从本地列表移除" :aria-label="`从列表移除：${item.title}`" @click="forgetConversation(item.id)"><AgentIcon name="close" /></button>
          </div>
        </div>
      </div>

      <div class="section sidebar-footer">
        <button class="nav-link" :disabled="!currentKnowledgeBaseId" @click="openDocuments"><AgentIcon name="file" /><span>文档管理</span><span class="nav-meta">{{ documentCount ?? '—' }}</span></button>
        <router-link class="nav-link" :class="{ selected: route.path === '/retrieval' }" to="/retrieval"><AgentIcon name="search" /><span>检索实验室</span><AgentIcon name="chevron" /></router-link>
        <button class="nav-link" @click="openKnowledgeBases"><AgentIcon name="settings" /><span>知识库设置</span></button>
        <div class="sidebar-note"><span class="note-dot" />以文档为依据，让回答有出处</div>
      </div>
    </div>
    <KnowledgeBaseDialog v-model="kbDialogVisible" />
    <DocumentDrawer v-model="docDrawerVisible" :document-count="documentCount" @changed="refreshDocumentCount" />
  </aside>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AgentIcon from './AgentIcon.vue'
import KnowledgeBaseDialog from './KnowledgeBaseDialog.vue'
import DocumentDrawer from './DocumentDrawer.vue'
import { listDocuments } from '../api/document'
import { useWorkspace } from '../composables/useWorkspace'

defineProps({ collapsed: Boolean })
const route = useRoute()
const router = useRouter()
const { knowledgeBases, currentKnowledgeBaseId, conversations, currentConversationId, loadingKnowledgeBases, sending, selectKnowledgeBase, forgetConversation, startNewConversation, openConversation } = useWorkspace()
const kbDialogVisible = ref(false)
const docDrawerVisible = ref(false)
const documentCount = ref(null)

const refreshDocumentCount = async () => {
  const id = currentKnowledgeBaseId.value
  if (!id) { documentCount.value = null; return }
  try {
    const documents = await listDocuments(id)
    if (id === currentKnowledgeBaseId.value) documentCount.value = documents.length
  } catch {
    if (id === currentKnowledgeBaseId.value) documentCount.value = null
  }
}
watch(currentKnowledgeBaseId, refreshDocumentCount)
onMounted(refreshDocumentCount)
watch(() => route.fullPath, () => { kbDialogVisible.value = false; docDrawerVisible.value = false })
const openDocuments = () => { docDrawerVisible.value = true }
const openKnowledgeBases = () => { kbDialogVisible.value = true }
defineExpose({ openDocuments, openKnowledgeBases })
const onNewConversation = () => {
  if (!currentKnowledgeBaseId.value || sending.value) return
  startNewConversation()
  router.push('/')
}
const onSelectKnowledgeBase = (id) => { selectKnowledgeBase(id) }
const onOpenConversation = async (id) => {
  if (sending.value) return
  try { await openConversation(id); await router.push('/') } catch { /* 请求拦截器统一提示错误。 */ }
}
</script>

<style scoped>
.sidebar { width: var(--sidebar-width); flex-shrink: 0; height: 100%; background: var(--sidebar-bg); color: var(--sidebar-text); transition: width 0.2s ease; z-index: 40; overflow: hidden; }
.sidebar-inner { display: flex; flex-direction: column; width: var(--sidebar-width); height: 100%; }
.sidebar.collapsed { width: 0; visibility: hidden; }
.sidebar.collapsed .sidebar-inner { opacity: 0; pointer-events: none; }
.brand { display: flex; align-items: center; gap: 11px; padding: 28px 23px 32px; }
.brand-mark { color: var(--sidebar-heading); font-size: 30px; }
.brand-text { display: flex; flex-direction: column; gap: 5px; }
.brand-title { color: var(--sidebar-heading); font-size: 19px; font-weight: 600; letter-spacing: 2px; }
.brand-sub { color: var(--sidebar-text-dim); font-size: 8px; letter-spacing: 1.5px; }
.section { padding: 0 16px; }
.section-label { display: flex; justify-content: space-between; align-items: center; margin: 26px 9px 10px; color: var(--sidebar-text-dim); font-size: 11px; letter-spacing: 0.5px; }
.section-action { display: flex; padding: 3px; border: 0; border-radius: 4px; background: transparent; color: var(--sidebar-text-dim); font-size: 15px; cursor: pointer; }
.section-action:hover { color: var(--sidebar-heading); }
.new-chat { display: flex; align-items: center; gap: 10px; width: 100%; height: 43px; padding: 0 13px; border: 1px solid var(--sidebar-border); border-radius: 10px; background: var(--sidebar-bg-hover); color: var(--sidebar-heading); font-size: 13px; cursor: pointer; transition: background 0.15s ease; }
.new-chat:hover:not(:disabled) { background: var(--sidebar-bg-active); }
.new-chat > .agent-icon { font-size: 18px; }
.new-chat:disabled { opacity: 0.45; }
.button-detail { margin-left: auto; font-size: 9px; color: var(--sidebar-text-dim); letter-spacing: 1px; }
.kb-picker { display: flex; align-items: center; gap: 9px; min-width: 0; padding: 0 10px; border: 1px solid var(--sidebar-border); border-radius: 9px; background: var(--sidebar-bg); }
.kb-picker > .agent-icon { font-size: 17px; color: var(--sidebar-text-dim); }
.kb-select { flex: 1; min-width: 0; }
.kb-select :deep(.el-select__wrapper) { box-shadow: none !important; min-height: 38px; background: transparent; padding-left: 0; }
.kb-select :deep(.el-select__selected-item) { color: var(--sidebar-text); font-size: 12px; }
.kb-select :deep(.el-select__placeholder.is-transparent), .kb-select :deep(.el-select__caret) { color: var(--sidebar-text-dim); }
.kb-empty { padding: 16px; text-align: center; font-size: 12px; color: var(--text-dim); }
.section-grow { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.history-label { margin-top: 31px; }
.history-count { font-size: 10px; }
.conversation-list { overflow-y: auto; flex: 1; }
.conversation-empty { display: flex; align-items: flex-start; flex-direction: column; gap: 9px; padding: 23px 10px; color: var(--sidebar-text-dim); font-size: 12px; }
.conversation-empty > .agent-icon { font-size: 21px; margin-bottom: 4px; opacity: 0.6; }
.conversation-empty small { font-size: 10px; opacity: 0.7; }
.conversation { display: flex; align-items: center; border-radius: 8px; margin-bottom: 3px; transition: background 0.15s; }
.conversation:hover { background: var(--sidebar-bg-hover); }
.conversation.active { background: var(--sidebar-bg-active); }
.conversation-open { display: flex; align-items: center; gap: 9px; min-width: 0; flex: 1; padding: 11px 9px; background: transparent; border: 0; text-align: left; color: var(--sidebar-text); font-size: 12px; cursor: pointer; }
.conversation-open > .agent-icon { color: var(--sidebar-text-dim); font-size: 14px; }
.conversation-open span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.conversation-remove { display: flex; align-items: center; justify-content: center; margin-right: 6px; width: 23px; height: 23px; border: 0; border-radius: 5px; background: transparent; color: var(--sidebar-text-dim); opacity: 0; cursor: pointer; }
.conversation:hover .conversation-remove, .conversation:focus-within .conversation-remove { opacity: 1; }
.conversation-remove:hover { color: var(--sidebar-heading); background: var(--sidebar-bg-active); }
.sidebar-footer { border-top: 1px solid var(--sidebar-border); padding-top: 13px; padding-bottom: 18px; margin-top: 18px; }
.nav-link { display: flex; align-items: center; gap: 11px; width: 100%; padding: 11px 10px; border: 0; border-radius: 8px; background: transparent; color: var(--sidebar-text); font-size: 12px; text-decoration: none; cursor: pointer; }
.nav-link:hover, .nav-link.selected { background: var(--sidebar-bg-hover); }
.nav-link:disabled { opacity: 0.4; }
.nav-link > .agent-icon { color: var(--sidebar-text-dim); font-size: 16px; }
.nav-link span:first-of-type { flex: 1; text-align: left; }
.nav-meta { color: var(--sidebar-text-dim); font-size: 11px; }
.sidebar-note { display: flex; align-items: center; gap: 6px; padding: 22px 10px 0; color: var(--sidebar-text-dim); font-size: 9px; }
.note-dot { height: 4px; width: 4px; border-radius: 50%; background: var(--sidebar-text-dim); }
</style>
