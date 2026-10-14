<template>
  <section class="chat" :class="{ 'is-empty': !messages.length }" aria-label="知识库对话">
    <div ref="scrollArea" class="stream" role="log" aria-label="对话消息" aria-live="polite" :aria-busy="sending">
      <div v-if="!messages.length" class="welcome">
        <div class="welcome-symbol"><AgentIcon name="spark" /></div>
        <span class="welcome-eyebrow">YOUR KNOWLEDGE, CONNECTED</span>
        <h1>让知识，成为答案。</h1>
        <p v-if="currentKnowledgeBase">在「{{ currentKnowledgeBase.name }}」中探索，让每一次回答都有据可循。</p>
        <p v-else>连接你的文档，从一个好问题开始。</p>
        <button v-if="!currentKnowledgeBaseId" class="setup-link" @click="emit('manage-knowledge-bases')">创建或选择知识库<AgentIcon name="chevron" /></button>
        <div class="samples">
          <button v-for="sample in samples" :key="sample.title" class="sample" type="button" :disabled="!currentKnowledgeBaseId || sending" @click="onSampleClick(sample.question)">
            <AgentIcon :name="sample.icon" /><span>{{ sample.title }}</span><AgentIcon name="chevron" />
          </button>
        </div>
      </div>
      <div v-if="messages.length" class="messages">
        <article v-for="(message, index) in messages" :key="index" class="turn" :class="message.role.toLowerCase()">
          <template v-if="message.role === 'ASSISTANT'">
            <div class="assistant-label"><span class="assistant-mark"><AgentIcon name="spark" /></span><span>知序</span><span class="assistant-type">知识库助手</span></div>
            <div class="assistant-body">
              <div v-if="message.pending" class="pending" role="status"><span class="pending-orbit"><AgentIcon name="spark" /></span><span>正在检索资料并组织回答<span class="thinking-dots">…</span></span></div>
              <template v-else>
                <div class="content" :class="{ failed: message.failed }" v-html="renderAnswer(message.content)" />
                <SourceList v-if="message.sources?.length" :sources="message.sources" />
                <div v-if="!message.failed" class="answer-actions">
                  <button class="copy-button" :aria-label="copiedIndex === index ? '已复制回答' : '复制回答'" :title="copiedIndex === index ? '已复制' : '复制回答'" @click="copyAnswer(message.content, index)"><AgentIcon :name="copiedIndex === index ? 'check' : 'copy'" /><span>{{ copiedIndex === index ? '已复制' : '复制' }}</span></button>
                  <span v-if="message.tokens" class="tokens" :title="`输入 ${message.tokens.prompt} · 输出 ${message.tokens.completion}`">{{ message.tokens.total }} tokens</span>
                </div>
              </template>
            </div>
          </template>
          <div v-else class="user-bubble">{{ message.content }}</div>
        </article>
      </div>
    </div>
    <div class="composer-wrap">
      <div class="composer" :class="{ 'is-sending': sending }">
        <el-input ref="draftInput" v-model="draft" type="textarea" :autosize="{ minRows: 2, maxRows: 7 }" resize="none" aria-label="输入你的问题" :placeholder="currentKnowledgeBaseId ? '有什么想从文档中了解的？' : '先创建一个知识库，再开始对话…'" :disabled="!currentKnowledgeBaseId" @keydown="onInputKeydown" />
        <div class="composer-toolbar">
          <div class="composer-tools">
            <button class="icon-button attach-button" type="button" title="管理文档" aria-label="打开文档管理" :disabled="!currentKnowledgeBaseId" @click="emit('open-documents')"><AgentIcon name="plus" /></button>
            <span class="context-chip"><AgentIcon name="book" /><span>{{ currentKnowledgeBase?.name || '未连接知识库' }}</span></span>
          </div>
          <div class="send-tools"><span class="send-shortcut">Enter 发送</span><button class="send-button" type="button" :disabled="!currentKnowledgeBaseId || !draft.trim() || sending" :aria-label="sending ? '正在生成回答' : '发送问题'" :title="sending ? '正在生成回答' : '发送问题'" @click="onSend"><span v-if="sending" class="send-spinner" /><AgentIcon v-else name="arrow" /></button></div>
        </div>
      </div>
      <div class="composer-hint"><AgentIcon name="link" />回答基于检索到的资料，请结合来源原文核对<span class="hint-divider">·</span><span class="keyboard-hint">Shift + Enter 换行</span></div>
    </div>
  </section>
</template>

