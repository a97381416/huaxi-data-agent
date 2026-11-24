<template>
  <Teleport to="body">
    <div
      v-if="modelValue"
      class="fixed inset-0 z-[2000] flex items-start justify-center bg-black/25 pt-[12vh]"
      @click.self="close"
    >
      <div
        class="w-[560px] max-w-[92vw] overflow-hidden rounded-xl border border-gray-100 bg-white shadow-2xl"
      >
        <!-- 输入行 -->
        <div class="flex items-center gap-2 border-b border-gray-100 px-4">
          <el-icon :size="16" class="text-gray-400"><Search /></el-icon>
          <input
            ref="inputRef"
            v-model="keyword"
            class="h-12 w-full border-0 bg-transparent text-sm text-gray-800 outline-none placeholder:text-gray-300"
            placeholder="conversation_id / session_id / user_id / 问题关键词…"
            @input="onInput"
            @keydown.down.prevent="move(1)"
            @keydown.up.prevent="move(-1)"
            @keydown.enter.prevent="select(activeIndex)"
            @keydown.esc.prevent="close"
          />
          <span class="whitespace-nowrap text-xs text-gray-300">ESC 关闭</span>
        </div>

        <!-- 结果区 -->
        <div class="max-h-[52vh] overflow-auto py-2">
          <div v-if="loading" class="px-4 py-6 text-center text-sm text-gray-400">搜索中…</div>
          <div v-else-if="!items.length" class="px-4 py-6 text-center text-sm text-gray-400">
            {{ keyword ? '未找到匹配的会话/用户' : '输入 ID 或问题关键词开始搜索' }}
          </div>

          <template v-else>
            <!-- 精确命中（conversation/user 精确项在前） -->
            <div v-if="exactItems.length" class="px-4 pb-1 pt-2 text-xs font-medium text-gray-400">
              精确命中
            </div>
            <div
              v-for="(item, i) in exactItems"
              :key="'e' + i"
              class="mx-2 flex cursor-pointer items-start gap-2.5 rounded-lg px-2 py-2"
              :class="flatIndex(item) === activeIndex ? 'bg-blue-50' : 'hover:bg-gray-50'"
              @click="select(flatIndex(item))"
              @mousemove="activeIndex = flatIndex(item)"
            >
              <el-tag size="small" :type="tagType(item)" disable-transitions>{{ tagText(item) }}</el-tag>
              <div class="min-w-0 flex-1">
                <div class="truncate text-sm text-gray-800">
                  {{ item.question || item.conversationId || item.userId }}
                </div>
                <div class="mt-0.5 truncate text-xs text-gray-400">
                  {{ subtitle(item) }}
                </div>
              </div>
            </div>

            <!-- 模糊建议 -->
            <div v-if="fuzzyItems.length" class="px-4 pb-1 pt-3 text-xs font-medium text-gray-400">
              模糊建议
            </div>
            <div
              v-for="(item, i) in fuzzyItems"
              :key="'f' + i"
              class="mx-2 flex cursor-pointer items-start gap-2.5 rounded-lg px-2 py-2"
              :class="flatIndex(item) === activeIndex ? 'bg-blue-50' : 'hover:bg-gray-50'"
              @click="select(flatIndex(item))"
              @mousemove="activeIndex = flatIndex(item)"
            >
              <el-tag size="small" :type="tagType(item)" disable-transitions>{{ tagText(item) }}</el-tag>
              <div class="min-w-0 flex-1">
                <div class="truncate text-sm text-gray-800">
                  {{ item.question || item.conversationId || item.userId }}
                </div>
                <div class="mt-0.5 truncate text-xs text-gray-400">
                  {{ subtitle(item) }}
                </div>
              </div>
            </div>
          </template>
        </div>

        <div
          class="flex items-center gap-4 border-t border-gray-100 bg-gray-50 px-4 py-2 text-xs text-gray-400"
        >
          <span>↵ 打开选中项</span><span>↑↓ 选择</span>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { searchMonitor } from '@/api/monitor'

/**
 * 命令面板（T3.4，设计 §6.3）：输入防抖 300ms 调 /monitor/search；
 * 后端识别优先级 conversation_id → session_id → user_id → question 模糊，
 * 精确项打类型标签；选中会话 → 详情页，选中用户 → 列表页预填 userId。
 */
