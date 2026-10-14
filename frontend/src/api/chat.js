import request from './request'

/*
 * conversationId 由前端生成：传了就有多轮记忆，不传就是单轮问答。
 * 后端只把它当成不透明 key，所以用 UUID 就够了。
 */
export const ask = (knowledgeBaseId, payload) =>
  request.post(`/knowledge-bases/${knowledgeBaseId}/chat`, payload)

export const getConversation = (knowledgeBaseId, conversationId) =>
  request.get(`/knowledge-bases/${knowledgeBaseId}/conversations/${conversationId}`)
