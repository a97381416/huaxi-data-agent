<template>
  <div class="mcp-manage">
    <!-- 工具栏：说明 + 新建/刷新 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <span class="hint">
          MCP 服务通过三种传输接入外部工具：Streamable HTTP / SSE（远程服务）、STDIO（服务端本地子进程，
          Windows 下命令填 npx.cmd）。启用后 ALWAYS 型经 tool_search 按需发现，联网型受对话页「联网」开关控制；
          增删改即时热生效（下一轮对话可用）。凭据脱敏显示：含 *** 的值保存时视为未修改。
        </span>
      </div>
      <el-button :loading="loading" @click="load">
        <el-icon class="btn-icon"><Refresh /></el-icon>刷新
      </el-button>
      <el-button type="primary" @click="openCreate">
        <el-icon class="btn-icon"><Plus /></el-icon>新建服务
      </el-button>
    </div>

    <!-- 服务列表 -->
    <el-table v-loading="loading" :data="rows" stripe class="mcp-table">
      <el-table-column prop="name" label="服务名" min-width="210">
        <template #default="{ row }">
          <div class="name-cell">
            <span class="server-name">{{ row.name }}</span>
            <span class="tag-row">
              <el-tag size="small" :type="transportTag(row.transport).type" effect="plain">
                {{ transportTag(row.transport).label }}
              </el-tag>
              <el-tag size="small" :type="sourceTag(row.source).type" effect="plain">
                {{ sourceTag(row.source).label }}
              </el-tag>
              <el-tag size="small" :type="row.injectMode === 'ONLINE_TOGGLE' ? 'primary' : 'info'" effect="plain">
                {{ row.injectMode === 'ONLINE_TOGGLE' ? '联网开关' : '始终注入' }}
              </el-tag>
            </span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="端点 / 命令" min-width="300" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.transport === 'STDIO'" class="mono">{{ row.command }} {{ row.args }}</span>
          <span v-else class="mono">{{ row.url }}<template v-if="row.transport === 'SSE'"> · {{ row.sseEndpoint }}</template></span>
        </template>
      </el-table-column>
      <el-table-column label="认证" width="110">
        <template #default="{ row }">
          <span v-if="row.authType === 'BEARER'">Bearer <span class="mono muted">{{ row.tokenMasked || '（空）' }}</span></span>
          <span v-else class="muted">无</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="150">
        <template #default="{ row }">
          <el-tooltip :content="row.lastError || ''" :disabled="!row.lastError" placement="top">
            <div class="status-stack">
              <span class="status-line" :class="statusClass(row.status)">
                <span class="dot"></span>{{ statusLabel(row.status) }}
              </span>
              <span class="muted time">{{ formatTime(row.lastCheckTime) }}</span>
            </div>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="工具" width="70">
        <template #default="{ row }">
          <span>{{ row.toolCount ?? '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="90">
        <template #default="{ row }">
          <el-switch
            :model-value="row.enabled"
            inline-prompt
            active-text="是"
            inactive-text="否"
            @change="(val) => onToggle(row, val)"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <div class="ops-row">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" size="small" @click="openTools(row)">工具</el-button>
            <el-dropdown trigger="click" @command="(cmd) => onRowCommand(cmd, row)">
              <el-button link type="primary" size="small" class="more-btn">
                更多<el-icon :size="12"><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="test">测试连接</el-dropdown-item>
                  <el-dropdown-item command="sync">重新拉取</el-dropdown-item>
                  <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建/编辑弹窗：传输方式单选 + 三选一动态表单 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新建 MCP 服务' : `编辑 MCP 服务 · ${editingName}`"
      width="640px"
      destroy-on-close
    >
      <el-form :model="form" label-width="92px">
        <el-form-item label="服务名称">
          <el-input
            v-model="form.name"
            :disabled="editingId !== null"
            maxlength="64"
            placeholder="唯一标识，如 mcp-gaode；创建后不可改（是运行时注册表的键）"
          />
        </el-form-item>
        <el-form-item label="传输方式">
          <el-radio-group v-model="form.transport">
            <el-radio-button value="STREAMABLE">Streamable HTTP</el-radio-button>
            <el-radio-button value="SSE">SSE（旧版）</el-radio-button>
            <el-radio-button value="STDIO">STDIO（本地进程）</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <!-- STREAMABLE -->
        <template v-if="form.transport === 'STREAMABLE'">
          <el-form-item label="服务 URL">
            <el-input v-model="form.url" placeholder="http://localhost:3033/mcp" />
          </el-form-item>
          <el-form-item label="认证方式">
            <el-radio-group v-model="form.authType">
              <el-radio value="NONE">无</el-radio>
              <el-radio value="BEARER">Bearer Token</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="form.authType === 'BEARER'" label="凭据">
            <el-input v-model="form.authToken" placeholder="编辑时留空 = 清除；含 *** = 保持不变" />
          </el-form-item>
        </template>

        <!-- SSE -->
        <template v-else-if="form.transport === 'SSE'">
          <el-form-item label="Base URL">
            <el-input v-model="form.url" placeholder="https://mcp.example.com" />
          </el-form-item>
          <el-form-item label="SSE 端点">
            <el-input v-model="form.sseEndpoint" placeholder="默认 /sse" />
          </el-form-item>
          <el-form-item label="认证方式">
            <el-radio-group v-model="form.authType">
              <el-radio value="NONE">无</el-radio>
              <el-radio value="BEARER">Bearer Token</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="form.authType === 'BEARER'" label="凭据">
            <el-input v-model="form.authToken" placeholder="编辑时留空 = 清除；含 *** = 保持不变" />
          </el-form-item>
        </template>

        <!-- STDIO -->
        <template v-else>
          <el-form-item label="启动命令">
            <el-input v-model="form.command" placeholder="如 node / python / npx.cmd（Windows 需带 .cmd 后缀）" />
          </el-form-item>
          <el-form-item label="启动参数">
            <el-input
              v-model="form.args"
              type="textarea"
              :rows="3"
              class="mono-editor"
              placeholder="每行一个参数，例如：&#10;-y&#10;@modelcontextprotocol/server-filesystem"
            />
          </el-form-item>
          <el-form-item label="环境变量">
            <el-input
              v-model="form.env"
              type="textarea"
              :rows="3"
              class="mono-editor"
              placeholder="每行一个 KEY=VALUE（密钥值保存后脱敏回显）"
            />
          </el-form-item>
          <div class="stdio-warn">⚠ STDIO 将在服务端主机启动子进程，仅限可信来源；凭据通过环境变量传入。</div>
        </template>

        <el-form-item label="工具注入">
          <el-radio-group v-model="form.injectMode">
            <el-radio value="ALWAYS">始终注入（tool_search 按需发现）</el-radio>
            <el-radio value="ONLINE_TOGGLE">受对话页「联网」开关控制</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="超时(ms)">
          <el-input-number v-model="form.timeoutMs" :min="1000" :max="300000" :step="1000" />
          <span class="muted ml8">工具调用与握手共用；STDIO 慢启动建议加大</span>
        </el-form-item>
      </el-form>

      <!-- 测试连接结果反馈区 -->
      <el-alert
        v-if="testResult"
        :title="testTitle"
        :type="testResult.ok ? 'success' : 'error'"
        :closable="false"
        class="test-alert"
      >
        <div v-if="testResult.ok" class="test-tools">{{ testResult.toolNames.join(' / ') }}</div>
        <div v-else class="test-tools">{{ testResult.error }}</div>
      </el-alert>

      <template #footer>
        <el-button :loading="testing" @click="onTest">测试连接</el-button>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 工具预览抽屉 -->
    <el-drawer
      v-model="drawerVisible"
      :title="toolsRow ? `工具清单 · ${toolsRow.name} (${tools.length})` : '工具清单'"
      size="560px"
    >
      <div v-loading="toolsLoading" class="tools-body">
        <div v-if="toolsRow" class="tools-meta">
          <span class="status-cell" :class="statusClass(toolsRow.status)">
            <span class="dot"></span>{{ statusLabel(toolsRow.status) }}
          </span>
          <span class="mono muted tools-url">
            {{ toolsRow.transport === 'STDIO' ? toolsRow.command : toolsRow.url }}
          </span>
        </div>
        <template v-if="tools.length">
          <div v-for="t in tools" :key="t.name" class="tool-item">
            <div class="tool-name mono">{{ t.name }}</div>
            <div class="tool-desc">{{ t.description || '（无描述）' }}</div>
          </div>
        </template>
        <div v-else-if="!toolsLoading" class="muted tools-empty">
          未连接或未拉取到工具——启用服务或点「重新拉取」建立连接后再看。
        </div>
      </div>
      <template #footer>
        <span class="muted">上次同步 {{ formatTime(toolsRow?.lastCheckTime) }}</span>
        <el-button type="primary" size="small" :loading="syncing" @click="onSyncFromDrawer">重新拉取</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Plus, Refresh } from '@element-plus/icons-vue'
import {
  createMcpServer, deleteMcpServer, listMcpServers, listMcpTools,
  syncMcpServer, testMcpServer, toggleMcpServer, updateMcpServer
} from '@/api/mcp'

// ── 标签映射 ──
const TRANSPORT_TAGS = {
  STREAMABLE: { label: 'STREAMABLE', type: 'primary' },
  SSE: { label: 'SSE', type: 'warning' },
  STDIO: { label: 'STDIO', type: 'danger' }
}

const SOURCE_TAGS = {
  yml: { label: '内置', type: 'info' },
  manual: { label: '手动', type: 'warning' }
}

function transportTag(t) {
  return TRANSPORT_TAGS[t] || TRANSPORT_TAGS.STREAMABLE
}

function sourceTag(s) {
  return SOURCE_TAGS[s] || SOURCE_TAGS.manual
}

function statusLabel(status) {
  if (status === 'OK') return '已连接'
  if (status === 'ERROR') return '连接失败'
  return '未连接'
}

function statusClass(status) {
  if (status === 'OK') return 'is-ok'
  if (status === 'ERROR') return 'is-error'
  return 'is-unknown'
}

function formatTime(t) {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(5, 16) // MM-dd HH:mm
}

const rows = ref([])
const loading = ref(false)

onMounted(load)

async function load() {
  loading.value = true
  try {
    rows.value = await listMcpServers()
  } finally {
    loading.value = false
  }
}

// ── 启停 ──
async function onToggle(row, enabled) {
  try {
    const info = await toggleMcpServer(row.id, enabled)
    Object.assign(row, info) // 状态/工具数随返回刷新
    ElMessage.success(`${row.name} 已${enabled ? '启用' : '停用'}（${statusLabel(info.status)}）`)
  } catch {
    // request.js 已弹后端错误信息
  }
}

// ── 行内更多操作 ──
async function onRowCommand(cmd, row) {
  if (cmd === 'test') {
    openEdit(row)
    // 打开编辑弹窗后自动触发一次测试（凭据缺省回退库中值）
    setTimeout(() => onTest(), 50)
    return
  }
  if (cmd === 'sync') {
    try {
      const info = await syncMcpServer(row.id)
      Object.assign(row, info)
      ElMessage.success(`${row.name} 重连${info.status === 'OK' ? `成功，发现 ${info.toolCount} 个工具` : '失败（见状态列）'}`)
    } catch {
      // request.js 已弹后端错误信息
    }
    return
  }
  if (cmd === 'delete') {
    try {
      await ElMessageBox.confirm(
        `删除服务「${row.name}」将关闭客户端并移除记录，Agent 不再可调用其工具。确认删除？`,
        '删除 MCP 服务',
        { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
    await deleteMcpServer(row.id)
    ElMessage.success('已删除')
    await load()
  }
}

// ── 新建/编辑弹窗 ──
const dialogVisible = ref(false)
const saving = ref(false)
const testing = ref(false)
const testResult = ref(null)
const editingId = ref(null)
const editingName = ref('')
const form = ref(emptyForm())

const testTitle = computed(() => {
  if (!testResult.value) return ''
  return testResult.value.ok
    ? `✓ 连接成功，发现 ${testResult.value.toolCount} 个工具（${testResult.value.elapsedMs}ms）`
    : `✕ 连接失败（${testResult.value.elapsedMs}ms）`
})

function emptyForm() {
  return {
    name: '',
    transport: 'STREAMABLE',
    url: '',
    sseEndpoint: '',
    command: '',
    args: '',
    env: '',
    authType: 'NONE',
    authToken: '',
    timeoutMs: 60000,
    injectMode: 'ALWAYS'
  }
}

function openCreate() {
  editingId.value = null
  editingName.value = ''
  testResult.value = null
  form.value = emptyForm()
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  editingName.value = row.name
  testResult.value = null
  form.value = {
    name: row.name,
    transport: row.transport,
    url: row.url || '',
    sseEndpoint: row.sseEndpoint || '',
    command: row.command || '',
    args: jsonToLines(row.args),
    env: jsonToEnvLines(row.env),
    authType: row.authType,
    authToken: row.tokenMasked || '', // 回显脱敏值；原样回传含 *** = 保持不变
    timeoutMs: row.timeoutMs,
    injectMode: row.injectMode
  }
  dialogVisible.value = true
}

/** JSON 数组（字符串或已解析数组）→ 每行一个参数的文本 */
function jsonToLines(json) {
  if (!json) return ''
  if (Array.isArray(json)) return json.join('\n')
  try {
    const arr = JSON.parse(json)
    return Array.isArray(arr) ? arr.join('\n') : json
  } catch {
    return json
  }
}

/**
 * env（后端返回脱敏后的对象 Map，兼容 JSON 字符串）→ 每行一个 KEY=VALUE 的文本。
 * 编辑弹窗回显用；注意后端 McpServerInfo.env 是对象而非字符串。
 */
function jsonToEnvLines(json) {
  if (!json) return ''
  let obj = json
  if (typeof json === 'string') {
    try {
      obj = JSON.parse(json)
    } catch {
      return json
    }
  }
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) {
    return typeof json === 'string' ? json : ''
  }
  return Object.entries(obj).map(([k, v]) => `${k}=${v ?? ''}`).join('\n')
}

/** 每行一个参数（容错数组/对象输入）→ JSON 数组字符串（空 → null） */
function linesToJsonArray(text) {
  let t = text
  if (Array.isArray(t)) t = t.join('\n')
  if (typeof t !== 'string') t = ''
  const arr = t.split('\n').map((s) => s.trim()).filter(Boolean)
  return arr.length ? JSON.stringify(arr) : null
}

/** 每行一个 KEY=VALUE（容错对象输入：后端 env 是 Map）→ JSON 对象字符串（空 → null） */
function linesToJsonObject(text) {
  let t = text
  if (t && typeof t === 'object' && !Array.isArray(t)) {
    t = Object.entries(t).map(([k, v]) => `${k}=${v ?? ''}`).join('\n')
  }
  if (typeof t !== 'string') t = ''
  const obj = {}
  for (const line of t.split('\n')) {
    const s = line.trim()
    if (!s) continue
    const eq = s.indexOf('=')
    if (eq <= 0) {
      ElMessage.warning(`环境变量行格式错误（应为 KEY=VALUE）：${s}`)
      throw new Error('env-line')
    }
    obj[s.slice(0, eq).trim()] = s.slice(eq + 1)
  }
  return Object.keys(obj).length ? JSON.stringify(obj) : null
}

/** 表单 → 请求体（authToken 始终回传文本：含 *** = 保持；空串 = 清除） */
function buildPayload() {
  const f = form.value
  return {
    name: f.name?.trim() || null,
    transport: f.transport,
    url: f.url?.trim() || null,
    sseEndpoint: f.sseEndpoint?.trim() || null,
    command: f.command?.trim() || null,
    args: linesToJsonArray(f.args),
    env: linesToJsonObject(f.env),
    authType: f.transport === 'STDIO' ? 'NONE' : f.authType,
    authToken: (f.authToken ?? '').trim(),
    timeoutMs: f.timeoutMs,
    injectMode: f.injectMode
  }
}

function validateForm() {
  const f = form.value
  if (editingId.value === null && !f.name?.trim()) {
    ElMessage.warning('请填写服务名称')
    return false
  }
  if (f.transport === 'STDIO') {
    if (!f.command?.trim()) {
      ElMessage.warning('STDIO 传输必须填写启动命令')
      return false
    }
  } else if (!/^https?:\/\/.+/.test((f.url || '').trim())) {
    ElMessage.warning('服务 URL 必须以 http:// 或 https:// 开头')
    return false
  }
  return true
}

async function onTest() {
  if (!validateForm()) return
  testing.value = true
  testResult.value = null
  try {
    testResult.value = await testMcpServer(buildPayload(), editingId.value)
  } catch (e) {
    // env 行格式错误等前端校验异常
    if (e?.message !== 'env-line') testResult.value = { ok: false, error: e?.message || '测试失败', elapsedMs: 0, toolCount: 0, toolNames: [] }
  } finally {
    testing.value = false
  }
}

async function onSave() {
  if (!validateForm()) return
  saving.value = true
  try {
    const payload = buildPayload()
    if (editingId.value === null) {
      const info = await createMcpServer(payload)
      ElMessage.success(`服务 ${info.name} 已创建（${statusLabel(info.status)}${info.status === 'OK' ? `，${info.toolCount} 个工具` : ''}）`)
    } else {
      const info = await updateMcpServer(editingId.value, payload)
      ElMessage.success(`服务 ${info.name} 已保存（${statusLabel(info.status)}，下一轮对话生效）`)
    }
    dialogVisible.value = false
    await load()
  } catch (e) {
    if (e?.message === 'env-line') return // 已提示
  } finally {
    saving.value = false
  }
}

// ── 工具预览抽屉 ──
const drawerVisible = ref(false)
const toolsLoading = ref(false)
const syncing = ref(false)
const toolsRow = ref(null)
const tools = ref([])

async function openTools(row) {
  toolsRow.value = row
  tools.value = []
  drawerVisible.value = true
  toolsLoading.value = true
  try {
    tools.value = await listMcpTools(row.id)
  } finally {
    toolsLoading.value = false
  }
}

async function onSyncFromDrawer() {
  if (!toolsRow.value) return
  syncing.value = true
  try {
    const info = await syncMcpServer(toolsRow.value.id)
    Object.assign(toolsRow.value, info)
    tools.value = await listMcpTools(toolsRow.value.id)
    ElMessage.success(info.status === 'OK' ? `重连成功，${tools.value.length} 个工具` : '重连失败（见状态列）')
  } catch {
    // request.js 已弹后端错误信息
  } finally {
    syncing.value = false
  }
}
</script>

<style scoped>
.mcp-manage {
  /* 全出血：MainLayout 对 /mcp 去 layout-main 内边距，表格左右铺满视口 */
  padding: 0;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding: 12px 16px;
  margin-bottom: 16px;
}

.toolbar-left {
  min-width: 0;
  flex: 1;
}

.hint {
  font-size: 12px;
  line-height: 1.6;
  color: #9ca3af;
}

.btn-icon {
  margin-right: 4px;
}

.mcp-table {
  width: 100%;
}

.name-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.server-name {
  font-weight: 500;
  color: #111827;
}

.tag-row {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}

.muted {
  color: #9ca3af;
}

.ml8 {
  margin-left: 8px;
}

/* 列表状态列：状态 + 检查时间两行堆叠，杜绝"已连接"被列宽折行 */
.status-stack {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.4;
}

.status-stack .status-line {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  white-space: nowrap;
}

.status-stack .time {
  font-size: 11px;
  white-space: nowrap;
}

.status-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.status-cell .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #d1d5db;
}

.status-cell.is-ok .dot {
  background: #10b981;
}

.status-cell.is-error .dot {
  background: #ef4444;
}

.status-cell .time {
  font-size: 11px;
}

/* 操作列：flex 水平居中，保证「更多」下拉与两个按钮同一水平线 */
.ops-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* 间距统一交给 gap，抵消 el-button 相邻默认外边距与 more-btn 旧偏移 */
.ops-row :deep(.el-button + .el-button) {
  margin-left: 0;
}

.ops-row .more-btn {
  margin-left: 0;
}

.more-btn {
  display: inline-flex;
  align-items: center;
}

/* ── 弹窗 ── */
.mono-editor :deep(.el-textarea__inner) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.6;
}

.stdio-warn {
  margin: -6px 0 12px 92px;
  font-size: 12px;
  color: #b45309;
}

.test-alert {
  margin-bottom: 4px;
}

.test-tools {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  word-break: break-all;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

/* ── 工具抽屉 ── */
.tools-body {
  min-height: 120px;
}

.tools-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.tools-url {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-item {
  padding: 10px 4px;
  border-bottom: 1px solid #f3f4f6;
}

.tool-name {
  font-weight: 600;
  color: #111827;
}

.tool-desc {
  margin-top: 2px;
  font-size: 12.5px;
  line-height: 1.6;
  color: #6b7280;
}

.tools-empty {
  padding: 20px 0;
  font-size: 13px;
}
</style>
