<template>
  <div>
    <template v-for="node in nodes" :key="'g-' + keyId(node.event.seq)">
      <!-- 主事件行：❓提问 / 🤖 LLM 决策 / 💬 终答 -->
      <div
        :id="'event-node-' + keyId(node.event.seq)"
        class="mt-1 cursor-pointer rounded-lg border px-3 py-2 transition-colors"
        :class="keyId(node.event.seq) === selectedKey
          ? 'border-blue-300 bg-blue-50'
          : 'border-transparent hover:bg-gray-50'"
        @click="emitSelect(node.event)"
      >
        <div class="flex items-center gap-2 text-sm">
          <!-- 折叠箭头（仅 LLM 决策有子工具） -->
          <span
            v-if="node.children.length"
            class="shrink-0 select-none font-mono text-xs text-gray-400 hover:text-blue-500"
            @click.stop="emit('toggle', keyId(node.event.seq))"
          >{{ collapsedKeys.has(keyId(node.event.seq)) ? '▸' : '▾' }}</span>
          <span class="shrink-0 text-[13px] leading-none">{{ eventIcon(node.event) }}</span>
          <span v-if="eventSuccess(node.event)" class="shrink-0 font-mono text-xs text-gray-400">
            {{ eventTypeLabel(node.event) }}
          </span>
          <el-icon v-else :size="14" class="shrink-0 text-red-500"><CircleCloseFilled /></el-icon>
          <span class="min-w-0 flex-1 truncate text-xs" :class="eventTextClass(node.event)" :title="eventTitleFull(node.event)">
            {{ eventTitleFull(node.event) }}
          </span>
          <el-tooltip
            v-if="node.riskRules.length"
            :content="`命中规则：${node.riskRules.join('、')}（点击查看异常详情）`"
            placement="top"
            :show-after="200"
          >
            <span
              class="shrink-0 text-[13px] leading-none text-orange-500 hover:text-red-500"
              @click.stop="emitSelect(node.event, 'risk')"
            >⚠</span>
          </el-tooltip>
          <span class="shrink-0 text-[11px] text-gray-400">{{ formatDuration(node.event.durationMs) }}</span>
        </div>
        <div class="mt-0.5 flex items-center gap-3 pl-6 text-[11px] text-gray-400">
          <span v-if="node.event.promptTokens != null">
            {{ formatTokens(node.event.promptTokens) }}/{{ formatTokens(node.event.completionTokens) }} tok
          </span>
          <span v-if="node.event.errorMessage" class="truncate text-red-400">
            错误: {{ node.event.errorMessage }}
          </span>
          <span v-if="node.children.length" class="shrink-0">
            {{ node.children.length }} 个工具
          </span>
        </div>
      </div>
      <!-- 🔧 工具子事件（并发同级；结果直接内联展示）+ 委派子Agent块（M-TT） -->
      <template v-if="!collapsedKeys.has(keyId(node.event.seq))">
        <template v-for="child in node.children" :key="'c-' + keyId(child.seq)">
          <div
            :id="'event-node-' + keyId(child.seq)"
            class="ml-7 cursor-pointer rounded-lg border px-3 py-1.5 transition-colors"
            :class="keyId(child.seq) === selectedKey
              ? 'border-blue-300 bg-blue-50'
              : 'border-transparent hover:bg-gray-50'"
            @click="emitSelect(child)"
          >
            <div class="flex items-center gap-2 text-xs">
              <span class="shrink-0">{{ toolIcon(child) }}</span>
              <span class="shrink-0 font-mono font-semibold text-violet-600">{{ child.toolName }}</span>
              <span class="min-w-0 flex-1 truncate font-mono text-[11px] text-gray-400" :title="toolArgsPreview(child)">
                {{ toolArgsPreview(child) }}
              </span>
              <span v-if="child.resultChars != null" class="shrink-0 text-[11px] text-gray-400">
                {{ formatSize(child.resultChars) }}
              </span>
              <span v-else-if="!child.subSession" class="shrink-0 text-[11px] text-gray-300">结果缺</span>
            </div>
            <!-- 委派徽标行（M-TT §4.1）：agentCode 中文名/内部N轮M步/tok/状态点/▾，点击展开子块 -->
            <div
              v-if="child.subSession"
              class="mt-0.5 flex cursor-pointer items-center gap-1.5 pl-4 text-[11px] hover:text-blue-600"
              @click.stop="emit('toggle-sub', child.subSession.sessionId)"
            >
              <span class="shrink-0 select-none font-mono text-gray-400">
                {{ expandedSubs.has(child.subSession.sessionId) ? '▾' : '▸' }}
              </span>
              <span class="shrink-0">🤖</span>
              <span class="shrink-0 font-medium text-blue-600">{{ agentName(child.subSession.agentCode) }}</span>
              <span
                class="min-w-0 flex-1 truncate text-gray-400"
                :title="`内部 ${child.subSession.roundCount || 0} 轮 · ${stepCountOf(child.subSession)} 步工具`"
              >
                内部{{ child.subSession.roundCount || 0 }}轮/{{ stepCountOf(child.subSession) }}步
                <span v-if="child.subSession.promptTokens != null || child.subSession.completionTokens != null">
                  · {{ formatTokens((child.subSession.promptTokens || 0) + (child.subSession.completionTokens || 0)) }} tok
                </span>
              </span>
              <span
                v-if="child.subSession.failRounds > 0 || child.subSession.status === 'error'"
                class="shrink-0 text-red-500"
              >失败{{ child.subSession.failRounds || 0 }}轮</span>
              <span class="shrink-0">{{ subStatusDot(child.subSession.status) }}</span>
            </div>
          </div>
          <!-- 子Agent执行块（展开态）：整体缩进 + 左竖线 + 浅底色（对齐 ChatView 嵌套子卡片视觉语言）；
               含失败轮/错误态红描边（打开即看到病灶） -->
          <div
            v-if="child.subSession && expandedSubs.has(child.subSession.sessionId)"
            class="mb-1 ml-3 mt-1 rounded-lg border p-2"
            :class="subHasProblem(child.subSession)
              ? 'border-red-300 bg-red-50/20'
              : 'border-blue-100 bg-blue-50/30'"
          >
            <div class="flex items-center gap-1.5 text-xs">
              <span class="shrink-0">🤖</span>
              <span class="shrink-0 font-semibold text-blue-700">{{ agentName(child.subSession.agentCode) }}</span>
              <span class="shrink-0 font-mono text-[10px] text-gray-400">{{ child.subSession.agentCode }}</span>
              <el-tag size="small" :type="subTagType(child.subSession.status)" disable-transitions>
                {{ subStatusCn(child.subSession.status) }}
              </el-tag>
              <span v-if="subHasProblem(child.subSession)" class="shrink-0 text-[10px] text-red-500">含失败轮</span>
              <span class="ml-auto shrink-0 text-[10px] text-gray-400">
                {{ child.subSession.roundCount || 0 }} 轮
              </span>
            </div>
            <div class="mt-1 flex items-start gap-1 text-[11px] leading-4 text-gray-500" :title="child.subSession.task">
              <span class="shrink-0">🎯 任务:</span>
              <span class="min-w-0 flex-1">{{ firstLine(child.subSession.task) }}</span>
            </div>
            <!-- 子事件嵌套一层树（递归复用本组件；子块内无孙代，D3 只嵌一层） -->
            <TimelineEventList
              v-if="child.subSession.events && child.subSession.events.length"
              class="mt-1"
              :events="child.subSession.events"
              :keep="subKeepOf(child.subSession)"
              :risk-round-map="subRiskMapOf(child.subSession)"
              :selected-key="selectedKey"
              :collapsed-keys="collapsedKeys"
              :key-prefix="'sub-' + child.subSession.sessionId"
              :expanded-subs="emptySet"
              :sub-keeps="emptyObj"
              :sub-risk-maps="emptyObj"
              @select="(ev, prefix, tab) => emit('select', ev, prefix, tab)"
              @toggle="(k) => emit('toggle', k)"
              @locate="(k) => emit('locate', k)"
            />
            <div v-else class="mt-1 text-[11px] text-gray-300">
              （子Agent无轮次事件：未执行或明细未落库）
            </div>
          </div>
        </template>
      </template>
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { CircleCloseFilled } from '@element-plus/icons-vue'
import { agentName, subStatusCn, subTagType, subStatusDot } from '@/utils/agentNames'