<script setup>
import { nextTick, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import AgentIcon from '../components/AgentIcon.vue'
import SourceList from '../components/SourceList.vue'
import { useWorkspace } from '../composables/useWorkspace'

const emit = defineEmits(['open-documents', 'manage-knowledge-bases'])
const { currentKnowledgeBase, currentKnowledgeBaseId, currentConversationId, messages, sending, send, startNewConversation } = useWorkspace()
const draft = ref('')
const draftInput = ref(null)
const scrollArea = ref(null)
const copiedIndex = ref(null)
let copyTimer
const samples = [
  { title: '梳理关键概念', icon: 'book', question: '请解释文档中的关键概念，并标注相关来源。' },
  { title: '查找操作步骤', icon: 'file', question: '文档中介绍了哪些操作步骤？请结合资料说明。' },
  { title: '对比不同方案', icon: 'search', question: '文档中有哪些可对比的方案？它们的主要区别是什么？' }
]

/* 先转义模型原文，只为引用编号插入受控标签。 */
const renderAnswer = (text) => {
  if (!text) return ''
  const escaped = text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  return escaped.replace(/\[(\d{1,3})\]/g, '<span class="cite">[$1]</span>')
}
const scrollToBottom = async () => {
  await nextTick()
  if (scrollArea.value) scrollArea.value.scrollTop = scrollArea.value.scrollHeight
}
watch(() => messages.value.length, scrollToBottom)
watch(() => messages.value[messages.value.length - 1]?.content, scrollToBottom)
watch(currentConversationId, () => { draft.value = ''; copiedIndex.value = null })
const doSend = async (question) => {
  const text = question?.trim()
  if (!text || sending.value) return
  if (!currentKnowledgeBaseId.value) { ElMessage.warning('请先选择或创建一个知识库'); return }
  draft.value = ''
  await send(text)
  await scrollToBottom()
  draftInput.value?.focus()
}
const onSend = () => doSend(draft.value)
const onInputKeydown = (event) => {
  // 中文输入法确认候选时也会发 Enter，不能误当成提交。
  if (event.key === 'Enter' && !event.shiftKey && !event.ctrlKey && !event.altKey && !event.metaKey && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault()
    onSend()
  }
}
const onSampleClick = (question) => {
  if (sending.value || !currentKnowledgeBaseId.value) return
  startNewConversation()
  doSend(question)
}
const copyAnswer = async (text, index) => {
  try {
    await navigator.clipboard.writeText(text)
    copiedIndex.value = index
    clearTimeout(copyTimer)
    copyTimer = setTimeout(() => { copiedIndex.value = null }, 2000)
  } catch { ElMessage.warning('无法访问剪贴板，请手动选择回答复制') }
}
onUnmounted(() => clearTimeout(copyTimer))
</script>

<style scoped>
.chat { flex: 1; display: flex; flex-direction: column; min-height: 0; }
.stream { flex: 1; min-height: 0; overflow-y: auto; overscroll-behavior: contain; padding: 22px 38px 10px; }
.messages { max-width: var(--reading-width); margin: 0 auto; padding-bottom: 28px; }
.chat.is-empty { justify-content: center; }
.is-empty .stream { flex: 0 1 auto; padding-top: 20px; overflow-y: auto; }
.welcome { max-width: var(--reading-width); margin: 0 auto; padding: 0 0 34px; text-align: center; }
.welcome-symbol { display: flex; justify-content: center; margin-bottom: 21px; color: var(--brand); font-size: 48px; }
.welcome-eyebrow { display: block; font-size: 9px; letter-spacing: 2.5px; color: var(--text-dim); margin-bottom: 17px; }
.welcome h1 { margin: 0; font-size: clamp(27px, 3.4vw, 38px); font-weight: 500; letter-spacing: -1px; line-height: 1.4; }
.welcome p { margin: 17px 0 0; font-size: 13px; color: var(--text-secondary); line-height: 1.8; }
.setup-link { display: inline-flex; align-items: center; gap: 5px; margin-top: 16px; padding: 7px 11px; border: 0; border-radius: 8px; background: var(--brand-soft); color: var(--brand); font-size: 12px; cursor: pointer; }
.samples { display: flex; justify-content: center; flex-wrap: wrap; gap: 10px; margin-top: 30px; }
.sample { display: flex; align-items: center; gap: 9px; padding: 12px 14px; border: 1px solid var(--border); border-radius: 10px; color: var(--text-secondary); background: var(--surface); font-size: 12px; cursor: pointer; transition: border-color 0.15s, transform 0.15s; }
.sample > .agent-icon:first-child { font-size: 16px; color: var(--brand); }
.sample > .agent-icon:last-child { font-size: 12px; margin-left: 10px; color: var(--text-dim); }
.sample:hover:not(:disabled) { transform: translateY(-2px); border-color: var(--border-strong); box-shadow: var(--shadow-sm); }
.sample:disabled { opacity: 0.55; }
.turn { margin-bottom: 31px; }
.turn.user { display: flex; justify-content: flex-end; padding-top: 10px; }
.user-bubble { max-width: 82%; padding: 13px 20px; border-radius: 18px 18px 5px 18px; background: var(--surface-hover); font-size: 14px; line-height: 1.85; white-space: pre-wrap; overflow-wrap: anywhere; }
.assistant-label { display: flex; align-items: center; gap: 9px; margin-bottom: 13px; font-size: 12px; font-weight: 600; }
.assistant-mark { display: inline-flex; align-items: center; justify-content: center; width: 27px; height: 27px; background: var(--brand-soft); color: var(--brand); font-size: 19px; border-radius: 8px; }
.assistant-type { font-size: 10px; color: var(--text-dim); font-weight: 400; margin-left: 2px; }
.assistant-body { padding-left: 36px; }
.content { font-size: 14px; line-height: 1.95; white-space: pre-wrap; overflow-wrap: anywhere; }
.content.failed { color: var(--danger); }
.content :deep(.cite) { display: inline-block; margin: 0 2px; padding: 0 4px; line-height: 1.5; background: var(--brand-soft); color: var(--brand); border-radius: 4px; font-size: 11px; font-weight: 600; }
.pending { display: flex; align-items: center; gap: 10px; min-height: 37px; color: var(--text-secondary); font-size: 13px; }
.pending-orbit { display: flex; color: var(--brand); animation: thinking 3s ease-in-out infinite; }
.thinking-dots { letter-spacing: 3px; }
@keyframes thinking { 50% { opacity: 0.35; transform: rotate(90deg); } }
.answer-actions { display: flex; align-items: center; gap: 15px; margin-top: 15px; }
.copy-button { display: flex; align-items: center; gap: 5px; padding: 4px 0; border: 0; background: transparent; color: var(--text-dim); font-size: 11px; cursor: pointer; }
.copy-button > .agent-icon { font-size: 14px; }
.copy-button:hover { color: var(--brand); }
.tokens { font-size: 10px; color: var(--text-dim); }
.composer-wrap { width: 100%; max-width: calc(var(--reading-width) + 76px); padding: 10px 38px 20px; margin: 0 auto; flex-shrink: 0; }
.is-empty .composer-wrap { margin: 0 auto; padding-bottom: 42px; }
.composer { border: 1px solid var(--border-strong); border-radius: 20px; background: var(--surface); padding: 15px 16px 11px; box-shadow: var(--shadow); transition: border-color 0.15s, box-shadow 0.15s; }
.composer:focus-within { border-color: var(--brand-light-3); box-shadow: var(--shadow), 0 0 0 2px var(--brand-soft); }
.composer :deep(.el-textarea__inner) { padding: 1px 4px 10px; background: transparent; box-shadow: none !important; border: 0; color: var(--text); line-height: 1.8; font-size: 14px; }
.composer :deep(.el-textarea__inner::placeholder) { color: var(--text-dim); }
.composer-toolbar, .composer-tools, .send-tools { display: flex; align-items: center; }
.composer-toolbar { justify-content: space-between; gap: 12px; margin-top: 7px; }
.composer-tools { min-width: 0; gap: 9px; }
.attach-button { width: 29px; height: 29px; border: 1px solid var(--border); border-radius: 50%; font-size: 17px; flex-shrink: 0; }
.context-chip { display: flex; align-items: center; gap: 6px; min-width: 0; padding: 6px 9px; border-radius: 6px; background: var(--surface-soft); color: var(--text-secondary); font-size: 10px; }
.context-chip .agent-icon { font-size: 13px; }
.context-chip span { max-width: 240px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.send-tools { flex-shrink: 0; gap: 13px; }
.send-shortcut { color: var(--text-dim); font-size: 10px; }
.send-button { display: inline-flex; align-items: center; justify-content: center; width: 34px; height: 34px; border: 0; border-radius: 11px; background: var(--brand); color: var(--brand-contrast); font-size: 21px; cursor: pointer; transition: background 0.15s, transform 0.15s; }
.send-button:hover:not(:disabled) { background: var(--brand-hover); transform: translateY(-1px); }
.send-button:disabled { opacity: 0.4; }
.is-sending .send-button:disabled { opacity: 0.75; }
.send-spinner { width: 16px; height: 16px; border: 2px solid var(--brand-light-3); border-top-color: var(--brand-contrast); border-radius: 50%; animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.composer-hint { display: flex; justify-content: center; align-items: center; gap: 6px; margin-top: 13px; color: var(--text-dim); font-size: 10px; text-align: center; line-height: 1.6; }
.hint-divider { margin: 0 3px; }
@media (max-height: 650px) {
  .is-empty .stream { flex: 1; }
  .welcome { padding-top: 10px; padding-bottom: 15px; }
  .welcome-symbol { margin-bottom: 10px; font-size: 32px; }
  .welcome-eyebrow { margin-bottom: 9px; }
  .samples { margin-top: 17px; }
  .is-empty .composer-wrap { padding-bottom: 15px; }
}
</style>
