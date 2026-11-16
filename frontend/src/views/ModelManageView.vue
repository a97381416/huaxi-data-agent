<template>
  <div class="model-manage">
    <!-- 工具栏：说明 + 新建/刷新 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <span class="hint">
          对话模型走 OpenAI 兼容端点（Base URL + API Key + 模型名），管理对话页可选的大模型清单；
          增删改/启停即时热生效（下一轮对话可用），默认模型承接未选择/已失效的请求。
          凭据脱敏显示：Key 框含 *** 或留空保存时视为未修改。
        </span>
      </div>
      <el-button :loading="loading" @click="load">
        <el-icon class="btn-icon"><Refresh /></el-icon>刷新
      </el-button>
      <el-button type="primary" @click="openCreate">
        <el-icon class="btn-icon"><Plus /></el-icon>新建模型
      </el-button>
    </div>

    <!-- 模型列表 -->
    <el-table v-loading="loading" :data="rows" stripe class="model-table">
      <el-table-column label="名称 / 标识" min-width="220">
        <template #default="{ row }">
          <div class="name-cell">
            <span class="model-name">
              {{ row.name }}
              <span v-if="row.isDefault" class="default-star" title="默认模型">★</span>
            </span>
            <span class="tag-row">
              <span class="mono muted">{{ row.code }}</span>
              <el-tag size="small" :type="sourceTag(row.source).type" effect="plain">
                {{ sourceTag(row.source).label }}
              </el-tag>
            </span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="厂商" width="90">
        <template #default="{ row }">
          <span>{{ row.provider || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="端点 / 模型名" min-width="300" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="mono">{{ row.baseUrl }}</span>
          <span v-if="row.completionsPath && row.completionsPath !== '/v1/chat/completions'" class="mono muted">
            · {{ row.completionsPath }}
          </span>
          <span class="mono muted"> · {{ row.model }}</span>
        </template>
      </el-table-column>
      <el-table-column label="API Key" width="150">
        <template #default="{ row }">
          <span class="mono muted">{{ row.apiKeyMasked || '（空）' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="温度" width="70">
        <template #default="{ row }">
          <span>{{ row.temperature ?? '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="最近测试" width="150">
        <template #default="{ row }">
          <el-tooltip :content="row.lastError || ''" :disabled="!row.lastError" placement="top">
            <div class="status-stack">
              <span class="status-line" :class="row.lastError ? 'is-error' : 'is-ok'">
                <span class="dot"></span>{{ row.lastCheckTime ? (row.lastError ? '失败' : '成功') : '未测试' }}
              </span>
              <span class="muted time">{{ formatTime(row.lastCheckTime) }}</span>
            </div>
          </el-tooltip>
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
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <div class="ops-row">
            <el-button link type="primary" size="small" :loading="testingId === row.id" @click="onRowTest(row)">
              测试
            </el-button>
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-dropdown trigger="click" @command="(cmd) => onRowCommand(cmd, row)">
              <el-button link type="primary" size="small" class="more-btn">
                更多<el-icon :size="12"><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="default" :disabled="row.isDefault || !row.enabled">
                    {{ row.isDefault ? '已是默认' : '设为默认' }}
                  </el-dropdown-item>
                  <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建/编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新建模型' : `编辑模型 · ${editingName}`"
      width="640px"
      destroy-on-close
    >
      <el-form :model="form" label-width="92px">
        <el-form-item label="标识 code">
          <el-input
            v-model="form.code"
            :disabled="editingId !== null"
            maxlength="64"
            placeholder="唯一标识，如 deepseek-v3；创建后不可改（对话页选模型传它）"
          />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="form.name" maxlength="64" placeholder="如 DeepSeek V3（对话页选择器展示）" />
        </el-form-item>
        <el-form-item label="厂商">
          <el-input v-model="form.provider" maxlength="64" placeholder="如 DeepSeek / 阿里云（可选）" />
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input v-model="form.baseUrl" placeholder="OpenAI 兼容端点，如 https://api.deepseek.com" />
        </el-form-item>
        <el-form-item label="补全路径">
          <el-input
            v-model="form.completionsPath"
            maxlength="200"
            placeholder="默认 /v1/chat/completions；智谱 GLM 等 /v4 风格端点填 /chat/completions"
          />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            placeholder="编辑时留空或含 *** = 保持不变"
          />
        </el-form-item>
        <el-form-item label="模型名">
          <el-input v-model="form.model" maxlength="128" placeholder="透传给端点的 model 参数，如 deepseek-chat" />
        </el-form-item>
        <el-form-item label="温度">
          <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" />
          <span class="muted ml8">0~2，默认 0.7</span>
        </el-form-item>
        <el-form-item label="超时(ms)">
          <el-input-number v-model="form.timeoutMs" :min="1000" :max="600000" :step="1000" />
          <span class="muted ml8">连通测试读超时；对话流式不受此限制</span>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" :max="9999" :step="1" />
          <span class="muted ml8">小在前（对话页选择器顺序）</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="2"
            maxlength="500"
            placeholder="展示在对话页选择器说明行（可选）"
          />
        </el-form-item>
        <el-form-item label="关思考参数">
          <el-input
            v-model="form.thinkingOffParam"
            maxlength="180"
            placeholder='默认 {"enable_thinking":false}；GLM 风格填 {"thinking":{"type":"disabled"}}；清空 = 不支持请求级关闭'
          />
        </el-form-item>
        <el-form-item label="默认模型">
          <el-switch v-model="form.isDefault" />
          <span class="muted ml8">未选择/已失效的对话请求落到默认模型（全局唯一）</span>
        </el-form-item>
      </el-form>

      <!-- 测试连通结果反馈区 -->
      <el-alert
        v-if="testResult"
        :title="testTitle"
        :type="testResult.ok ? 'success' : 'error'"
        :closable="false"
        class="test-alert"
      >
        <div class="test-tools">{{ testResult.ok ? testResult.reply || '（模型无文本回复）' : testResult.error }}</div>
      </el-alert>

      <template #footer>
        <el-button :loading="testing" @click="onTest">测试连通</el-button>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Plus, Refresh } from '@element-plus/icons-vue'
import {
  createModel, deleteModel, listModels, setDefaultModel, testModel, toggleModel, updateModel
} from '@/api/model'

// ── 标签映射 ──
const SOURCE_TAGS = {
  yml: { label: '内置', type: 'info' },
  manual: { label: '手动', type: 'warning' }
}

function sourceTag(s) {
  return SOURCE_TAGS[s] || SOURCE_TAGS.manual
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
    rows.value = await listModels()
  } finally {
    loading.value = false
  }
}

// ── 启停（停用默认行后端自动移交默认） ──
async function onToggle(row, enabled) {
  try {
    const info = await toggleModel(row.id, enabled)
    Object.assign(row, info)
    ElMessage.success(`${row.name} 已${enabled ? '启用' : '停用'}${info.isDefault ? '（默认模型）' : ''}`)
  } catch {
    // request.js 已弹后端错误信息
  }
}

// ── 行内操作 ──
const testingId = ref(null)

/** 行内测试：空请求体 + id，后端凭据缺省回退库中值 */
async function onRowTest(row) {
  testingId.value = row.id
  try {
    const result = await testModel({}, row.id)
    if (result.ok) {
      ElMessage.success(`${row.name} 连通成功（${result.elapsedMs}ms）`)
    } else {
      ElMessage.error(`${row.name} 连通失败：${result.error}`)
    }
    await load()
  } catch {
    // request.js 已弹后端错误信息
  } finally {
    testingId.value = null
  }
}

async function onRowCommand(cmd, row) {
  if (cmd === 'default') {
    try {
      const info = await setDefaultModel(row.id)
      Object.assign(row, info)
      ElMessage.success(`默认模型已切换为 ${row.name}`)
    } catch {
      // request.js 已弹后端错误信息
    }
    return
  }
  if (cmd === 'delete') {
    try {
      await ElMessageBox.confirm(
        `删除模型「${row.name}」后对话页不再可选；若它是默认模型，默认将自动移交 sort 最小的启用模型。确认删除？`,
        '删除模型',
        { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
    await deleteModel(row.id)
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
    ? `✓ 连通成功（${testResult.value.elapsedMs}ms）`
    : `✕ 连通失败（${testResult.value.elapsedMs}ms）`
})

function emptyForm() {
  return {
    code: '',
    name: '',
    provider: '',
    baseUrl: '',
    completionsPath: '',
    apiKey: '',
    model: '',
    temperature: 0.7,
    timeoutMs: 300000,
    sort: 0,
    remark: '',
    thinkingOffParam: '',
    isDefault: false
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
    code: row.code,
    name: row.name,
    provider: row.provider || '',
    baseUrl: row.baseUrl || '',
    completionsPath: row.completionsPath || '',
    apiKey: row.apiKeyMasked || '', // 回显脱敏值；原样回传含 *** = 保持不变
    model: row.model || '',
    temperature: row.temperature ?? 0.7,
    timeoutMs: row.timeoutMs,
    sort: row.sort ?? 0,
    remark: row.remark || '',
    thinkingOffParam: row.thinkingOffParam || '', // 空 = 不支持请求级关闭；新建留空由后端落默认
    isDefault: row.isDefault
  }
  dialogVisible.value = true
}

/** 表单 → 请求体（apiKey 始终回传文本：含 *** / 空 = 保持不变） */
function buildPayload() {
  const f = form.value
  return {
    code: f.code?.trim() || null,
    name: f.name?.trim() || null,
    provider: f.provider?.trim() || null,
    baseUrl: f.baseUrl?.trim() || null,
    completionsPath: f.completionsPath?.trim() || null,
    apiKey: (f.apiKey ?? '').trim(),
    model: f.model?.trim() || null,
    temperature: f.temperature,
    timeoutMs: f.timeoutMs,
    sort: f.sort,
    remark: f.remark?.trim() || null,
    thinkingOffParam: f.thinkingOffParam !== null ? (f.thinkingOffParam?.trim() || '') : null,
    isDefault: f.isDefault
  }
}

function validateForm() {
  const f = form.value
  if (editingId.value === null && !f.code?.trim()) {
    ElMessage.warning('请填写模型标识（code）')
    return false
  }
  if (!f.name?.trim()) {
    ElMessage.warning('请填写显示名')
    return false
  }
  if (!/^https?:\/\/.+/.test((f.baseUrl || '').trim())) {
    ElMessage.warning('Base URL 必须以 http:// 或 https:// 开头')
    return false
  }
  const path = (f.completionsPath || '').trim()
  if (path && (!/^\/\S+$/.test(path) || path.length > 200)) {
    ElMessage.warning('补全路径必须以 / 开头且不含空格（留空 = 默认 /v1/chat/completions）')
    return false
  }
  if (!f.model?.trim()) {
    ElMessage.warning('请填写模型名')
    return false
  }
  if (editingId.value === null && !f.apiKey?.trim()) {
    ElMessage.warning('请填写 API Key')
    return false
  }
  return true
}

async function onTest() {
  if (!validateForm()) return
  testing.value = true
  testResult.value = null
  try {
    testResult.value = await testModel(buildPayload(), editingId.value)
  } catch (e) {
    testResult.value = { ok: false, error: e?.message || '测试失败', elapsedMs: 0 }
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
      const info = await createModel(payload)
      ElMessage.success(`模型 ${info.name} 已创建${info.isDefault ? '（默认模型）' : ''}`)
    } else {
      const info = await updateModel(editingId.value, payload)
      ElMessage.success(`模型 ${info.name} 已保存（下一轮对话生效）`)
    }
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.model-manage {
  /* 全出血：MainLayout 对 /models 去 layout-main 内边距，表格左右铺满视口 */
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

.model-table {
  width: 100%;
}

.name-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.model-name {
  font-weight: 500;
  color: #111827;
}

.default-star {
  color: #f59e0b;
  margin-left: 2px;
}

.tag-row {
  display: flex;
  gap: 4px;
  align-items: center;
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

/* 最近测试列：结果 + 时间两行堆叠，杜绝列宽折行 */
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

.status-line .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #d1d5db;
}

.status-line.is-ok .dot {
  background: #10b981;
}

.status-line.is-error .dot {
  background: #ef4444;
}

/* 操作列：flex 水平居中，保证「更多」下拉与两个按钮同一水平线 */
.ops-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

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
</style>
