import { computed, reactive, ref } from 'vue'
import { listKnowledgeBases } from '../api/knowledgeBase'
import { ask, getConversation } from '../api/chat'

/*
 * 全应用共享的工作区状态：知识库、会话、当前消息流。
 *
 * 没有引 pinia：模块级 ref 本身就是单例，这个规模用它就够了，
 * 少一个依赖也少一层要解释的东西。
 */

const CURRENT_KB_KEY = 'rag.currentKnowledgeBaseId'
const CONVERSATIONS_KEY = 'rag.conversations'

const knowledgeBases = ref([])
const currentKnowledgeBaseId = ref(localStorage.getItem(CURRENT_KB_KEY) || null)
const conversations = ref([])
const currentConversationId = ref(null)
const messages = ref([])
const loadingKnowledgeBases = ref(false)
const sending = ref(false)

const currentKnowledgeBase = computed(
  () => knowledgeBases.value.find((item) => item.id === currentKnowledgeBaseId.value) ?? null
)

/*
 * 会话列表存在浏览器本地。
 * 后端没有「列出某个知识库全部会话」的接口——Redis 只按 conversationId 存，
 * 没有索引表，所以列表只能由前端自己记。换浏览器或清缓存就没了，
 * 真实产品需要后端补一个会话索引。
 */
const readConversationIndex = () => {
  try {
    return JSON.parse(localStorage.getItem(CONVERSATIONS_KEY) || '{}')
  } catch {
    return {}
  }
}

const loadConversations = () => {
  if (!currentKnowledgeBaseId.value) {
    conversations.value = []
    return
  }
  conversations.value = readConversationIndex()[currentKnowledgeBaseId.value] ?? []
}

const rememberConversation = (conversationId, title) => {
  if (!currentKnowledgeBaseId.value || !conversationId) {
    return
  }
  const index = readConversationIndex()
  const list = index[currentKnowledgeBaseId.value] ?? []
  const existing = list.find((item) => item.id === conversationId)
  if (existing) {
    existing.updatedAt = Date.now()
  } else {
    list.unshift({ id: conversationId, title: (title || '新会话').slice(0, 26), updatedAt: Date.now() })
  }
  index[currentKnowledgeBaseId.value] = list.slice(0, 50)
  localStorage.setItem(CONVERSATIONS_KEY, JSON.stringify(index))
  loadConversations()
}

const forgetConversation = (conversationId) => {
  const index = readConversationIndex()
  const list = index[currentKnowledgeBaseId.value] ?? []
  index[currentKnowledgeBaseId.value] = list.filter((item) => item.id !== conversationId)
  localStorage.setItem(CONVERSATIONS_KEY, JSON.stringify(index))
  loadConversations()
}

const startNewConversation = () => {
  currentConversationId.value = crypto.randomUUID()
  messages.value = []
}

/**
 * 打开一段历史会话。
 *
 * 后端只存了角色和正文，没有存引用来源，
 * 所以翻回旧会话时只有问答文本，看不到当时的来源卡片。
 */
const openConversation = async (conversationId) => {
  currentConversationId.value = conversationId
  if (!currentKnowledgeBaseId.value) {
    messages.value = []
    return
  }
  const history = await getConversation(currentKnowledgeBaseId.value, conversationId)
  messages.value = history.map((item) => ({ role: item.role, content: item.content }))
}

const loadKnowledgeBases = async () => {
  loadingKnowledgeBases.value = true
  try {
    knowledgeBases.value = await listKnowledgeBases()
    const stillExists = knowledgeBases.value.some((item) => item.id === currentKnowledgeBaseId.value)
    if (!stillExists) {
      selectKnowledgeBase(knowledgeBases.value[0]?.id ?? null)
    }
  } finally {
    loadingKnowledgeBases.value = false
  }
}

function selectKnowledgeBase(knowledgeBaseId) {
  currentKnowledgeBaseId.value = knowledgeBaseId
  if (knowledgeBaseId) {
    localStorage.setItem(CURRENT_KB_KEY, knowledgeBaseId)
  } else {
    localStorage.removeItem(CURRENT_KB_KEY)
  }
  loadConversations()
  const latest = conversations.value[0]
  if (latest) {
    openConversation(latest.id)
  } else {
    startNewConversation()
  }
}

const send = async (question) => {
  if (!currentKnowledgeBaseId.value) {
    throw new Error('请先选择或创建一个知识库')
  }
  if (!currentConversationId.value) {
    startNewConversation()
  }

  messages.value.push({ role: 'USER', content: question })
  // 后续直接修改这个对象，必须使用代理才能让回答、来源和等待状态触发视图更新。
  const answer = reactive({ role: 'ASSISTANT', content: '', pending: true })
  messages.value.push(answer)
  sending.value = true

  try {
    const result = await ask(currentKnowledgeBaseId.value, {
      question,
      conversationId: currentConversationId.value
    })
    answer.content = result.answer
    answer.sources = result.sources ?? []
    answer.tokens =
      result.totalTokens == null
        ? null
        : { prompt: result.promptTokens, completion: result.completionTokens, total: result.totalTokens }
    // 只在模型调用成功后记会话，失败的一轮不该出现在列表里
    rememberConversation(currentConversationId.value, question)
  } catch {
    // 拦截器已经弹过错误提示，这里只把气泡从「正在生成」换成失败说明
    answer.content = '（本次请求失败，具体原因见错误提示）'
    answer.failed = true
  } finally {
    answer.pending = false
    sending.value = false
  }
}

export function useWorkspace() {
  return {
    knowledgeBases,
    currentKnowledgeBaseId,
    currentKnowledgeBase,
    conversations,
    currentConversationId,
    messages,
    loadingKnowledgeBases,
    sending,
    loadKnowledgeBases,
    selectKnowledgeBase,
    loadConversations,
    forgetConversation,
    startNewConversation,
    openConversation,
    send
  }
}
