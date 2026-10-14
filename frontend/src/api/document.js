import request from './request'

export const listDocuments = (knowledgeBaseId) =>
  request.get(`/knowledge-bases/${knowledgeBaseId}/documents`)

export const uploadDocument = (knowledgeBaseId, file) => {
  const form = new FormData()
  // 后端 multipart 的字段名固定是 file，不能改
  form.append('file', file)
  return request.post(`/knowledge-bases/${knowledgeBaseId}/documents`, form)
}

export const getDocument = (documentId) => request.get(`/documents/${documentId}`)

export const deleteDocument = (documentId) => request.delete(`/documents/${documentId}`)

export const listChunks = (documentId) => request.get(`/documents/${documentId}/chunks`)

export const parseDocument = (documentId) => request.post(`/documents/${documentId}/parse`)

export const embedDocument = (documentId) => request.post(`/documents/${documentId}/embed`)
