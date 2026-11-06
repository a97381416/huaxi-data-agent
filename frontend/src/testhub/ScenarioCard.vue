<template>
  <div class="scenario" :class="`scenario-${stateKind}`">
    <div class="scenario-head">
      <el-tag size="small" effect="plain" class="id-tag">{{ sc.id }}</el-tag>
      <el-tag size="small" :type="prioTagType" effect="dark">{{ sc.prio }}</el-tag>
      <span class="title">{{ sc.title }}</span>
      <el-tag v-if="sc.auto" size="small" type="primary" effect="plain">可自动</el-tag>
      <span class="spacer" />
      <el-tag v-if="running || result" size="small" :type="stateTagType">{{ stateText }}</el-tag>
      <el-button
        v-if="sc.auto"
        size="small"
        type="primary"
        :loading="running"
        :disabled="running"
        @click="$emit('run', sc.id)"
      >
        运 行
      </el-button>
      <template v-else>
        <el-button size="small" type="success" plain :disabled="running" @click="$emit('mark', sc.id, 'manual-pass')">
          人工通过
        </el-button>
        <el-button size="small" type="danger" plain :disabled="running" @click="$emit('mark', sc.id, 'manual-fail')">
          不通过
        </el-button>
      </template>
      <el-button v-if="result" size="small" text @click="$emit('clear', sc.id)">清除</el-button>
    </div>

    <div class="scenario-body">
      <div class="line"><span class="label">步骤</span><span class="text">{{ sc.steps }}</span></div>
      <div class="line"><span class="label">预期</span><span class="text expected">{{ sc.expected }}</span></div>
      <div v-if="sc.note" class="line"><span class="label">备注</span><span class="text note">{{ sc.note }}</span></div>

      <div v-if="sc.code" class="code-row">
        <el-button size="small" text type="primary" @click="copy(sc.code)">复制{{ codeLabel }}</el-button>
        <pre class="code">{{ sc.code }}</pre>
      </div>
      <div v-if="sc.copy" class="copy-row">
        <el-button size="small" text type="primary" @click="copy(sc.copy)">复制提问</el-button>
        <code class="copy-text">{{ sc.copy }}</code>
      </div>

      <!-- 运行结果 -->
      <div v-if="result && !running" class="result">
        <div class="result-msg" :class="`msg-${stateKind}`">{{ result.message }}</div>

        <div v-for="(exp, i) in result.detail?.expectations || []" :key="'e' + i" class="exp" :class="exp.pass ? 'exp-pass' : 'exp-fail'">
          <span>{{ exp.pass ? '✓' : '✗' }}</span>
          <span>{{ exp.msg }}</span>
        </div>

        <el-collapse v-if="result.detail?.steps?.length" class="detail-collapse">
          <el-collapse-item :title="`请求/响应明细（${result.detail.steps.length} 步）`">
            <div v-for="(st, i) in result.detail.steps" :key="'s' + i" class="step">
              <template v-if="st.kind === 'http'">
                <div class="step-line">
                  <el-tag size="small" :type="st.status < 400 ? 'success' : 'danger'">{{ st.status }}</el-tag>
                  <code>{{ st.method }} {{ st.url }}</code>
                </div>
                <pre v-if="st.request" class="step-pre">{{ st.request }}</pre>
                <pre v-if="st.response" class="step-pre">{{ pretty(st.response) }}</pre>
              </template>
              <template v-else-if="st.kind === 'sse'">
                <div class="step-line">
                  <el-tag size="small" type="warning">SSE</el-tag>
                  <code>共 {{ st.eventCount }} 个事件</code>
                </div>
                <div class="events">
                  <div v-for="(ev, j) in st.events" :key="j" class="event">
                    <span class="event-seq">{{ ev.seq || j + 1 }}</span>
                    <el-tag size="small" :type="eventTagType(ev.type)">{{ ev.type }}</el-tag>
                    <span class="event-summary">{{ ev.summary }}</span>
                  </div>
                </div>
              </template>
            </div>
          </el-collapse-item>
        </el-collapse>

        <div class="ts">执行时间：{{ formatTs(result.ts) }}</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  sc: { type: Object, required: true },
  /** 本条用例当前结果（父组件管理，可能为 null） */
  result: { type: Object, default: null },
  running: { type: Boolean, default: false }
})

defineEmits(['run', 'mark', 'clear'])

