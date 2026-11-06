<template>
  <div class="skill-manage">
    <!-- 工具栏：说明 + 新建/上传 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <span class="hint">
          技能 = 含 SKILL.md 的目录包（可附 scripts/references）。手动新建适合纯文档型技能；
          需要附属资源的技能走 zip 上传。启用后经 Skill 工具按需加载（渐进式披露），
          停用立即从 Agent 工具清单消失。手动/上传技能可编辑、可删除；内置技能只读。
        </span>
      </div>
      <el-button type="primary" @click="openCreate">
        <el-icon class="btn-icon"><Plus /></el-icon>新建技能
      </el-button>
      <el-button :loading="uploading" @click="onUploadClick">
        <el-icon class="btn-icon"><Upload /></el-icon>上传技能 zip
      </el-button>
      <input ref="zipInputRef" type="file" accept=".zip" style="display: none" @change="onZipSelected" />
    </div>

    <!-- 技能列表 -->
    <el-table v-loading="loading" :data="rows" stripe class="skill-table">
      <el-table-column prop="name" label="技能名" min-width="200">
        <template #default="{ row }">
          <span class="skill-name">{{ row.name }}</span>
          <el-tag size="small" :type="sourceTag(row).type" effect="plain" class="ml4">
            {{ sourceTag(row).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="320" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.description">{{ row.description }}</span>
          <span v-else class="muted">（无描述）</span>
        </template>
      </el-table-column>
      <el-table-column prop="path" label="存储位置" min-width="240" show-overflow-tooltip />
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
          <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          <el-button
            v-if="row.source === 'manual' || row.source === 'upload'"
            link type="primary" size="small" @click="onEditClick(row)"
          >
            编辑
          </el-button>
          <el-button link type="primary" size="small" @click="onExport(row)">导出</el-button>
          <el-button v-if="row.source !== 'builtin'" link type="danger" size="small" @click="onDelete(row)">
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 详情抽屉：渲染效果 / 源码 双视图 -->
    <el-drawer v-model="drawerVisible" :title="detail ? `技能详情 · ${detail.name}` : '技能详情'" size="720px">
      <div v-loading="detailLoading" class="detail-body">
        <template v-if="detail">
          <div class="detail-meta">
            <el-tag size="small" :type="sourceTag(detail).type" effect="plain">{{ sourceTag(detail).label }}</el-tag>
            <el-tag size="small" :type="detail.enabled ? 'success' : 'info'" effect="plain" class="ml4">
              {{ detail.enabled ? '已启用' : '已停用' }}
            </el-tag>
          </div>
          <div v-if="detail.description" class="detail-desc">{{ detail.description }}</div>
          <div v-else class="detail-desc muted">（无描述）</div>
          <div class="detail-toolbar">
            <el-radio-group v-model="detailView" size="small">
              <el-radio-button value="render">渲染效果</el-radio-button>
              <el-radio-button value="source">源码</el-radio-button>
            </el-radio-group>
          </div>
          <div v-if="detailView === 'render'" class="skill-md" v-html="renderMd(detail.content)"></div>
          <pre v-else class="skill-raw">{{ detail.content }}</pre>
        </template>
      </div>
      <template #footer>
        <el-button type="primary" @click="onExport(detail)">导出 zip</el-button>
        <el-button v-if="detail && detail.source !== 'builtin'" type="primary" @click="onEditFromDetail">
          编辑
        </el-button>
        <el-button @click="drawerVisible = false">关闭</el-button>
      </template>
    </el-drawer>

    <!-- 新建/编辑弹窗（复用表单；编辑时技能名置灰） -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingName === null ? '新建技能' : `编辑技能 · ${editingName}`"
      width="640px"
      destroy-on-close
    >
      <el-form :model="form" label-width="80px">
        <el-form-item label="技能名">
          <el-input
            v-model="form.name"
            :disabled="editingName !== null"
            maxlength="100"
            placeholder="如 my-skill，不含空格；即 skills 目录名，创建后不可改"
          />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="2"
            maxlength="3000"
            show-word-limit
            placeholder="一句话说明技能用途与触发场景，Agent 据此判断何时加载（写入 frontmatter description）"
          />
        </el-form-item>
        <el-form-item label="正文">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="16"
            class="content-editor"
            placeholder="Markdown 正文，例如：&#10;&#10;## 使用步骤&#10;1. 先用 list_tables 查看表结构&#10;2. 用 execute_sql 执行查询&#10;&#10;无需包含 frontmatter（name/description 保存时自动生成）"
          />
        </el-form-item>
      </el-form>
      <div class="form-hint">
        {{
          editingName === null
            ? '正文为 Markdown；若粘贴的内容自带 frontmatter 头（--- 包裹），保存时会自动剥离后重新生成。'
            : '正文为完整 SKILL.md（含 --- 包裹的 frontmatter 头，可直接修改 description 等键，name 不可改）；修改上方「描述」会自动同步头内 description。'
        }}
      </div>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Upload } from '@element-plus/icons-vue'
import md from '@/utils/markdown'
import {
  createSkill, deleteSkill, exportSkillZip, getSkillContent, listSkills, toggleSkill, updateSkillContent, uploadSkillZip
} from '@/api/skill'

/** 来源 → 标签文案/颜色（builtin=仓库目录 / upload=zip 上传 / manual=管理界面手动创建） */
const SOURCE_TAGS = {
  builtin: { label: '内置', type: 'success' },
  upload: { label: '上传', type: 'primary' },
  manual: { label: '手动', type: 'warning' }
}

function sourceTag(row) {
  return SOURCE_TAGS[row.source] || SOURCE_TAGS.builtin
}

const rows = ref([])
const loading = ref(false)
const uploading = ref(false)
const zipInputRef = ref(null)

// ── 详情抽屉 ──
const drawerVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const detailView = ref('render')

// ── 新建/编辑弹窗（editingName=null 为新建模式） ──
const dialogVisible = ref(false)
const saving = ref(false)
const editingName = ref(null)
const form = ref(emptyForm())

function emptyForm() {
  return { name: '', description: '', content: '' }
}

onMounted(load)

async function load() {
  loading.value = true
  try {
    rows.value = await listSkills()
  } finally {
    loading.value = false
  }
}

// ── zip 上传（原有入口） ──
function onUploadClick() {
  zipInputRef.value?.click()
}

async function onZipSelected(e) {
  const file = e.target.files?.[0]
  e.target.value = '' // 允许重复选择同一文件
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.zip')) {
    ElMessage.warning('仅支持 .zip 技能包')
    return
  }
  uploading.value = true
  try {
    const info = await uploadSkillZip(file)
    ElMessage.success(`技能 ${info.name} 上传成功（${info.enabled ? '已启用' : '保持停用'}）`)
    await load()
  } finally {
    uploading.value = false
  }
}

