<template>
  <div class="user-manage">
    <!-- 工具栏：搜索 + 新建 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <el-input
          v-model="keyword"
          placeholder="搜索用户名 / 昵称"
          clearable
          class="search-input"
          @keyup.enter="onSearch"
          @clear="onSearch"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" plain @click="onSearch">搜索</el-button>
      </div>
      <el-button type="primary" @click="openCreate">
        <el-icon class="btn-icon"><Plus /></el-icon>新建用户
      </el-button>
    </div>

    <!-- 用户列表 -->
    <el-table v-loading="loading" :data="rows" stripe class="user-table">
      <el-table-column prop="id" label="ID" width="90" />
      <el-table-column prop="username" label="用户名" min-width="120">
        <template #default="{ row }">
          <span class="username">{{ row.username }}</span>
          <el-tag v-if="row.id === 1" size="small" type="warning" effect="plain" class="ml4">内置</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="nickname" label="昵称" min-width="110" />
      <el-table-column label="角色" min-width="200">
        <template #default="{ row }">
          <template v-if="row.roles?.length">
            <el-tag v-for="r in row.roles" :key="r.id" size="small" class="role-tag">
              {{ r.name }}
              <span class="scope-badge">{{ scopeLabel(r.dataScope) }}</span>
            </el-tag>
          </template>
          <span v-else class="muted">未分配</span>
        </template>
      </el-table-column>
      <el-table-column label="部门" min-width="180">
        <template #default="{ row }">
          <template v-if="row.depts?.length">
            <el-tag
              v-for="d in row.depts"
              :key="d.id"
              size="small"
              type="info"
              effect="plain"
              class="role-tag"
            >
              {{ d.name }}
            </el-tag>
          </template>
          <span v-else class="muted">未挂载</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-switch
            :model-value="row.status === 'ACTIVE'"
            :disabled="isProtectedAdmin(row)"
            inline-prompt
            active-text="启用"
            inactive-text="禁用"
            @change="(val) => onToggleStatus(row, val)"
          />
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" size="small" @click="openResetPwd(row)">重置密码</el-button>
          <el-button
            link
            type="danger"
            size="small"
            :disabled="String(row.id) === '1'"
            @click="onDelete(row)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="load"
        @size-change="load"
      />
    </div>

    <!-- 新建/编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑用户' : '新建用户'"
      width="560px"
      destroy-on-close
    >
      <el-form :model="form" label-width="90px">
        <el-form-item label="用户名" required>
          <el-input
            v-model="form.username"
            :disabled="!!editingId"
            placeholder="登录用户名（创建后不可修改）"
            maxlength="64"
          />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickname" placeholder="展示用昵称" maxlength="64" />
        </el-form-item>
        <el-form-item :label="editingId ? '重置密码' : '初始密码'" :required="!editingId">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :placeholder="editingId ? '留空表示不修改密码' : '登录密码'"
          />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.roleIds" multiple placeholder="选择角色（可多选）" class="w-full">
            <el-option
              v-for="r in roleOptions"
              :key="r.id"
              :value="r.id"
              :label="`${r.name}（${scopeLabel(r.dataScope)}）`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="部门">
          <el-tree-select
            v-model="form.deptIds"
            :data="deptTreeData"
            multiple
            check-strictly
            :render-after-expand="false"
            show-checkbox
            node-key="id"
            placeholder="选择部门（可多选，支持跨部门）"
            class="w-full"
            :props="{ label: 'name', children: 'children' }"
          />
        </el-form-item>

        <el-divider content-position="left">个人档案（可选）</el-divider>
        <el-form-item label="真实姓名">
          <el-input v-model="form.profile.realName" maxlength="50" />
        </el-form-item>
        <el-form-item label="身份证号">
          <el-input v-model="form.profile.idCard" maxlength="18" />
        </el-form-item>
        <el-form-item label="年龄">
          <el-input-number v-model="form.profile.age" :min="1" :max="120" />
        </el-form-item>
        <el-form-item label="学历">
          <el-input v-model="form.profile.education" maxlength="20" />
        </el-form-item>
        <el-form-item label="家庭住址">
          <el-input v-model="form.profile.homeAddress" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">
          {{ editingId ? '保存修改' : '创建' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 重置密码对话框 -->
    <el-dialog v-model="resetPwdVisible" title="重置密码" width="420px" destroy-on-close>
      <div class="reset-tip">为 <b>{{ resetPwdUser?.username }}</b> 设置新密码（BCrypt 入库，下次登录生效）</div>
      <el-input
        v-model="resetPwdValue"
        type="password"
        show-password
        placeholder="新密码"
        class="mt12"
      />
      <template #footer>
        <el-button @click="resetPwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onResetPwd">确认重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import {
  createUser,
  deleteUser,
  getDeptTree,
  listRoles,
  listUsers,
  resetPassword,
  updateUser,
  updateUserStatus
} from '@/api/sys'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')

const roleOptions = ref([])
const deptTreeData = ref([])

const dialogVisible = ref(false)
const editingId = ref(null)
const saving = ref(false)
const emptyForm = () => ({
  username: '',
  nickname: '',
  password: '',
  roleIds: [],
  deptIds: [],
  profile: { realName: '', idCard: '', age: undefined, education: '', homeAddress: '' }
})
const form = ref(emptyForm())

const resetPwdVisible = ref(false)
const resetPwdUser = ref(null)
const resetPwdValue = ref('')

const SCOPE_LABELS = {
  ALL: '全部数据',
  DEPT_AND_SUB: '本部门及子部门',
  DEPT: '仅本部门',
  SELF: '仅本人'
}

function scopeLabel(scope) {
  return SCOPE_LABELS[scope] || scope || '-'
}

function isProtectedAdmin(row) {
  // 内置 admin 禁禁用；有 admin 角色的账号后端也会拒绝禁用，这里先行禁用开关
  return String(row.id) === '1' || row.roles?.some((r) => r.code === 'admin')
}

function formatTime(t) {
  if (!t) return '-'
  return String(t).replace('T', ' ').slice(0, 19)
}

onMounted(() => {
  load()
  loadOptions()
})

async function load() {
  loading.value = true
  try {
    const pageData = await listUsers(page.value, size.value, keyword.value.trim())
    rows.value = pageData?.records || []
    total.value = Number(pageData?.total || 0)
  } finally {
    loading.value = false
  }
}

async function loadOptions() {
  try {
    const [roles, tree] = await Promise.all([listRoles(), getDeptTree()])
    roleOptions.value = roles || []
    deptTreeData.value = tree || []
  } catch {
    /* 拦截器已弹错 */
  }
}

function onSearch() {
  page.value = 1
  load()
}

// ── 新建 / 编辑 ──

function openCreate() {
  editingId.value = null
  form.value = emptyForm()
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  form.value = {
    username: row.username,
    nickname: row.nickname || '',
    password: '',
    roleIds: (row.roles || []).map((r) => r.id),
    deptIds: (row.depts || []).map((d) => d.id),
    profile: {
      realName: row.profile?.realName || '',
      idCard: row.profile?.idCard || '',
      age: row.profile?.age ?? undefined,
      education: row.profile?.education || '',
      homeAddress: row.profile?.homeAddress || ''
    }
  }
  dialogVisible.value = true
}

function profileHasValue(p) {
  return !!(p.realName || p.idCard || p.age || p.education || p.homeAddress)
}

async function onSave() {
  if (!editingId.value && !form.value.username.trim()) {
    ElMessage.warning('请输入用户名')
    return
  }
  if (!editingId.value && !form.value.password) {
    ElMessage.warning('请输入初始密码')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      const payload = {
        nickname: form.value.nickname,
        roleIds: form.value.roleIds,
        deptIds: form.value.deptIds
      }
      if (form.value.password) {
        payload.password = form.value.password
      }
      if (profileHasValue(form.value.profile)) {
        payload.profile = form.value.profile
      }
      await updateUser(editingId.value, payload)
      ElMessage.success('已保存')
    } else {
      const payload = {
        username: form.value.username.trim(),
        password: form.value.password,
        nickname: form.value.nickname,
        roleIds: form.value.roleIds,
        deptIds: form.value.deptIds
      }
      if (profileHasValue(form.value.profile)) {
        payload.profile = form.value.profile
      }
      await createUser(payload)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    load()
  } catch {
    /* 拦截器已弹错（重名/最后 admin 等） */
  } finally {
    saving.value = false
  }
}

// ── 状态切换 ──

async function onToggleStatus(row, nextActive) {
  const next = nextActive ? 'ACTIVE' : 'DISABLED'
  const action = next === 'DISABLED' ? '禁用' : '启用'
  try {
    await ElMessageBox.confirm(
      next === 'DISABLED'
        ? `确认禁用 ${row.username}？该账号将被立即踢下线，且无法再登录。`
        : `确认启用 ${row.username}？`,
      `${action}账号`,
      { confirmButtonText: action, cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return // 取消
  }
  try {
    await updateUserStatus(row.id, next)
    ElMessage.success(`已${action}`)
    load()
  } catch {
    /* 拦截器已弹错 */
  }
}

// ── 删除 ──

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除用户 ${row.username}？其角色/部门/档案关联将一并清理，且立即踢下线。`,
      '删除用户',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'error' }
    )
  } catch {
    return
  }
  try {
    await deleteUser(row.id)
    ElMessage.success('已删除')
    load()
  } catch {
    /* 拦截器已弹错（内置 admin / 最后 admin 拒删） */
  }
}

// ── 重置密码 ──

function openResetPwd(row) {
  resetPwdUser.value = row
  resetPwdValue.value = ''
  resetPwdVisible.value = true
}

async function onResetPwd() {
  if (!resetPwdValue.value) {
    ElMessage.warning('请输入新密码')
    return
  }
  saving.value = true
  try {
    await resetPassword(resetPwdUser.value.id, resetPwdValue.value)
    ElMessage.success('密码已重置')
    resetPwdVisible.value = false
  } catch {
    /* 拦截器已弹错 */
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.user-manage {
  background: #fff;
  border-radius: 8px;
  padding: 16px;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
}

.toolbar-left {
  display: flex;
  gap: 8px;
}

.search-input {
  width: 240px;
}

.btn-icon {
  margin-right: 4px;
}

.user-table {
  width: 100%;
}

.username {
  font-weight: 600;
}

.ml4 {
  margin-left: 4px;
}

.role-tag {
  margin-right: 6px;
  margin-bottom: 2px;
}

.scope-badge {
  margin-left: 4px;
  padding: 0 4px;
  border-radius: 4px;
  background: rgba(64, 158, 255, 0.1);
  color: #409eff;
  font-size: 11px;
}

.muted {
  color: #c0c4cc;
  font-size: 12px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

.w-full {
  width: 100%;
}

.reset-tip {
  font-size: 13px;
  color: #606266;
}

.mt12 {
  margin-top: 12px;
}
</style>
