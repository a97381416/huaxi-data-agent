<template>
  <div class="kernel-demo">
    <el-card shadow="never" class="demo-card">
      <template #header>
        <div class="card-header">
          <span>内核演示（M1）· mini-agentx-core 全链路</span>
          <el-tag size="small" :type="statusTagType">{{ statusText }}</el-tag>
        </div>
      </template>

      <div class="query-row">
        <el-input
          v-model="query"
          type="textarea"
          :rows="2"
          placeholder="试试：用 echo 工具回声'你好' / 调用 boom 测试失败回灌 / 1+1 等于几"
          :disabled="running"
        />
        <div class="btn-group">
          <el-button type="primary" :loading="running" @click="send">发 送</el-button>
          <el-button type="danger" plain :disabled="!running" @click="stop">停 止</el-button>
        </div>
      </div>

      <div class="meta-row">
        <span>conversationId：<code>{{ conversationId || '（发送时自动生成）' }}</code></span>
        <el-link v-if="conversationId" type="primary" :underline="false" @click="newConversation" class="new-link">
          新会话
        </el-link>
      </div>
    </el-card>

    <!-- 思考区（折叠） -->
    <el-card v-if="thinkingText" shadow="never" class="demo-card">
      <template #header>
        <div class="card-header" @click="thinkingOpen = !thinkingOpen" style="cursor: pointer">
          <span>🧠 思考（THINK_TAG 解析，&lt;think&gt; 标签内容）</span>
          <el-icon><ArrowDown v-if="thinkingOpen" /><ArrowRight v-else /></el-icon>
        </div>
      </template>
      <pre v-show="thinkingOpen" class="thinking-pre">{{ thinkingText }}</pre>
    </el-card>

    <!-- 工具事件区 -->
    <el-card v-if="toolEvents.length" shadow="never" class="demo-card">
      <template #header><span>🔧 工具事件</span></template>
      <div v-for="(t, i) in toolEvents" :key="i" class="tool-item">
        <el-tag :type="t.status === 'running' ? 'warning' : t.status === 'failed' ? 'danger' : 'success'" size="small">
          {{ t.name }}
        </el-tag>
        <code class="tool-args">{{ t.arguments }}</code>
        <span class="tool-status">
          {{ t.status === 'running' ? '执行中…' : t.status === 'failed' ? '失败（错误 JSON 已回灌，循环继续）' : '完成' }}
        </span>
        <div v-if="t.result" class="tool-result">{{ t.result }}</div>
      </div>
    </el-card>

    <!-- 正文区 -->
    <el-card shadow="never" class="demo-card">
      <template #header><span>💬 回答</span></template>
      <pre class="answer-pre">{{ answerText || '（等待发送）' }}</pre>
    </el-card>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowDown, ArrowRight } from '@element-plus/icons-vue'
import request from '@/api/request'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

const query = ref('')
const conversationId = ref('')
const running = ref(false)
const terminalStatus = ref('') // '' | completed | paused | error
const thinkingText = ref('')
const thinkingOpen = ref(true)
const answerText = ref('')
const toolEvents = ref([])
let abortController = null

const statusText = computed(() => {
  if (running.value) return '执行中'
  if (terminalStatus.value === 'completed') return '已完成'
  if (terminalStatus.value === 'paused') return '已暂停（Paused）'
  if (terminalStatus.value === 'error') return '错误'
  return '空闲'
})
const statusTagType = computed(() => {
  if (running.value) return 'warning'
  if (terminalStatus.value === 'completed') return 'success'
  if (terminalStatus.value === 'paused') return 'info'
  if (terminalStatus.value === 'error') return 'danger'
  return 'info'
})

function newConversation() {
  conversationId.value = ''
  resetOutput()
}

function resetOutput() {
  thinkingText.value = ''
  answerText.value = ''
  toolEvents.value = []
  terminalStatus.value = ''
}

