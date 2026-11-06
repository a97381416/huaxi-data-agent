<template>
  <div class="testhub">
    <!-- 页头：里程碑信息 + 进度 + 全局操作 -->
    <el-card shadow="never" class="head-card">
      <div class="head-top">
        <div>
          <div class="head-title">
            <span>{{ ms.title }}</span>
            <el-tag size="small" :type="ms.delivered ? 'success' : 'info'">
              {{ ms.delivered ? '已交付 · 自动用例可真实运行' : '未交付 · 接口探测将显示"未交付/受阻"' }}
            </el-tag>
          </div>
          <div class="head-theme">{{ ms.theme }}</div>
        </div>
        <div class="head-actions">
          <el-button type="primary" :loading="runAllLoading" :disabled="autoCount === 0" @click="runAllAuto">
            运行全部自动用例（{{ autoCount }}）
          </el-button>
          <el-button :disabled="noResults" @click="confirmClear">清除本页结果</el-button>
        </div>
      </div>

      <div class="stats-row">
        <div class="stat stat-total"><span class="num">{{ allScenarios.length }}</span><span class="lbl">用例总数</span></div>
        <div class="stat"><span class="num num-pass">{{ count('passed') + count('manual-pass') }}</span><span class="lbl">通过</span></div>
        <div class="stat"><span class="num num-fail">{{ count('failed') + count('manual-fail') }}</span><span class="lbl">失败</span></div>
        <div class="stat"><span class="num num-block">{{ count('blocked') }}</span><span class="lbl">受阻</span></div>
        <div class="stat"><span class="num">{{ autoCount }}</span><span class="lbl">可自动</span></div>
        <div v-if="ms.m === 1" class="stat-link">
          <el-link type="primary" :underline="false" @click="$router.push('/kernel')">自由调试入口：内核演示页 →</el-link>
        </div>
      </div>

      <el-collapse class="env-collapse">
        <el-collapse-item title="测试环境（公共）">
          <div v-for="(line, i) in envNotes" :key="i" class="env-line">{{ line }}</div>
        </el-collapse-item>
      </el-collapse>
    </el-card>

    <!-- 用例分组 -->
    <el-collapse v-model="openedGroups" class="group-collapse">
      <el-collapse-item v-for="g in ms.groups" :key="g.key" :name="g.key">
        <template #title>
          <div class="group-title">
            <el-tag size="small" type="info" effect="plain">{{ g.key }}</el-tag>
            <span>{{ g.name }}</span>
            <span class="group-count">{{ groupStat(g) }}</span>
            <span class="group-count">共 {{ g.scenarios.length }} 条</span>
          </div>
        </template>
        <ScenarioCard
          v-for="sc in g.scenarios"
          :key="sc.id"
          :sc="sc"
          :result="results[sc.id] || null"
          :running="runningId === sc.id"
          @run="runOne"
          @mark="markManual"
          @clear="clearOne"
        />
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import ScenarioCard from '@/testhub/ScenarioCard.vue'
import { getMilestone, envNotes } from '@/testhub/scenarios'
import { runScenario, newContext } from '@/testhub/run'
import { getResult, saveResult, clearResults } from '@/testhub/store'

const props = defineProps({
  m: { type: [Number, String], required: true }
})

const ms = computed(() => getMilestone(props.m))

/** 本页全部结果（内存态 + localStorage 持久化），key = 用例编号 */
const results = ref({})
const runningId = ref('')
const runAllLoading = ref(false)
const openedGroups = ref([])

// 换页（m0 → m3）时重载该里程碑的结果并展开全部分组
watch(
  () => props.m,
  () => load(),
  { immediate: true }
)

function load() {
  const all = {}
  ms.value.groups.forEach((g) => g.scenarios.forEach((sc) => {
    const r = getResult(ms.value.m, sc.id)
    if (r) all[sc.id] = r
  }))
  results.value = all
  openedGroups.value = ms.value.groups.map((g) => g.key)
}

const allScenarios = computed(() => ms.value.groups.flatMap((g) => g.scenarios))
const autoCount = computed(() => allScenarios.value.filter((s) => s.auto).length)
const noResults = computed(() => Object.keys(results.value).length === 0)