const stateKind = computed(() => {
  if (props.running) return 'running'
  const s = props.result?.state
  if (s === 'passed' || s === 'manual-pass') return 'pass'
  if (s === 'failed' || s === 'manual-fail') return 'fail'
  if (s === 'blocked') return 'blocked'
  return 'none'
})

const stateText = computed(() => {
  if (props.running) return '运行中…'
  const s = props.result?.state
  if (s === 'passed') return '通过'
  if (s === 'failed') return '失败'
  if (s === 'blocked') return '未交付/受阻'
  if (s === 'manual-pass') return '人工通过'
  if (s === 'manual-fail') return '人工不通过'
  return ''
})

const stateTagType = computed(() => {
  if (props.running) return 'info'
  const s = props.result?.state
  if (s === 'passed' || s === 'manual-pass') return 'success'
  if (s === 'failed' || s === 'manual-fail') return 'danger'
  if (s === 'blocked') return 'warning'
  return 'info'
})

const prioTagType = computed(() => (props.sc.prio === 'P0' ? 'danger' : props.sc.prio === 'P1' ? 'warning' : 'info'))

const codeLabel = computed(() => (/^\s*(SELECT|SHOW|UPDATE|INSERT|--)/i.test(props.sc.code || '') ? 'SQL' : '命令'))

function eventTagType(type) {
  if (type === 'COMPLETE') return 'success'
  if (type === 'PAUSED') return 'info'
  if (type === 'ERROR') return 'danger'
  if (type === 'TOOL_START' || type === 'TOOL_END') return 'warning'
  return 'info'
}

function pretty(text) {
  try {
    return JSON.stringify(JSON.parse(text), null, 2).slice(0, 2500)
  } catch {
    return text
  }
}

function formatTs(ts) {
  return ts ? new Date(ts).toLocaleString('zh-CN') : '-'
}

async function copy(text) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    const ta = document.createElement('textarea')
    ta.value = text
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
    ElMessage.success('已复制')
  }
}
</script>

<style scoped>
.scenario {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 10px 14px;
  margin-bottom: 10px;
  background: #fff;
  border-left: 3px solid #dcdfe6;
}

.scenario-pass { border-left-color: #67c23a; }
.scenario-fail { border-left-color: #f56c6c; }
.scenario-blocked { border-left-color: #e6a23c; }

.scenario-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.id-tag { font-family: monospace; }

.title { font-size: 14px; font-weight: 500; color: #303133; }

.spacer { flex: 1; }

.scenario-body { margin-top: 8px; }

.line { display: flex; gap: 8px; font-size: 13px; margin-bottom: 4px; }

.label { flex: none; color: #909399; width: 32px; }

.text { color: #606266; }

.expected { color: #303133; }

.note { color: #e6a23c; }

.code-row,
.copy-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 6px;
}

.code {
  flex: 1;
  margin: 0;
  background: #fafafa;
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 12px;
  font-family: Consolas, Monaco, monospace;
  white-space: pre-wrap;
  word-break: break-all;
  color: #476582;
}

.copy-text {
  font-size: 13px;
  color: #409eff;
  background: #ecf5ff;
  border-radius: 6px;
  padding: 4px 10px;
}

.result {
  margin-top: 10px;
  border-top: 1px dashed #ebeef5;
  padding-top: 8px;
}

.result-msg { font-size: 13px; font-weight: 500; margin-bottom: 6px; }
.msg-pass { color: #67c23a; }
.msg-fail { color: #f56c6c; }
.msg-blocked { color: #e6a23c; }

.exp { display: flex; gap: 6px; font-size: 12px; margin: 2px 0; }
.exp-pass { color: #67c23a; }
.exp-fail { color: #f56c6c; }

.detail-collapse { margin-top: 6px; border: none; }

.step { margin-bottom: 10px; }

.step-line { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.step-line code { font-size: 12px; color: #606266; word-break: break-all; }

.step-pre {
  margin: 4px 0;
  background: #fafafa;
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 12px;
  font-family: Consolas, Monaco, monospace;
  white-space: pre-wrap;
  word-break: break-all;
  color: #606266;
  max-height: 220px;
  overflow-y: auto;
}

.events { max-height: 260px; overflow-y: auto; }

.event { display: flex; align-items: center; gap: 8px; margin: 3px 0; }

.event-seq { width: 36px; text-align: right; font-size: 12px; color: #c0c4cc; font-family: monospace; }

.event-summary {
  font-size: 12px;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 520px;
}

.ts { font-size: 12px; color: #c0c4cc; margin-top: 6px; }
</style>