/**
 * 事件流一层树渲染（M-TT T3.1 抽取，主轴与子Agent块共用，避免复制模板）：
 * 每个节点 = 事件行（❓/🤖/💬）+ 可折叠并发工具子行；委派 tool 行带 subSession 时
 * 追加「子Agent徽标行」（点击展开/收起嵌套子块）。
 *
 * keyPrefix 区分命名空间：主轴 ''（key=String(seq)），子块 `sub-{subSessionId}`
 * （key=`sub-{subSessionId}-{seq}`，D2 子事件 seq 独立编号）。DOM id 统一 event-node-{key}。
 * 分组/筛选/风险映射均由父组件计算后传入（组件保持 dumb）。
 */
const props = defineProps({
  /** 当前命名空间事件流（主轴 timelineEvents 或 subSession.events） */
  events: { type: Array, default: () => [] },
  /** 可见 seq 集合（筛选联动；null = 全部） */
  keep: { type: Set, default: null },
  /** 本命名空间 轮号 → 命中规则ID列表（⚠ 角标数据源） */
  riskRoundMap: { type: Map, default: () => new Map() },
  /** 选中事件 key（主轴 String(seq)，子块带前缀） */
  selectedKey: { type: String, default: '' },
  /** 折叠的 LLM 决策节点 key 集合（跨命名空间共用一个 Set） */
  collapsedKeys: { type: Set, default: () => new Set() },
  /** 本命名空间前缀：'' 主轴 / 'sub-{subSessionId}' 子块 */
  keyPrefix: { type: String, default: '' },
  /** 已展开的子Agent块 subSessionId 集合（仅主轴用） */
  expandedSubs: { type: Set, default: () => new Set() },
  /** 子Agent块可见集合：{ [subSessionId]: Set<seq> }（筛选联动；null = 全部） */
  subKeeps: { type: Object, default: null },
  /** 子Agent块风险映射：{ [subSessionId]: Map<round, ruleIds> } */
  subRiskMaps: { type: Object, default: () => ({}) },
  /**
   * 需重点提示的子会话集合（方案 §4.1：含失败轮 / 错误态 / 命中 high 风险）——
   * 命中则自动展开 + 红描边；口径由父组件统一计算（组件不自行猜失败）。
   */
  problemSubs: { type: Set, default: () => new Set() }
})