function count(state) {
  return Object.values(results.value).filter((r) => r.state === state).length
}

function groupStat(g) {
  const total = g.scenarios.length
  const done = g.scenarios.filter((sc) => {
    const s = results.value[sc.id]?.state
    return s === 'passed' || s === 'manual-pass'
  }).length
  const fail = g.scenarios.filter((sc) => ['failed', 'manual-fail'].includes(results.value[sc.id]?.state)).length
  const parts = [`通过 ${done}/${total}`]
  if (fail) parts.push(`失败 ${fail}`)
  return parts.join(' · ')
}

/** 单条自动用例：执行 → 判分 → 持久化 */
async function runOne(id) {
  const sc = allScenarios.value.find((s) => s.id === id)
  if (!sc || !sc.auto) return
  const ctx = newContext()
  runningId.value = id
  try {
    const r = await runScenario(sc, ctx, { delivered: ms.value.delivered })
    applyResult(id, r)
  } finally {
    runningId.value = ''
  }
}

async function runAllAuto() {
  const list = allScenarios.value.filter((s) => s.auto)
  if (ms.value.m === 1) {
    ElMessage.info('M1 流式用例逐条串行执行（含真实模型调用，整体可能需要 1-2 分钟）')
  }
  if (ms.value.m === 2) {
    ElMessage.warning('M2 用例走真实模型链路（单条约 30~120 秒），全部运行约需 20-40 分钟，建议逐条运行')
  }
  runAllLoading.value = true
  const ctx = newContext()
  try {
    for (const sc of list) {
      runningId.value = sc.id
      const r = await runScenario(sc, ctx, { delivered: ms.value.delivered })
      applyResult(sc.id, r)
      await new Promise((res) => setTimeout(res, 300))
    }
    ElMessage.success(`自动用例执行完毕：通过 ${count('passed')}，失败 ${count('failed')}`)
  } finally {
    runningId.value = ''
    runAllLoading.value = false
  }
}

function applyResult(id, r) {
  saveResult(ms.value.m, id, r)
  results.value = { ...results.value, [id]: { ...r, ts: Date.now() } }
}

function markManual(id, state) {
  applyResult(id, {
    state,
    message: state === 'manual-pass' ? '人工标记：通过' : '人工标记：不通过',
    detail: null
  })
}

function clearOne(id) {
  const next = { ...results.value }
  delete next[id]
  results.value = next
  // localStorage 里删单条（读出-改-写回）
  const m = ms.value.m
  const all = JSON.parse(localStorage.getItem(`da-testhub:m${m}`) || '{}')
  delete all[id]
  localStorage.setItem(`da-testhub:m${m}`, JSON.stringify(all))
}

function confirmClear() {
  ElMessageBox.confirm('清除本里程碑（M' + ms.value.m + '）的全部测试记录？', '提示', { type: 'warning' })
    .then(() => {
      clearResults(ms.value.m)
      results.value = {}
      ElMessage.success('已清除')
    })
    .catch(() => {})
}
</script>

<style scoped>
.testhub {
  max-width: 1000px;
  margin: 0 auto;
}

.head-card { margin-bottom: 14px; }

.head-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  flex-wrap: wrap;
}

.head-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 17px;
  font-weight: 600;
  color: #303133;
}

.head-theme {
  margin-top: 6px;
  font-size: 13px;
  color: #909399;
}

.head-actions { display: flex; gap: 10px; align-items: center; }

.stats-row {
  display: flex;
  gap: 28px;
  margin-top: 16px;
  align-items: center;
  flex-wrap: wrap;
}

.stat { display: flex; flex-direction: column; align-items: center; min-width: 48px; }

.num { font-size: 22px; font-weight: 600; color: #303133; }

.num-pass { color: #67c23a; }
.num-fail { color: #f56c6c; }
.num-block { color: #e6a23c; }

.lbl { font-size: 12px; color: #909399; }

.stat-link { margin-left: auto; font-size: 13px; }

.env-collapse { margin-top: 12px; border: none; }

.env-line { font-size: 13px; color: #606266; line-height: 1.9; }

.group-collapse { background: #fff; border-radius: 8px; }

.group-title { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 500; color: #303133; }

.group-count { font-size: 12px; color: #909399; font-weight: 400; }
</style>
