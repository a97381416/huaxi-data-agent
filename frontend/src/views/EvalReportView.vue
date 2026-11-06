<template>
  <div class="eval-report">
    <!-- 左侧报告清单 -->
    <aside class="report-list">
      <div class="list-header">
        <span class="list-title">评测报告</span>
        <el-button size="small" text :icon="Refresh" @click="loadList" :loading="listLoading" />
      </div>
      <div v-if="!listLoading && reports.length === 0" class="list-empty">
        暂无报告。先运行 scripts/agent 下的评测脚本：<br />
        <code>python agent_eval_runner.py</code>
      </div>
      <div
        v-for="r in reports"
        :key="r.name"
        class="report-item"
        :class="{ active: r.name === current?.name }"
        @click="openReport(r)"
      >
        <div class="item-title">{{ r.title }}</div>
        <div class="item-meta">{{ r.modifiedAt }} · {{ formatSize(r.size) }}</div>
      </div>
    </aside>

    <!-- 右侧报告内容（markdown 渲染） -->
    <section class="report-content">
      <div v-if="contentLoading" class="content-loading">加载中…</div>
      <div v-else-if="!current" class="content-empty">选择左侧报告查看</div>
      <div v-else class="markdown-body" v-html="renderedHtml"></div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import request from '@/api/request'
import md from '@/utils/markdown'

const reports = ref([])
const current = ref(null)
const contentHtml = ref('')
const listLoading = ref(false)
const contentLoading = ref(false)

const renderedHtml = computed(() => contentHtml.value)

function formatSize(bytes) {
  if (!bytes && bytes !== 0) return ''
  if (bytes < 1024) return `${bytes} B`
  return `${(bytes / 1024).toFixed(1)} KB`
}

async function loadList() {
  listLoading.value = true
  try {
    const data = await request.get('/agent/eval/report')
    reports.value = data || []
    if (current.value) {
      // 清单刷新后当前报告可能已被删除
      current.value = reports.value.find((r) => r.name === current.value.name) || null
      if (!current.value) contentHtml.value = ''
    }
  } catch (e) {
    ElMessage.error(e.message || '报告清单加载失败')
  } finally {
    listLoading.value = false
  }
}

async function openReport(r) {
  current.value = r
  contentLoading.value = true
  try {
    const text = await request.get('/agent/eval/report/content', { params: { name: r.name } })
    contentHtml.value = md.render(text || '')
  } catch (e) {
    ElMessage.error(e.message || '报告内容加载失败')
  } finally {
    contentLoading.value = false
  }
}

onMounted(loadList)
</script>

<style scoped>
.eval-report {
  display: flex;
  gap: 16px;
  height: calc(100vh - 56px);
  padding: 16px;
}

/* 左侧清单 */
.report-list {
  width: 280px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
  background: #fff;
  overflow-y: auto;
}
.list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px 8px;
}
.list-title {
  font-weight: 600;
  font-size: 14px;
}
.list-empty {
  padding: 16px 14px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.8;
}
.list-empty code {
  background: var(--el-fill-color-light);
  padding: 1px 5px;
  border-radius: 4px;
}
.report-item {
  padding: 10px 14px;
  cursor: pointer;
  border-top: 1px solid var(--el-border-color-extra-light);
  transition: background 0.15s;
}
.report-item:hover {
  background: var(--el-fill-color-light);
}
.report-item.active {
  background: var(--el-color-primary-light-9);
}
.item-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--el-text-color-primary);
}
.item-meta {
  margin-top: 3px;
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

/* 右侧内容 */
.report-content {
  flex: 1;
  min-width: 0;
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
  background: #fff;
  padding: 20px 24px;
  overflow-y: auto;
}
.content-loading,
.content-empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

/* markdown 表格与图片约束 */
.markdown-body :deep(img) {
  max-width: 100%;
  box-sizing: border-box;
}
.markdown-body :deep(table) {
  border-collapse: collapse;
  font-size: 12px;
  display: block;
  overflow-x: auto;
  max-width: 100%;
}
.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid var(--el-border-color-light);
  padding: 5px 10px;
  white-space: nowrap;
}
.markdown-body :deep(pre) {
  background: var(--el-fill-color-light);
  padding: 12px;
  border-radius: 8px;
  overflow-x: auto;
  font-size: 12px;
}
.markdown-body :deep(h1) {
  font-size: 20px;
}
.markdown-body :deep(h2) {
  font-size: 16px;
  margin-top: 20px;
}
.markdown-body :deep(h3) {
  font-size: 14px;
}
</style>
