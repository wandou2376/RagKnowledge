<template>
  <el-dialog
    append-to-body
    :model-value="modelValue"
    title="知识库管理"
    width="720px"
    @update:model-value="close"
  >
    <el-form :inline="true" :model="form" class="kb-form" @submit.prevent>
      <el-form-item label="名称">
        <el-input v-model="form.name" maxlength="100" placeholder="例如：星云平台文档库" style="width: 200px" />
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="form.description" maxlength="500" placeholder="选填" style="width: 220px" />
      </el-form-item>
      <el-form-item v-if="editingId" label="状态">
        <el-select v-model="form.status" style="width: 110px">
          <el-option label="启用" value="ACTIVE" />
          <el-option label="停用" value="DISABLED" />
          <el-option label="归档" value="ARCHIVED" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="saving" @click="save">
          {{ editingId ? '保存修改' : '新建' }}
        </el-button>
        <el-button v-if="editingId" @click="resetForm">取消编辑</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="knowledgeBases" empty-text="还没有知识库" height="320">
      <el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip />
      <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusOf(row.status).type" size="small" effect="light">
            {{ statusOf(row.status).text }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130" align="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="edit(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <template #footer>
      <span class="muted">删除知识库会连同它下面的文档、分块和原文件一起删除。</span>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createKnowledgeBase, deleteKnowledgeBase, updateKnowledgeBase } from '../api/knowledgeBase'
import { useWorkspace } from '../composables/useWorkspace'
import { KNOWLEDGE_BASE_STATUS } from '../utils/format'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const { knowledgeBases, loadingKnowledgeBases, loadKnowledgeBases, selectKnowledgeBase } = useWorkspace()
const loading = computed(() => loadingKnowledgeBases.value)

const saving = ref(false)
const editingId = ref(null)
const form = reactive({ name: '', description: '', status: 'ACTIVE' })

const statusOf = (status) => KNOWLEDGE_BASE_STATUS[status] ?? { text: status, type: 'info' }

const resetForm = () => {
  editingId.value = null
  Object.assign(form, { name: '', description: '', status: 'ACTIVE' })
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      resetForm()
      loadKnowledgeBases()
    }
  }
)

const close = (visible) => emit('update:modelValue', visible)

const save = async () => {
  if (!form.name.trim()) {
    ElMessage.warning('名称不能为空')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await updateKnowledgeBase(editingId.value, { ...form })
      ElMessage.success('已保存')
    } else {
      const created = await createKnowledgeBase({ name: form.name, description: form.description })
      ElMessage.success('已创建')
      // 新建后直接切过去，省一步手动选择
      await loadKnowledgeBases()
      selectKnowledgeBase(created.id)
    }
    resetForm()
    await loadKnowledgeBases()
  } catch {
    // 拦截器已经提示过错误
  } finally {
    saving.value = false
  }
}

const edit = (row) => {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    description: row.description ?? '',
    status: row.status
  })
}

const remove = async (row) => {
  try {
    await ElMessageBox.confirm(
      `删除知识库「${row.name}」会连同它下面的文档、分块和原文件一起删掉，确认吗？`,
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await deleteKnowledgeBase(row.id)
  ElMessage.success('已删除')
  await loadKnowledgeBases()
}
</script>

<style scoped>
.kb-form {
  margin-bottom: 8px;
}

.kb-form :deep(.el-form-item) {
  margin-bottom: 10px;
}

.muted {
  color: var(--text-dim);
  font-size: 12px;
}
</style>
