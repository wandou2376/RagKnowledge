<template>
  <div class="retrieval">
    <header class="page-header">
      <div>
        <div class="page-title">检索调试</div>
        <div class="page-sub">
          同一个问题换检索模式，对比哪条通道召回了哪些分块。这是排查「为什么没答对」的第一步。
        </div>
      </div>
      <router-link class="back" to="/">← 回到对话</router-link>
    </header>

    <div class="body">
      <div class="panel">
        <el-form :inline="true" :model="form" @submit.prevent>
          <el-form-item label="问题">
            <el-input
              v-model="form.query"
              class="query-input"
              placeholder="例如：E-4012 的触发条件是什么"
              @keydown.enter.prevent="run"
            />
          </el-form-item>
          <el-form-item label="模式">
            <el-radio-group v-model="form.mode">
              <el-radio-button value="HYBRID">融合</el-radio-button>
              <el-radio-button value="VECTOR">向量</el-radio-button>
              <el-radio-button value="KEYWORD">关键词</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="Top-K">
            <el-input-number v-model="form.topK" :min="1" :max="50" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="loading" :disabled="!currentKnowledgeBaseId" @click="run">
              检索
            </el-button>
          </el-form-item>
        </el-form>

        <div v-if="currentKnowledgeBase" class="scope">
          检索范围：<strong>{{ currentKnowledgeBase.name }}</strong>
        </div>
        <div v-else class="scope">请先在左侧选择知识库</div>
      </div>

      <div v-if="results.length" class="explain">
        命中 {{ results.length }} 条。
        <template v-if="form.mode === 'HYBRID'">
          融合模式下主分数是 RRF 分数，<strong>只反映排名、不是相似度</strong>；每条由哪一通道召回的看后面的标签。
        </template>
        <template v-else>当前模式下主分数就是该通道的分数。</template>
      </div>

      <div v-for="(item, index) in results" :key="item.chunkId" class="hit">
        <div class="hit-head">
          <span class="rank">{{ index + 1 }}</span>
          <span class="file">{{ item.originalFileName }}</span>
          <span class="meta">{{ formatPages(item.pageNumberStart, item.pageNumberEnd, item.originalFileName) }}</span>
          <span class="meta">分块 #{{ item.chunkIndex }}</span>
          <span class="meta">{{ item.tokenCount }} token</span>
          <span class="scores">
            <span class="score primary">score {{ formatScore(item.score) }}</span>
            <span v-if="item.vectorScore != null" class="score vector">
              vector {{ formatScore(item.vectorScore) }}
            </span>
            <span v-if="item.keywordScore != null" class="score keyword">
              keyword {{ formatScore(item.keywordScore) }}
            </span>
          </span>
        </div>
        <p class="hit-content">{{ item.content }}</p>
      </div>

      <div v-if="!results.length && !loading" class="placeholder">输入问题后点检索</div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { search } from '../api/retrieval'
import { useWorkspace } from '../composables/useWorkspace'
import { formatPages, formatScore } from '../utils/format'

const { currentKnowledgeBaseId, currentKnowledgeBase } = useWorkspace()

const form = reactive({ query: '', mode: 'HYBRID', topK: 5 })
const results = ref([])
const loading = ref(false)

const run = async () => {
  if (!currentKnowledgeBaseId.value) {
    ElMessage.warning('请先选择知识库')
    return
  }
  if (!form.query.trim()) {
    ElMessage.warning('请输入问题')
    return
  }
  loading.value = true
  try {
    results.value = await search(currentKnowledgeBaseId.value, { ...form })
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.retrieval {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 14px 28px 12px;
  border-bottom: 1px solid var(--border);
  background: var(--main-bg);
}

.page-title {
  font-size: 14px;
  font-weight: 600;
}

.page-sub {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-dim);
  max-width: 620px;
}

.back {
  font-size: 12.5px;
  color: var(--brand);
  text-decoration: none;
  padding: 4px 8px;
  border-radius: var(--radius-sm);
}

.back:hover {
  background: var(--brand-soft);
}

.body {
  flex: 1;
  overflow-y: auto;
  padding: 20px 28px 32px;
  min-height: 0;
}

.panel {
  max-width: var(--reading-width);
  margin: 0 auto 16px;
  padding: 16px 18px 6px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}

.scope {
  font-size: 12px;
  color: var(--text-dim);
  padding-bottom: 12px;
}

.scope strong {
  color: var(--text-secondary);
}

.explain {
  max-width: var(--reading-width);
  margin: 0 auto 12px;
  font-size: 12.5px;
  color: var(--text-secondary);
}

.hit {
  max-width: var(--reading-width);
  margin: 0 auto 12px;
  padding: 12px 14px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
}

.hit-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 12px;
}

.rank {
  width: 20px;
  height: 20px;
  border-radius: 6px;
  background: var(--brand-soft);
  color: var(--brand);
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}

.file {
  font-weight: 600;
}

.meta {
  color: var(--text-dim);
}

.scores {
  margin-left: auto;
  display: flex;
  gap: 6px;
}

.score {
  padding: 1px 7px;
  border-radius: 5px;
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
}

.score.primary {
  background: var(--surface-soft);
  color: var(--text-secondary);
}

.score.vector {
  background: var(--ok-soft);
  color: var(--ok);
}

.score.keyword {
  background: var(--warn-soft);
  color: var(--warn);
}

.hit-content {
  margin: 9px 0 0;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 180px;
  overflow: auto;
}

.placeholder {
  max-width: var(--reading-width);
  margin: 40px auto;
  text-align: center;
  color: var(--text-dim);
  font-size: 13px;
}
.query-input { width: 380px; max-width: 100%; }
.scores { flex-wrap: wrap; }
</style>