async function onToggle(row, enabled) {
  try {
    await toggleSkill(row.name, enabled)
    row.enabled = enabled
    ElMessage.success(`${row.name} 已${enabled ? '启用' : '停用'}`)
  } catch {
    // 失败回滚开关（request.js 已弹后端错误信息）
    row.enabled = !enabled
  }
}

/** 导出技能 zip（blob 下载；含 SKILL.md 与附属资源，可直接改后再上传导入） */
async function onExport(row) {
  if (!row?.name) return
  try {
    await exportSkillZip(row.name)
    ElMessage.success(`技能 ${row.name} 已导出`)
  } catch {
    // request.js 已弹后端错误信息
  }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(
      `删除技能「${row.name}」将同时移除技能目录与记录，Agent 不再可加载。确认删除？`,
      '删除技能',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await deleteSkill(row.name)
  ElMessage.success('已删除')
  await load()
}

// ── 详情：渲染视图剥离 frontmatter，源码视图展示全文原文 ──
async function openDetail(row) {
  detail.value = null
  detailView.value = 'render'
  drawerVisible.value = true
  detailLoading.value = true
  try {
    detail.value = await getSkillContent(row.name)
  } finally {
    detailLoading.value = false
  }
}

/** 剥离 frontmatter 首段（--- 包裹），返回正文 */
function stripFrontmatter(text) {
  if (!text || !text.startsWith('---')) return text || ''
  const endIndex = text.indexOf('---', 3)
  if (endIndex === -1) return text
  return text.slice(endIndex + 3).replace(/^\s+/, '')
}

function renderMd(text) {
  return md.render(stripFrontmatter(text))
}

// ── 新建/编辑 ──
function openCreate() {
  editingName.value = null
  form.value = emptyForm()
  dialogVisible.value = true
}

/** 详情抽屉 → 编辑（复用已取回的全文，省一次请求） */
function onEditFromDetail() {
  if (!detail.value) return
  openEditForm(detail.value.name, detail.value.description, detail.value.content)
}

/** 表格 → 编辑（先取全文再回填） */
async function onEditClick(row) {
  try {
    const d = await getSkillContent(row.name)
    openEditForm(d.name, d.description, d.content)
  } catch {
    // request.js 已弹后端错误信息
  }
}

function openEditForm(name, description, content) {
  editingName.value = name
  // 编辑正文回填完整 SKILL.md（含 --- 包裹的 frontmatter 头），所见即所存
  form.value = { name, description: description || '', content: content || '' }
  dialogVisible.value = true
}

/** 编辑模式：描述字段 → 正文 frontmatter 的 description 行单向同步（两处保持一致） */
watch(() => form.value.description, (val) => {
  if (editingName.value === null) return
  const c = form.value.content
  if (!c || !c.startsWith('---')) return
  const end = c.indexOf('---', 3)
  if (end === -1) return
  const single = (val || '').replace(/\s*\r?\n\s*/g, ' ').trim()
  const head = c.slice(0, end)
  if (!/^description:[^\n]*$/m.test(head)) return
  form.value.content = head.replace(/^description:[^\n]*$/m, 'description: ' + single) + c.slice(end)
})

async function onSave() {
  if (editingName.value === null && !form.value.name.trim()) {
    ElMessage.warning('请填写技能名')
    return
  }
  if (!form.value.content.trim()) {
    ElMessage.warning('请填写技能正文')
    return
  }
  saving.value = true
  try {
    if (editingName.value === null) {
      const info = await createSkill({
        name: form.value.name, description: form.value.description, content: form.value.content
      })
      ElMessage.success(`技能 ${info.name} 已创建（已启用）`)
    } else {
      await updateSkillContent(editingName.value, {
        description: form.value.description, content: form.value.content
      })
      ElMessage.success(`技能 ${editingName.value} 已保存`)
    }
    dialogVisible.value = false
    drawerVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.skill-manage {
  /* 全出血：MainLayout 对 /skills 去 layout-main 内边距，表格左右铺满视口 */
  padding: 0;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
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

.skill-table {
  width: 100%;
}

.skill-name {
  font-weight: 500;
  color: #111827;
}

.ml4 {
  margin-left: 4px;
}

.muted {
  color: #9ca3af;
}

/* ── 新建/编辑弹窗 ── */
.content-editor :deep(.el-textarea__inner) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.6;
}

.form-hint {
  margin-top: 4px;
  font-size: 12px;
  color: #9ca3af;
}

/* ── 详情抽屉 ── */
.detail-body {
  min-height: 120px;
}

.detail-meta {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
}

.detail-desc {
  margin-bottom: 12px;
  font-size: 13px;
  line-height: 1.6;
  color: #6b7280;
}

.detail-toolbar {
  margin-bottom: 12px;
}

.skill-raw {
  margin: 0;
  padding: 14px;
  background: #f9fafb;
  border: 1px solid #f3f4f6;
  border-radius: 8px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  color: #374151;
}

/* ── markdown 渲染（与 ChatView .chat-md 同款视觉，浅色系、斑马纹表格、浅灰代码块） ── */
.skill-md {
  font-size: 14px;
  line-height: 1.7;
  color: #374151;
  word-break: break-word;
}

.skill-md :deep(p) {
  margin: 6px 0;
}

.skill-md :deep(h1),
.skill-md :deep(h2),
.skill-md :deep(h3),
.skill-md :deep(h4) {
  margin: 14px 0 8px;
  font-size: 15px;
  font-weight: 600;
  color: #111827;
}

.skill-md :deep(strong) {
  color: #111827;
  font-weight: 600;
}

.skill-md :deep(a) {
  color: #2563eb;
}

.skill-md :deep(img) {
  display: block;
  box-sizing: border-box;
  max-width: 100%;
  height: auto;
  margin: 10px 0;
  border: 1px solid #f3f4f6;
  border-radius: 10px;
}

.skill-md :deep(table) {
  width: 100%;
  margin: 10px 0;
  border-collapse: collapse;
  border: 1px solid #f3f4f6;
  border-radius: 8px;
  font-size: 13px;
  display: block;
  overflow-x: auto;
}

.skill-md :deep(th) {
  background: #f9fafb;
  padding: 8px 12px;
  text-align: left;
  font-weight: 600;
  color: #4b5563;
  border-bottom: 1px solid #e5e7eb;
  white-space: nowrap;
}

.skill-md :deep(td) {
  padding: 8px 12px;
  color: #4b5563;
  border-bottom: 1px solid #f3f4f6;
}

.skill-md :deep(tbody tr:nth-child(even)) {
  background: #f9fafb;
}

.skill-md :deep(tbody tr:last-child td) {
  border-bottom: none;
}

.skill-md :deep(code) {
  background: #f3f4f6;
  color: #1f2937;
  padding: 2px 6px;
  border-radius: 6px;
  font-size: 12px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.skill-md :deep(pre) {
  background: #f3f4f6;
  color: #1f2937;
  padding: 12px 14px;
  border-radius: 10px;
  overflow-x: auto;
  font-size: 12px;
  line-height: 1.6;
}

.skill-md :deep(pre code) {
  background: transparent;
  color: inherit;
  padding: 0;
}

.skill-md :deep(blockquote) {
  margin: 8px 0;
  padding: 2px 12px;
  border-left: 2px solid #e5e7eb;
  color: #6b7280;
}

.skill-md :deep(ul),
.skill-md :deep(ol) {
  padding-left: 20px;
  margin: 6px 0;
}

.skill-md :deep(hr) {
  border: none;
  border-top: 1px solid #f3f4f6;
  margin: 12px 0;
}
</style>
