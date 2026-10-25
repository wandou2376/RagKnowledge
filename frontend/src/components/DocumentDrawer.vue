<template>
  <el-drawer
    append-to-body
    :model-value="modelValue"
    :title="view === 'list' ? `文档管理 · ${currentKnowledgeBase?.name ?? ''}` : `分块预览 · ${chunkDocumentName}`"
    size="720px"
    @update:model-value="close"
  >
    <!-- 一、文档列表 -->
    <template v-if="view === 'list'">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="hint"
        title="上传只保存文件；解析和向量化是分开的两步。解析是同步接口，页数多或表格大的文档会等一会儿。"
      />

      <div class="toolbar">
        <el-upload :show-file-list="false" :http-request="doUpload" accept=".pdf,.docx,.xlsx">
          <el-button type="primary" :loading="uploading">上传文档</el-button>
        </el-upload>
        <span class="muted">支持 PDF / Word（.docx）/ Excel（.xlsx），单个文件不超过 50MB</span>
      </div>

      <el-table v-loading="loading" :data="documents" empty-text="还没有文档" height="calc(100vh - 230px)">
        <el-table-column prop="originalFileName" label="文件名" min-width="170" show-overflow-tooltip />
        <el-table-column label="大小" width="90">
          <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column label="解析状态" width="130">
          <template #default="{ row }">
            <el-tag :type="statusOf(row.parseStatus).type" size="small" effect="light">
              {{ statusOf(row.parseStatus).text }}
            </el-tag>
            <el-tooltip v-if="row.errorMessage" :content="row.errorMessage" placement="top">
              <span class="warn-mark">!</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" align="right">
          <template #default="{ row }">
            <el-button link type="primary" :loading="busyId === row.id" @click="doParse(row)">解析</el-button>
            <el-button link type="primary" :loading="busyId === row.id" @click="doEmbed(row)">向量化</el-button>
            <el-button link type="primary" @click="openChunks(row)">分块</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- 二、分块预览 -->
    <template v-else>
      <div class="toolbar">
        <el-button @click="view = 'list'">← 返回文档列表</el-button>
        <span v-if="lastParse" class="muted">
          共 {{ lastParse.pageCount }}{{ locatorSuffix(chunkDocumentName) }}，切出 {{ lastParse.chunkCount }} 个分块，
          去重跳过 {{ lastParse.duplicateChunkCount }} 个
        </span>
      </div>
      <el-table v-loading="chunksLoading" :data="chunks" empty-text="还没有分块" height="calc(100vh - 230px)">
        <el-table-column prop="chunkIndex" label="#" width="60" />
        <el-table-column label="位置" width="120">
          <template #default="{ row }">{{ formatPages(row.pageNumberStart, row.pageNumberEnd, chunkDocumentName) }}</template>
        </el-table-column>
        <el-table-column prop="tokenCount" label="token" width="70" />
        <el-table-column prop="content" label="内容" min-width="380">
          <template #default="{ row }">
            <div class="chunk-content">{{ row.content }}</div>
          </template>
        </el-table-column>
      </el-table>
    </template>
  </el-drawer>
</template>

<script setup>
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteDocument,
  embedDocument,
  listChunks,
  listDocuments,
  parseDocument,
  uploadDocument
} from '../api/document'
import { useWorkspace } from '../composables/useWorkspace'
import { formatPages, formatSize, locatorSuffix, PARSE_STATUS } from '../utils/format'

const MAX_UPLOAD_BYTES = 50 * 1024 * 1024
const SUPPORTED_EXTENSIONS = ['.pdf', '.docx', '.xlsx']

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'changed'])

const { currentKnowledgeBaseId, currentKnowledgeBase } = useWorkspace()

const view = ref('list')
const documents = ref([])
const loading = ref(false)
const uploading = ref(false)
const busyId = ref(null)

const chunks = ref([])
const chunksLoading = ref(false)
const chunkDocumentName = ref('')
const lastParse = ref(null)

const statusOf = (status) => PARSE_STATUS[status] ?? { text: status, type: 'info' }

const load = async () => {
  if (!currentKnowledgeBaseId.value) {
    documents.value = []
    return
  }
  loading.value = true
  try {
    documents.value = await listDocuments(currentKnowledgeBaseId.value)
    emit('changed')
  } finally {
    loading.value = false
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      view.value = 'list'
      load()
    }
  }
)

watch(currentKnowledgeBaseId, () => {
  if (props.modelValue) {
    view.value = 'list'
    load()
  }
})

const close = (visible) => emit('update:modelValue', visible)

const doUpload = async ({ file }) => {
  // 后端也会校验（包括压缩包内部结构），这里先拦一道只是为了少一次无谓的往返
  const name = (file.name || '').toLowerCase()
  if (!SUPPORTED_EXTENSIONS.some((extension) => name.endsWith(extension))) {
    ElMessage.error('只支持 PDF、Word（.docx）和 Excel（.xlsx）文件')
    return
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    ElMessage.error('文件超过 50MB 上限')
    return
  }
  uploading.value = true
  try {
    await uploadDocument(currentKnowledgeBaseId.value, file)
    ElMessage.success('上传成功，接着点「解析」')
    await load()
  } finally {
    uploading.value = false
  }
}

const doParse = async (row) => {
  busyId.value = row.id
  try {
    lastParse.value = await parseDocument(row.id)
    ElMessage.success(`解析完成，得到 ${lastParse.value.chunkCount} 个分块`)
    await load()
  } finally {
    busyId.value = null
  }
}

const doEmbed = async (row) => {
  busyId.value = row.id
  try {
    const result = await embedDocument(row.id)
    ElMessage.success(`向量化完成：本次 ${result.embeddedCount} 条，已跳过 ${result.alreadyEmbeddedCount} 条`)
  } finally {
    busyId.value = null
  }
}

const openChunks = async (row) => {
  chunkDocumentName.value = row.originalFileName
  view.value = 'chunks'
  chunksLoading.value = true
  try {
    chunks.value = await listChunks(row.id)
  } finally {
    chunksLoading.value = false
  }
}

const remove = async (row) => {
  try {
    await ElMessageBox.confirm(
      `删除「${row.originalFileName}」会同时删掉它的分块和原文件，确认吗？`,
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await deleteDocument(row.id)
  ElMessage.success('已删除')
  await load()
}
</script>

<style scoped>
.hint {
  margin-bottom: 12px;
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.muted {
  color: var(--text-dim);
  font-size: 12px;
}

.warn-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 15px;
  height: 15px;
  margin-left: 5px;
  border-radius: 50%;
  background: var(--warn);
  color: var(--brand-contrast);
  font-size: 11px;
  font-weight: 700;
  cursor: help;
  vertical-align: middle;
}

.chunk-content {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12.5px;
  line-height: 1.65;
  max-height: 200px;
  overflow: auto;
  color: var(--text-secondary);
}
</style>
