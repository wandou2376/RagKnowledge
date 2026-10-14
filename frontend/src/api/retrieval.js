import request from './request'

/*
 * mode 决定检索走哪条通道：
 *   HYBRID  向量 + 关键词，按 RRF 融合（不传时的默认值）
 *   VECTOR  只用向量召回
 *   KEYWORD 只用关键词召回
 *
 * 注意返回里的 score 含义随 mode 变化：单通道时是对应通道的分数，
 * 融合时是 RRF 分数（只反映排名，不是相似度）。分渠道分数在
 * vectorScore / keywordScore 里，可能为 null。
 */
export const search = (knowledgeBaseId, payload) =>
  request.post(`/knowledge-bases/${knowledgeBaseId}/search`, payload)
