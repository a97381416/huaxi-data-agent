<template>
  <div class="flex h-full flex-col p-4">
    <section class="flex min-h-0 flex-1 flex-col overflow-hidden rounded-xl border border-gray-100 bg-white shadow-sm">
      <!-- 头部：引擎信息 + 级别筛选 + 刷新 -->
      <div class="flex shrink-0 items-center justify-between border-b border-gray-50 px-3 py-2">
        <span class="text-sm font-medium text-gray-700">
          检测规则管理
          <span class="ml-2 text-xs font-normal text-gray-400">
            引擎 {{ version || '—' }} · 共 {{ rules.length }} 条 · 启用 {{ enabledCount }} 条
          </span>
        </span>
        <div class="flex items-center gap-2">
          <el-radio-group v-model="severityFilter" size="small">
            <el-radio-button value="">全部</el-radio-button>
            <el-radio-button value="high">异常</el-radio-button>
            <el-radio-button value="medium">待人工确认</el-radio-button>
            <el-radio-button value="low">风险提示</el-radio-button>
          </el-radio-group>
          <el-button size="small" :loading="loading" @click="load">
            <el-icon class="mr-0.5"><Refresh /></el-icon>刷新
          </el-button>
        </div>
      </div>

      <!-- 规则表格（一行 = 一条检测规则；按分类分组排序：工具 → 最终回答 → ReAct） -->
      <div class="min-h-0 flex-1 overflow-auto p-3">
        <el-table v-loading="loading" :data="sortedRules" size="small">
          <!-- 示例展开行：MD 渲染（场景 + 证据代码块 + 命中级别），代码块带语言标签/复制 -->
          <el-table-column type="expand" width="42">
            <template #default="{ row }">
              <div
                v-if="exampleOf(row.ruleId)"
                class="example-md mx-2 my-1 text-xs leading-5 text-gray-600"
                @click="onExampleClick"
                v-html="renderExample(row.ruleId)"
              />
              <div v-else class="px-4 py-2 text-xs text-gray-300">暂无示例</div>
            </template>
          </el-table-column>
          <el-table-column label="分类" width="90">
            <template #default="{ row }">
              <span class="rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-600">{{ categoryCn(row.ruleId) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="规则" width="180">
            <template #default="{ row }">
              <span class="font-mono text-xs text-gray-600">{{ row.ruleId }}</span>
              <div class="truncate text-xs text-gray-400" :title="row.title">{{ row.title }}</div>
            </template>
          </el-table-column>
          <el-table-column label="级别" width="70">
            <template #default="{ row }">
              <span :class="severityClass(row.severity)">{{ severityCn(row.severity) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="220">
            <template #default="{ row }">
              <span class="text-xs leading-5 text-gray-500">{{ row.description }}</span>
            </template>
          </el-table-column>
          <!-- 备注：人工维护的说明留痕（为什么调阈值/关规则），不影响检测判定 -->
          <el-table-column label="备注" min-width="160">
            <template #default="{ row }">
              <el-input
                v-model="row.remark"
                type="textarea"
                :autosize="{ minRows: 1, maxRows: 4 }"
                size="small"
                class="param-textarea"
                placeholder="点击填写备注"
                @change="(nv) => saveRemark(row, nv)"
              />
            </template>
          </el-table-column>
          <el-table-column label="启用" width="70">
            <template #default="{ row }">
              <el-switch v-model="row.enabled" size="small" @change="saveRule(row, { enabled: row.enabled })" />
            </template>
          </el-table-column>
          <!-- 阈值：每个指标一行，中文（英文键）标签；map 型参数 JSON 文本域编辑（V2） -->
          <el-table-column label="阈值" min-width="300">
            <template #default="{ row }">
              <div v-if="Object.keys(row.params || {}).length" class="flex flex-col gap-1.5">
                <div v-for="(val, key) in row.params" :key="key" class="flex items-start gap-1.5 text-xs text-gray-500">
                  <span class="mt-1.5 w-[160px] shrink-0 leading-4" :title="key">{{ paramLabel(key) }}</span>
                  <el-input-number
                    v-if="typeof val === 'number'"
                    :model-value="val"
                    size="small"
                    :controls="false"
                    class="!w-[110px]"
                    @change="(nv) => saveRule(row, { params: { [key]: nv } }, key, nv)"
                  />
                  <el-input
                    v-else-if="isMapVal(val)"
                    :model-value="mapText(val)"
                    type="textarea"
                    :autosize="{ minRows: 1, maxRows: 8 }"
                    size="small"
                    class="!w-[340px] param-textarea font-mono"
                    placeholder='JSON，如 {"工具名": 5000}'
                    @change="(nv) => saveMapParam(row, key, nv)"
                  />
                  <el-input
                    v-else
                    :model-value="paramText(val)"
                    type="textarea"
                    :autosize="{ minRows: 1, maxRows: 6 }"
                    size="small"
                    class="!w-[300px] param-textarea"
                    placeholder="逗号分隔"
                    @change="(nv) => saveRule(row, { params: { [key]: parseParamText(nv, val) } }, key, nv)"
                  />
                </div>
              </div>
              <span v-else class="text-xs text-gray-300">—</span>
            </template>
          </el-table-column>
          <el-table-column label="最近改动" width="170">
            <template #default="{ row }">
              <span v-if="row.updatedAt" class="text-xs text-gray-400">
                {{ row.updatedBy || '—' }} · {{ row.updatedAt.slice(0, 19) }}
              </span>
              <span v-else class="text-xs text-gray-300">代码默认</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link size="small" @click="resetRule(row)">恢复默认</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="没有符合筛选条件的规则" :image-size="70" />
          </template>
        </el-table>
      </div>

      <!-- 底部提示 -->
      <div class="shrink-0 border-t border-gray-50 px-3 py-2 text-[11px] text-gray-400">
        开关/阈值改动落库即时生效（影响后续检测，历史命中不自动重算）；列表型参数用逗号分隔；map 型参数（如 perToolMs/requiredArgs/apiTools）用 JSON 编辑，格式错误不落库；备注仅作说明留痕，不影响检测判定；保存后可引导对近 7 天历史会话重刷 v2 检测。「恢复默认」回退代码默认开关与阈值。改动在配置表留痕（updated_by/updated_at）可审计。
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { fetchDetectRules, updateDetectRule, rerunDetect } from '@/api/monitor'
import mdRender from '@/utils/markdown'
import { ruleExample } from './ruleExamples'

/**
 * 规则管理（监控中心第 4 个页签）：异常检测规则注册表 table 化 web 管理——
 * 开关/阈值编辑/恢复默认（D7 弹层的独立页版本，接口同 /monitor/detect/rules）。
 * 改后即时生效免重启；分类（工具/最终回答/ReAct）分组排序 + severity 筛选；
 * 阈值区每指标一行、中文（英文键）标签，长列表参数不挤压。
 */

// ==================== 分类（ruleId 前缀 → 中文标签） ====================

/** 分类展示顺序：工具 → 技能 → 最终回答 → ReAct（同一分类内按 ruleId 排） */
const CATEGORY_ORDER = { TOOL: 0, SKILL: 1, ANSWER: 2, REACT: 3 }
const CATEGORY_CN = { TOOL: '工具', SKILL: '技能', ANSWER: '最终回答', REACT: 'ReAct' }

function categoryOf(ruleId) {
  return String(ruleId || '').split('-')[0].toUpperCase()
}

function categoryCn(ruleId) {
  return CATEGORY_CN[categoryOf(ruleId)] || '其他'
}

// ==================== 阈值参数中文名（英文键 → 中文指标名） ====================

/** 与后端 ReactRules/ToolRules/SkillRules/AnswerRules 的 defaultParams 键一一对应；未知键回退显示原键名 */
const PARAM_CN = {
  maxRounds: '最大执行轮数',
  repeatCalls: '重复调用次数',
  failStreak: '连续失败轮数',
  noProgressRounds: '无进展窗口轮数',
  failKeywords: '失败特征词',
  docToolNames: '文档类工具名（豁免）',
  successMarkers: '成功标记前缀（豁免）',
  obsTruncateChars: '观察截断字符数',
  defaultMs: '默认耗时上限（毫秒）',
  perToolMs: '按工具耗时阈值（毫秒）',
  maxRoundMs: '单轮耗时上限（毫秒·旧）',
  expectedOutput: '按工具期望输出类型',
  sqlToolKeywords: 'SQL 工具特征词',
  writeWhitelist: '写操作白名单用户',
  writeAllowedUsers: '写操作白名单用户（旧）',
  statKeywords: '统计类问题关键词',
  statQuestionKeywords: '统计类问题关键词（旧）',
  emptyPatterns: '空结果标记',
  emptyMarkers: '空结果标记（旧）',
  claimRegex: '结论数字句式',
  noDataWords: '无数据声明词',
  deterministicRegex: '确定性结论正则',
  denyWords: '否认/失败词',
  statFields: '统计数字字段名',
  statKeys: '统计数字字段名（旧）',
  conclusionPatterns: '统计结论数字模式',
  requiredArgs: '按工具必填参数',
  apiTools: 'API 业务码约定',
  skillToolName: '技能加载工具名',
  failPatterns: '失败特征模式'
}

function paramLabel(key) {
  return PARAM_CN[key] ? `${PARAM_CN[key]}（${key}）` : key
}

// ==================== 数据加载与筛选 ====================

const loading = ref(false)
const version = ref('')
const rules = ref([])
const severityFilter = ref('')

const sortedRules = computed(() => {
  const list = severityFilter.value
    ? rules.value.filter((r) => r.severity === severityFilter.value)
    : [...rules.value]
  return list.sort((a, b) => {
    const oa = CATEGORY_ORDER[categoryOf(a.ruleId)] ?? 99
    const ob = CATEGORY_ORDER[categoryOf(b.ruleId)] ?? 99
    return oa !== ob ? oa - ob : String(a.ruleId).localeCompare(String(b.ruleId))
  })
})

const enabledCount = computed(() => rules.value.filter((r) => r.enabled).length)

async function load() {
  loading.value = true
  try {
    const data = await fetchDetectRules()
    version.value = data?.version || ''
    rules.value = data?.rules || []
  } catch {
    ElMessage.error('规则注册表获取失败')
  } finally {
    loading.value = false
  }
}

// ==================== 规则改动（开关/阈值/恢复默认） ====================

/** 保存单条规则改动（开关/单个阈值键；PUT 后用返回的合并配置回填该行，并引导重刷近 7 天） */
async function saveRule(row, payload, paramKey, paramVal) {
  try {
    const updated = await updateDetectRule(row.ruleId, payload)
    Object.assign(row, updated)
    ElMessage.success(`${row.ruleId} 已更新（影响后续检测）`)
    promptRerun(row)
  } catch (e) {
    // 失败回滚该行显示（重新拉注册表最稳）
    await load()
    ElMessage.error(e?.message || `${row.ruleId} 更新失败${paramKey ? `（${paramKey}=${paramVal}）` : ''}`)
  }
}

/**
 * 保存备注（仅说明留痕，不影响检测判定，不引导重刷）；失败重新拉注册表回滚该行显示。
 */
async function saveRemark(row, val) {
  try {
    const updated = await updateDetectRule(row.ruleId, { remark: val ?? '' })
    Object.assign(row, updated)
    ElMessage.success(`${row.ruleId} 备注已保存`)
  } catch (e) {
    await load()
    ElMessage.error(e?.message || `${row.ruleId} 备注保存失败`)
  }
}

/**
 * 保存后引导（V2 §6.3）：是否对近 7 天历史会话重刷 v2 检测
 * （预填 detectorVersionLt=v2 + from=now-7d；只读回放，不重放工具）。
 */
async function promptRerun(row) {
  try {
    await ElMessageBox.confirm(
      `${row.ruleId} 配置已生效。是否对近 7 天会话重刷检测（按 v2 引擎只读回放历史 Trace）？`,
      '重刷历史检测',
      { confirmButtonText: '重刷近 7 天', cancelButtonText: '暂不', type: 'info' }
    )
  } catch {
    return
  }
  try {
    const from = new Date(Date.now() - 7 * 24 * 3600 * 1000)
    const pad = (n) => String(n).padStart(2, '0')
    const fromStr = `${from.getFullYear()}-${pad(from.getMonth() + 1)}-${pad(from.getDate())} 00:00:00`
    const data = await rerunDetect({ detectorVersionLt: 'v2', from: fromStr, limit: 1000 })
    ElMessage.success(`重刷受理 ${data?.accepted ?? 0} 条，跳过 ${data?.skipped ?? 0} 条`)
  } catch (e) {
    ElMessage.error(e?.message || '重刷受理失败')
  }
}

// ==================== map 型参数（V2）：JSON 文本域编辑 + 校验 ====================

function isMapVal(val) {
  return val !== null && typeof val === 'object' && !Array.isArray(val)
}

function mapText(val) {
  try {
    return JSON.stringify(val)
  } catch {
    return String(val)
  }
}

/** map 参数 JSON 校验保存：非法 JSON 报错不落库（该行显示由 load 恢复） */
async function saveMapParam(row, key, text) {
  let parsed
  try {
    parsed = JSON.parse((text || '').trim() || '{}')
  } catch {
    await load()
    ElMessage.error(`${key} 不是合法 JSON，未保存`)
    return
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    await load()
    ElMessage.error(`${key} 需为 JSON 对象（如 {"工具名": [...]}），未保存`)
    return
  }
  await saveRule(row, { params: { [key]: parsed } }, key, text)
}

/** 恢复默认 = 开关回代码默认 + 阈值全量回默认（覆盖配置表行） */
async function resetRule(row) {
  await saveRule(row, { enabled: row.defaultEnabled, params: row.defaultParams || {} })
}

/** 列表型参数渲染/提交（逗号分隔文本 ⇄ 数组） */
function paramText(val) {
  return Array.isArray(val) ? val.join(',') : String(val)
}

function parseParamText(text, original) {
  const raw = (text || '').trim()
  if (Array.isArray(original)) return raw ? raw.split(',').map((s) => s.trim()).filter(Boolean) : []
  return raw
}

/** V2-D9 三档口径：异常(红) / 待人工确认(橙) / 风险提示(黄) */
function severityCn(sev) {
  return { high: '异常', medium: '待人工确认', low: '风险提示' }[sev] || sev
}

function severityClass(sev) {
  return { high: 'text-red-600', medium: 'text-orange-500', low: 'text-yellow-600' }[sev] || ''
}

// ==================== 规则示例（展开行 MD 渲染） ====================

function exampleOf(ruleId) {
  return ruleExample(ruleId)
}

/**
 * 示例 markdown → HTML；fenced 代码块包一层头部（语言标签 + 复制按钮），
 * 复制经展开容器上的委托事件处理（v-html 内容无法直接绑 Vue 事件）。
 */
function renderExample(ruleId) {
  const html = mdRender.render(ruleExample(ruleId))
  return html
    .replace(/<pre><code class="language-([\w-]+)"[^>]*>/g,
      (_, lang) => codeOpen(lang))
    .replace(/<pre><code>/g, () => codeOpen('文本'))
    .replace(/<\/code><\/pre>/g, '</pre></div>')
}

function codeOpen(lang) {
  return `<div class="md-code"><div class="md-code-head"><span class="md-code-lang">${lang}</span>` +
    `<button type="button" class="md-code-copy">复制</button></div><pre class="md-code-pre">`
}

/** 展开区内点击代理：只处理 .md-code-copy（复制对应代码块文本） */
async function onExampleClick(e) {
  const btn = e.target.closest('.md-code-copy')
  if (!btn) return
  const pre = btn.closest('.md-code')?.querySelector('pre')
  try {
    await navigator.clipboard.writeText(pre?.innerText || '')
    btn.textContent = '已复制'
    setTimeout(() => { btn.textContent = '复制' }, 1200)
  } catch {
    ElMessage.warning('复制失败，请手动复制')
  }
}

onMounted(load)
</script>

<style scoped>
/* 阈值文本域（autosize 1~6 行）：收紧单行内边距，与数字输入框视觉对齐 */
.param-textarea :deep(.el-textarea__inner) {
  min-height: 24px;
  padding-top: 4px;
  padding-bottom: 4px;
  line-height: 1.5;
}

/* ── 规则示例（展开行）markdown 样式 ── */
.example-md :deep(p) {
  margin: 4px 0;
}
.example-md :deep(strong) {
  color: #111827;
  font-weight: 600;
}
.example-md :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  color: #374151;
}
/* 行内 code 与代码块内 code 区分：行内加浅底 */
.example-md :deep(p code) {
  background: #f3f4f6;
  border-radius: 4px;
  padding: 0 4px;
}
/* fenced 代码块：头部（语言标签 + 复制）+ 浅灰代码体，同对话气泡代码块观感 */
.example-md :deep(.md-code) {
  margin: 8px 0;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
  background: #f9fafb;
}
.example-md :deep(.md-code-head) {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 4px 10px;
  border-bottom: 1px solid #f3f4f6;
  background: #f3f4f6;
  font-size: 11px;
  color: #6b7280;
}
.example-md :deep(.md-code-lang) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.example-md :deep(.md-code-copy) {
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 11px;
  color: #6b7280;
  padding: 2px 4px;
}
.example-md :deep(.md-code-copy:hover) {
  color: #2563eb;
}
.example-md :deep(.md-code pre) {
  margin: 0;
  padding: 10px 12px;
  overflow-x: auto;
  line-height: 1.6;
}
</style>