const props = defineProps({ modelValue: Boolean })
const emit = defineEmits(['update:modelValue'])

const router = useRouter()
const inputRef = ref(null)
const keyword = ref('')
const loading = ref(false)
const items = ref([])
const activeIndex = ref(0)

const exactItems = computed(() => items.value.filter((x) => x.exact))
const fuzzyItems = computed(() => items.value.filter((x) => !x.exact))
const flat = computed(() => [...exactItems.value, ...fuzzyItems.value])

let debounceTimer = null
let searchSeq = 0

watch(
  () => props.modelValue,
  async (open) => {
    if (open) {
      keyword.value = ''
      items.value = []
      activeIndex.value = 0
      await nextTick()
      inputRef.value?.focus()
    }
  }
)

function close() {
  emit('update:modelValue', false)
}

function onInput() {
  clearTimeout(debounceTimer)
  const kw = keyword.value.trim()
  if (!kw) {
    items.value = []
    return
  }
  debounceTimer = setTimeout(async () => {
    const seq = ++searchSeq
    loading.value = true
    try {
      const list = (await searchMonitor(kw)) || []
      if (seq !== searchSeq) return // 只采纳最后一次输入的结果
      items.value = list.map((x, i) => ({ ...x, exact: i < exactCount(list) }))
      activeIndex.value = 0
    } catch {
      /* 拦截器已弹错 */
    } finally {
      if (seq === searchSeq) loading.value = false
    }
  }, 300)
}

/**
 * 后端按识别优先级返回（conversation 精确 → session 反查 → user → 模糊），
 * conversation/user 类型即「精确命中」，模糊建议排在其后。
 */
function exactCount(list) {
  return list.filter((x) => x.type === 'conversation' || x.type === 'user').length
}

function move(delta) {
  if (!flat.value.length) return
  activeIndex.value = (activeIndex.value + delta + flat.value.length) % flat.value.length
}

function flatIndex(item) {
  return flat.value.indexOf(item)
}

function select(index) {
  const item = flat.value[index]
  if (!item) return
  close()
  if (item.type === 'user') {
    // 选中用户项 → 跳列表页并预填 userId（设计 §6.3 ④）
    router.push({ path: '/monitor/traces', query: { userId: item.userId } })
  } else if (item.type === 'session' && item.sessionId) {
    // 提问条目 → 详情页定位该提问（分组高亮 + 默认选中终答/失败轮）
    router.push({
      path: `/monitor/traces/${encodeURIComponent(item.conversationId)}`,
      query: { sessionId: item.sessionId }
    })
  } else {
    router.push({ path: `/monitor/traces/${encodeURIComponent(item.conversationId)}` })
  }
}

function tagText(item) {
  if (item.type === 'user') return '用户'
  if (item.type === 'session') return '提问'
  return '会话'
}

function tagType(item) {
  if (item.type === 'user') return 'primary'
  if (item.status === 'error') return 'danger'
  if (item.status === 'running') return 'warning'
  return 'info'
}

function subtitle(item) {
  if (item.type === 'user') {
    const fail = item.failConv7d ? ` · 失败 ${item.failConv7d}` : ''
    return `近 7 天 ${item.conv7d ?? 0} 条提问${fail}`
  }
  // 提问条目：副标题直接给最终回答摘要（让人认出那次提问）
  if (item.type === 'session' && item.answerPreview) return item.answerPreview
  const parts = []
  if (item.conversationId) parts.push(shortId(item.conversationId))
  if (item.startedAt) parts.push(item.startedAt)
  if (item.status) parts.push(statusText(item.status))
  if (item.rounds !== null && item.rounds !== undefined) parts.push(`${item.rounds} 轮`)
  if (item.failRounds) parts.push(`失败 ${item.failRounds} 轮`)
  return parts.join(' · ')
}

function shortId(id) {
  return id && id.length > 14 ? id.slice(0, 8) + '…' + id.slice(-4) : id
}

function statusText(status) {
  return { running: '进行中', completed: '完成', error: '失败', interrupted: '已中断' }[status] || status
}
</script>