async function send() {
  if (!query.value.trim()) {
    ElMessage.warning('请输入问题')
    return
  }
  resetOutput()
  if (!conversationId.value) {
    conversationId.value = 'demo_conv_' + crypto.randomUUID()
  }
  running.value = true
  abortController = new AbortController()
  try {
    const resp = await fetch('/agent/demo/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', satoken: userStore.token },
      body: JSON.stringify({ query: query.value.trim(), conversationId: conversationId.value }),
      signal: abortController.signal
    })
    if (!resp.ok || !resp.body) {
      const text = await resp.text().catch(() => '')
      throw new Error(`HTTP ${resp.status} ${text.slice(0, 200)}`)
    }
    await consumeSse(resp.body)
  } catch (e) {
    if (e.name !== 'AbortError') {
      terminalStatus.value = 'error'
      ElMessage.error(e.message || '流式请求失败')
    }
  } finally {
    running.value = false
    abortController = null
  }
}

/** POST SSE 消费：按空行分帧，data: 行取 JSON（事件名在 event: 行，冗余取 type 字段） */
async function consumeSse(body) {
  const reader = body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let idx
    while ((idx = buffer.indexOf('\n\n')) >= 0) {
      const frame = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      handleFrame(frame)
    }
  }
}

function handleFrame(frame) {
  const dataLines = frame
    .split('\n')
    .filter((l) => l.startsWith('data:'))
    .map((l) => l.slice(5).trim())
  if (!dataLines.length) return
  let evt
  try {
    evt = JSON.parse(dataLines.join('\n'))
  } catch {
    return
  }
  switch (evt.type) {
    case 'THINKING':
      thinkingText.value += evt.content || ''
      break
    case 'TEXT':
      answerText.value += evt.content || ''
      break
    case 'TOOL_START': {
      const item = { name: evt.toolName, id: evt.toolCallId, arguments: evt.arguments, status: 'running', result: '' }
      const existing = toolEvents.value.find((t) => t.id === evt.toolCallId)
      if (existing) {
        existing.status = 'running'
      } else {
        toolEvents.value.push(item)
      }
      break
    }
    case 'TOOL_END': {
      const existing = toolEvents.value.find((t) => t.id === evt.toolCallId)
      if (existing) {
        existing.status = 'done'
        existing.result = evt.result || ''
      }
      break
    }
    case 'ERROR':
      answerText.value += `\n[ERROR] ${evt.message || ''}${evt.detail ? ' - ' + evt.detail : ''}\n`
      break
    case 'PAUSED':
      terminalStatus.value = 'paused'
      break
    case 'COMPLETE':
      terminalStatus.value = 'completed'
      break
  }
  // 失败工具没有 TOOL_END：下一轮事件到达时把仍在 running 的标记为 failed
  if (['TEXT', 'THINKING', 'TOOL_START', 'COMPLETE'].includes(evt.type)) {
    toolEvents.value.forEach((t) => {
      if (t.status === 'running' && evt.type !== 'TOOL_START') t.status = 'failed'
      if (t.status === 'running' && evt.type === 'TOOL_START' && t.id !== evt.toolCallId) t.status = 'failed'
    })
  }
}

async function stop() {
  if (!conversationId.value) return
  try {
    const r = await request.get('/agent/demo/stop', { params: { conversationId: conversationId.value } })
    ElMessage.info(`中断结果：stopped=${r.stopped}, hasInterruptState=${r.hasInterruptState}`)
    if (abortController) abortController.abort()
  } catch (e) {
    /* 401 等已由拦截器处理 */
  }
}
</script>

<style scoped>
.kernel-demo {
  max-width: 900px;
  margin: 0 auto;
}

.demo-card {
  margin-bottom: 14px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.query-row {
  display: flex;
  gap: 12px;
}

.btn-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.meta-row {
  margin-top: 10px;
  font-size: 13px;
  color: #909399;
  display: flex;
  gap: 12px;
  align-items: center;
}

.new-link {
  font-size: 13px;
}

.thinking-pre,
.answer-pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 14px;
  line-height: 1.7;
}

.thinking-pre {
  color: #909399;
  background: #fafafa;
  padding: 10px;
  border-radius: 6px;
  max-height: 260px;
  overflow-y: auto;
}

.tool-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
  border-bottom: 1px dashed #ebeef5;
  flex-wrap: wrap;
}

.tool-item:last-child {
  border-bottom: none;
}

.tool-args {
  font-size: 12px;
  color: #606266;
  max-width: 340px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-status {
  font-size: 12px;
  color: #909399;
}

.tool-result {
  width: 100%;
  font-size: 12px;
  color: #67c23a;
  word-break: break-all;
}
</style>