const emit = defineEmits(['select', 'toggle', 'toggle-sub', 'locate'])

const emptySet = new Set()
const emptyObj = {}

function keyId(seq) {
  return props.keyPrefix ? `${props.keyPrefix}-${seq}` : String(seq)
}

/** 当前提问的事件流骨架节点：主事件 + 筛选后的并发工具子事件 + 命中规则 */
const nodes = computed(() => {
  const evs = props.events || []
  const keep = props.keep
  const riskMap = props.riskRoundMap || new Map()
  const out = []
  for (const ev of evs) {
    if (ev.type === 'tool' || (keep && !keep.has(ev.seq))) continue
    out.push({
      event: ev,
      children: evs.filter((c) => c.type === 'tool' && c.parentSeq === ev.seq && (!keep || keep.has(c.seq))),
      riskRules: riskMap.get(ev.round) || []
    })
  }
  return out
})

function subKeepOf(sub) {
  if (!props.subKeeps) return null
  return props.subKeeps[sub.sessionId] || null
}

function subRiskMapOf(sub) {
  return props.subRiskMaps?.[sub.sessionId] || new Map()
}

function emitSelect(ev, tab) {
  emit('select', ev, props.keyPrefix, tab || '')
}

function stepCountOf(sub) {
  return (sub.events || []).filter((e) => e.type === 'tool').length
}

function subHasProblem(sub) {
  return props.problemSubs.has(sub.sessionId)
}

// ==================== 事件行渲染辅助 ====================

function eventIcon(ev) {
  return { user: '❓', llm: '🤖', answer: '💬' }[ev.type] || '·'
}

function eventTypeLabel(ev) {
  return ev.type === 'user' ? '提问' : ev.type === 'answer' ? '终答' : `R${ev.round}`
}

function eventSuccess(ev) {
  return ev.type === 'tool' ? ev.resultOk !== false : ev.success !== false
}

function eventTextClass(ev) {
  if (ev.type === 'user') return 'font-medium text-gray-800'
  if (ev.type === 'answer') return 'font-medium text-green-700'
  return ev.success === false ? 'text-red-500' : 'text-gray-600'
}

function eventTitleFull(ev) {
  if (ev.type === 'user') return ev.assistantText || '（无提问内容）'
  if (ev.type === 'answer') return ev.assistantText || '（无回答内容）'
  return ev.assistantText ? `“${firstLine(ev.assistantText)}”` : '（无文本输出，仅工具调用）'
}

function toolIcon(ev) {
  if (ev.resultOk == null) return '◌'
  if (ev.resultOk === false) return '❌'
  return ev.resultEmpty ? 'ⓘ' : '✅'
}

function toolArgsPreview(ev) {
  try {
    const args = JSON.parse(ev.arguments || '{}')
    const first = Object.entries(args)[0]
    if (!first) return '{}'
    const val = typeof first[1] === 'string' ? first[1] : JSON.stringify(first[1])
    return `${first[0]}=${abbreviate(firstLine(val), 30)}`
  } catch {
    return abbreviate(firstLine(ev.arguments || ''), 30)
  }
}

function firstLine(text) {
  return (text || '').split('\n')[0]
}

function abbreviate(text, max) {
  return (text || '').length > max ? (text || '').slice(0, max) + '…' : (text || '')
}

function formatTokens(n) {
  if (n == null) return '-'
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'k'
  return String(n)
}

function formatDuration(ms) {
  if (ms == null) return '-'
  if (ms < 1000) return Math.round(ms) + 'ms'
  const s = ms / 1000
  if (s < 60) return s.toFixed(1) + 's'
  const m = Math.floor(s / 60)
  return `${m}m${Math.round(s % 60)}s`
}

function formatSize(chars) {
  if (chars >= 1024 * 1024) return (chars / 1024 / 1024).toFixed(1) + 'MB'
  if (chars >= 1024) return (chars / 1024).toFixed(1) + 'KB'
  return chars + 'B'
}
</script>
