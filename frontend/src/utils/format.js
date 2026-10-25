/** 后端返回的时间是 ISO-8601 的 UTC 字符串（Instant），这里按本地时区展示。 */
export const formatTime = (value) => {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '-'
  return date.toLocaleString('zh-CN', { hour12: false })
}

export const formatSize = (bytes) => {
  if (bytes == null) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

/**
 * 定位单位随格式变化：Excel 的分块按工作表定位，PDF 和 Word 按页定位。
 * 后端 pageNumber 的语义就是这个——文档对应的自然定位单元。
 */
export const locatorSuffix = (fileName) => {
  const name = (fileName || '').toLowerCase()
  return name.endsWith('.xlsx') ? '个工作表' : '页'
}

/** 页码可能为空（分块没有页码信息时），空的时候要显示得比 null 好看 */
export const formatPages = (start, end, fileName) => {
  if (start == null) return '位置未知'
  const suffix = locatorSuffix(fileName)
  if (end == null || end === start) return `第 ${start} ${suffix}`
  return `第 ${start}-${end} ${suffix}`
}

export const formatScore = (value) => (value == null ? '-' : Number(value).toFixed(4))

export const KNOWLEDGE_BASE_STATUS = {
  ACTIVE: { text: '启用', type: 'success' },
  DISABLED: { text: '停用', type: 'info' },
  ARCHIVED: { text: '归档', type: 'warning' }
}

export const PARSE_STATUS = {
  PENDING: { text: '待解析', type: 'info' },
  PARSING: { text: '解析中', type: 'warning' },
  COMPLETED: { text: '已解析', type: 'success' },
  FAILED: { text: '解析失败', type: 'danger' }
}
