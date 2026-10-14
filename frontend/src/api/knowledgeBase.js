import request from './request'

export const listKnowledgeBases = () => request.get('/knowledge-bases')

export const getKnowledgeBase = (id) => request.get(`/knowledge-bases/${id}`)

export const createKnowledgeBase = (payload) => request.post('/knowledge-bases', payload)

export const updateKnowledgeBase = (id, payload) => request.put(`/knowledge-bases/${id}`, payload)

export const deleteKnowledgeBase = (id) => request.delete(`/knowledge-bases/${id}`)
