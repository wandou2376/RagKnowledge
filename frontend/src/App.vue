<template>
  <div class="app">
    <AppSidebar ref="sidebar" :collapsed="sidebarCollapsed" />
    <main class="app-main">
      <header class="workspace-header">
        <div class="workspace-location">
          <button class="icon-button" :aria-label="navigationOpen ? '收起导航' : '展开导航'" :aria-expanded="navigationOpen" aria-controls="workspace-sidebar" @click="toggleSidebar"><AgentIcon name="panel" /></button>
          <span class="workspace-label">工作空间</span>
          <span class="location-separator">/</span>
          <span class="workspace-name">{{ currentKnowledgeBase?.name || '我的知识库' }}</span>
        </div>
        <div class="workspace-actions">
          <span class="workspace-mode"><span class="status-dot" /> 文档驱动的问答</span>
          <button class="icon-button" aria-label="新建对话" title="新建对话" :disabled="!currentKnowledgeBaseId || sending" @click="newConversation"><AgentIcon name="plus" /></button>
        </div>
      </header>
      <router-view v-slot="{ Component }">
        <component :is="Component" @open-documents="sidebar?.openDocuments()" @manage-knowledge-bases="sidebar?.openKnowledgeBases()" />
      </router-view>
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppSidebar from './components/AppSidebar.vue'
import AgentIcon from './components/AgentIcon.vue'
import { useWorkspace } from './composables/useWorkspace'

const { loadKnowledgeBases, currentKnowledgeBase, currentKnowledgeBaseId, sending, startNewConversation } = useWorkspace()
const router = useRouter()
const sidebar = ref(null)
const sidebarCollapsed = ref(false)
const navigationOpen = computed(() => !sidebarCollapsed.value)
const toggleSidebar = () => {
  sidebarCollapsed.value = !sidebarCollapsed.value
}
const newConversation = () => { startNewConversation(); router.push('/') }
onMounted(() => {
  loadKnowledgeBases().catch(() => {})
})
</script>

<style scoped>
.app { display: flex; height: 100vh; overflow: hidden; }
.app-main { flex: 1; min-width: 0; background: var(--main-bg); display: flex; flex-direction: column; }
.workspace-header { height: 64px; min-height: 64px; display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 0 26px; }
.workspace-location, .workspace-actions { display: flex; align-items: center; gap: 13px; min-width: 0; }
.workspace-label, .location-separator { color: var(--text-dim); font-size: 12px; white-space: nowrap; }
.workspace-name { font-size: 13px; font-weight: 500; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.workspace-mode { display: flex; align-items: center; gap: 7px; font-size: 11px; color: var(--text-secondary); white-space: nowrap; }
.status-dot { width: 5px; height: 5px; background: var(--brand); border-radius: 50%; }
@media (max-width: 1000px) { .workspace-mode { display: none; } }
</style>
