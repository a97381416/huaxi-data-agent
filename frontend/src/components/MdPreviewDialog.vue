<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="760px"
    top="5vh"
    append-to-body
    destroy-on-close
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <div v-loading="loading" class="min-h-[120px]">
      <div class="mb-2 flex items-center justify-between">
        <div class="flex min-w-0 items-center gap-2">
          <el-radio-group v-if="content" v-model="mode" size="small">
            <el-radio-button value="rendered">渲染</el-radio-button>
            <el-radio-button value="raw">原文</el-radio-button>
          </el-radio-group>
          <!-- tool content 在链路上被 JSON 字符串编码过（双重编码），提示已自动解开 -->
          <span v-if="normalized.layers" class="shrink-0 text-[11px] text-amber-600">
            已自动解开 {{ normalized.layers }} 层 JSON 字符串编码
          </span>
          <span v-if="!content" class="text-xs text-gray-300">-</span>
        </div>
        <div v-if="content" class="flex shrink-0 items-center gap-2">
          <span class="text-[11px] text-gray-400">{{ formatSize(content.length) }}</span>
          <el-button size="small" @click="copy">复制原文</el-button>
        </div>
      </div>
      <div v-if="!content && !loading" class="rounded-lg bg-gray-50 py-8 text-center text-sm text-gray-400">
        {{ emptyText }}
      </div>
      <div v-else class="max-h-[70vh] overflow-auto rounded-lg border border-gray-100 bg-white p-3">
        <!-- 渲染态：JSON 对象/数组 → 结构化 JSON 树；字符串 → MD（html:false 防 XSS，同 THINK 渲染口径） -->
        <template v-if="mode === 'rendered'">
          <JsonTree v-if="normalized.jsonValue" :data="normalized.jsonValue" root-key="json" :expand-depth="3" />
          <div v-else class="prose prose-sm max-w-none text-xs text-gray-700" v-html="rendered" />
        </template>
        <pre v-else class="whitespace-pre-wrap break-words font-mono text-xs leading-5 text-gray-700">{{ content }}</pre>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import MarkdownIt from 'markdown-it'
import JsonTree from '@/components/JsonTree.vue'
import { normalizeJsonText } from '@/utils/jsonText'

/**
 * 统一 MD 预览弹窗（2026-11-19 评审反馈：回答/轮次长文本多为 md 原文，
 * 「点击展开」统一弹窗渲染）。使用方：
 * - 提问详情页 INPUT/OUTPUT JSON 树长文本（JsonTree long-text-preview 回调）
 * - 提问列表「最终回答」列（按需拉全文后传入）
 * 支持 渲染/原文 切换 + 复制原文；content 为空时给空态文案。
 *
 * 2026-11-20：tool 消息 content 在链路上被 JSON 字符串编码（Spring AI
 * FunctionToolCallback 对 String 结果再走一次 toJson，历史轮已落库），
 * 渲染前逐层 JSON.parse 自动解包（归一化逻辑抽 utils/jsonText 供事件快照共用）；
 * 解出 JSON 对象/数组（如 executeSql 结果）改给结构化 JSON 树。
 * 「原文」与「复制原文」始终用原始 content，不受解包影响。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  title: { type: String, default: '内容预览' },
  /** md 原文；null/'' 显示空态 */
  content: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  emptyText: { type: String, default: '暂无内容' }
})
const emit = defineEmits(['update:modelValue'])

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })
const mode = ref('rendered')

// 每次打开默认渲染视图
watch(() => props.modelValue, (v) => {
  if (v) mode.value = 'rendered'
})

const normalized = computed(() => normalizeJsonText(props.content))

const rendered = computed(() => md.render(normalized.value.text))

async function copy() {
  try {
    await navigator.clipboard.writeText(props.content || '')
    ElMessage.success('原文已复制')
  } catch {
    ElMessage.warning('复制失败，请手动复制')
  }
}

function formatSize(chars) {
  if (chars >= 1024 * 1024) return (chars / 1024 / 1024).toFixed(1) + 'MB'
  if (chars >= 1024) return (chars / 1024).toFixed(1) + 'KB'
  return chars + 'B'
}
</script>
