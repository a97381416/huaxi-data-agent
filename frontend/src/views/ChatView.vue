<template>
  <div class="flex h-full w-full bg-gray-50">
    <!-- 左侧：历史会话 Sidebar（280px 固定宽） -->
    <aside class="flex w-[280px] shrink-0 flex-col border-r border-gray-100 bg-white">
      <div class="p-3">
        <button
          type="button"
          class="flex h-9 w-full items-center justify-center gap-1.5 rounded-lg bg-blue-50 text-[13px] font-medium text-blue-600 transition-all duration-200 hover:bg-blue-100 active:scale-[0.98]"
          @click="newConversation"
        >
          <el-icon :size="14"><Plus /></el-icon>
          新会话
        </button>
      </div>

      <!-- 历史列表：按时间分组 -->
      <div class="thin-scroll flex-1 overflow-y-auto px-2 pb-3">
        <div class="flex flex-col gap-5 pt-1">
          <div v-for="g in groupedConversations" :key="g.label">
            <h3 class="mb-2 px-2 text-xs font-medium text-gray-400">{{ g.label }}</h3>
            <div class="flex flex-col gap-0.5">
              <div
                v-for="c in g.items"
                :key="c.conversationId"
                class="group relative cursor-pointer rounded-md p-2 pr-7 transition-colors duration-200 hover:bg-gray-200/50"
                :class="c.conversationId === activeCid ? 'bg-blue-50 hover:bg-blue-50' : ''"
                @click="openConversation(c.conversationId)"
                @mouseenter="checkTrunc($event, c.conversationId)"
              >
                <el-tooltip
                  :content="c.title || '新会话'"
                  placement="right"
                  :show-after="500"
                  :disabled="!truncMap[c.conversationId]"
                >
                  <div
                    class="conv-title truncate text-[13px] leading-5"
                    :class="c.conversationId === activeCid ? 'font-medium text-blue-700' : 'text-gray-700'"
                  >
                    {{ c.title || '新会话' }}
                  </div>
                </el-tooltip>
                <div class="mt-1 flex items-center gap-2 text-[11px] text-gray-400">
                  <span>{{ formatConvTime(c.lastActiveAt || c.createdAt) }}</span>
                  <span v-if="c.streaming" class="flex items-center gap-1 text-emerald-600">
                    <span class="h-1.5 w-1.5 animate-pulse rounded-full bg-emerald-500"></span>
                    生成中
                  </span>
                </div>
                <button
                  type="button"
                  title="删除会话"
                  class="absolute right-1.5 top-1.5 flex h-6 w-6 items-center justify-center rounded-md text-gray-300 opacity-0 transition-all duration-200 hover:bg-gray-200/70 hover:text-red-500 group-hover:opacity-100"
                  @click.stop="onDeleteConversation(c.conversationId)"
                >
                  <el-icon :size="13"><Delete /></el-icon>
                </button>
              </div>
            </div>
          </div>
        </div>
        <div
          v-if="conversationsHasMore"
          class="cursor-pointer py-2 text-center text-xs text-blue-500 transition-colors duration-200 hover:text-blue-600"
          @click="loadConversations(false)"
        >
          {{ conversationsLoading ? '加载中…' : '加载更多' }}
        </div>
        <div v-if="!conversations.length && !conversationsLoading" class="px-2 pt-6 text-center text-xs text-gray-300">
          暂无历史会话
        </div>
      </div>
    </aside>

    <!-- 右侧：聊天区 -->
    <section class="flex min-w-0 flex-1 flex-col">
      <!-- 消息流：max-w-6xl 居中，不贴边 -->
      <div ref="listRef" class="thin-scroll min-h-0 flex-1 overflow-y-auto" @scroll="onListScroll">
        <div class="mx-auto flex w-full max-w-6xl flex-col gap-5 px-6 py-8">
          <template v-for="(msg, i) in activeSession?.messages || []" :key="i">
            <!-- 用户消息（含附件 chips：图片缩略图 / 文件图标，点击预览）：靠右 -->
            <div v-if="msg.role === 'user'" class="flex justify-end">
              <div
                class="max-w-[85%] whitespace-pre-wrap rounded-2xl rounded-tr-sm bg-blue-600 px-5 py-3 text-sm leading-relaxed text-white shadow-sm"
              >
                <div v-if="msg.attachments?.length" class="mb-2 flex flex-wrap gap-2">
                  <div
                    v-for="att in msg.attachments"
                    :key="att.fileId"
                    class="flex max-w-[240px] cursor-pointer items-center gap-2 rounded-lg bg-white/15 px-2.5 py-1.5 transition-colors duration-200 hover:bg-white/25"
                    :title="att.fileName"
                    @click="openPreview(att)"
                  >
                    <img
                      v-if="isImageType(att.fileType)"
                      :src="filePreviewUrl(att.fileId)"
                      class="h-9 w-9 shrink-0 rounded-md object-cover"
                      alt=""
                    />
                    <el-icon v-else :size="18" class="shrink-0"><Document /></el-icon>
                    <span class="truncate text-xs">{{ att.fileName }}</span>
                  </div>
                </div>
                <div>{{ msg.content }}</div>
              </div>
            </div>

            <!-- 助手消息：白色卡片 + 微阴影，timeline 驱动（thinking / tool / todo / text / error）：靠左 -->
            <div v-else class="flex justify-start">
              <div class="chat-timeline w-full rounded-2xl rounded-tl-sm border border-gray-100 bg-white px-5 py-4 shadow-sm">
                <template v-for="(block, bi) in groupedTimeline(msg)" :key="bi">
                  <!-- 执行过程聚合组：连续 thinking/tool 压成一条，流式自动展开徽章流，结束后自动收起 -->
                  <div v-if="block.group">
                    <button type="button" class="pg-header" @click="toggleGroup(msg, block)">
                      <el-icon v-if="pgIsRunning(msg, block)" :size="13" class="pg-icon running"><Loading /></el-icon>
                      <el-icon v-else-if="pgIssueCount(block)" :size="13" class="pg-icon issue"><CircleCloseFilled /></el-icon>
                      <el-icon v-else :size="13" class="pg-icon done"><CircleCheckFilled /></el-icon>
                      <span>执行过程</span>
                      <span class="pg-meta">{{ pgMetaText(msg, block) }}</span>
                      <div v-if="pgIsRunning(msg, block) && pgTools(block).length" class="pg-bar">
                        <i :style="{ width: pgProgress(block) + '%' }"></i>
                      </div>
                      <el-icon :size="11" class="transition-transform duration-200" :class="pgExpanded(msg, block) ? 'rotate-180' : ''">
                        <ArrowDown />
                      </el-icon>
                    </button>
                    <div v-if="pgExpanded(msg, block)" class="pg-body">
                      <!-- 流式自动模式：极简单行 = 已完成计数 + 异常计数 + 当前运行中/思考中 -->
                      <div v-if="pgCompact(msg, block)" class="pg-badges">
                        <button v-if="pgDoneCount(block)" type="button" class="pg-badge" title="展开全部步骤" @click="openGroup(block)">
                          <el-icon :size="12" class="tool-icon completed"><CircleCheckFilled /></el-icon>
                          <span>已完成 {{ pgDoneCount(block) }} 步</span>
                        </button>
                        <button v-if="pgIssueCount(block)" type="button" class="pg-badge issue" title="展开查看异常步骤" @click="openGroup(block)">
                          <el-icon :size="12" class="tool-icon failed"><CircleCloseFilled /></el-icon>
                          <span>{{ pgIssueCount(block) }} 项异常</span>
                        </button>
                        <span v-if="pgRunningTool(block)" class="pg-badge running">
                          <el-icon :size="12" class="tool-icon running"><Loading /></el-icon>
                          <span class="font-mono">{{ pgRunningTool(block).toolName }}</span>
                        </span>
                        <span v-else-if="pgIsRunning(msg, block)" class="pg-badge">
                          <el-icon :size="12" class="pg-think-icon is-loading"><Loading /></el-icon>
                          <span>思考中…</span>
                        </span>
                      </div>
                      <!-- 手动展开：全量徽章流（可换行，点击看单步结果/思考） -->
                      <template v-else>
                        <div class="pg-badges">
                          <button
                            v-for="(step, si) in block.items"
                            :key="si"
                            type="button"
                            class="pg-badge"
                            :class="{ sel: pgState(block.lead).selected === step, running: step.type === 'tool' && step.status === 'running' }"
                            @click="toggleStep(block, step)"
                          >
                            <template v-if="step.type === 'thinking'">
                              <el-icon
                                :size="12"
                                class="pg-think-icon"
                                :class="{ 'is-loading': msg.streaming && msg.timeline[msg.timeline.length - 1] === step }"
                              >
                                <Loading v-if="msg.streaming && msg.timeline[msg.timeline.length - 1] === step" />
                                <View v-else />
                              </el-icon>
                              <span>思考过程</span>
                            </template>
                            <template v-else>
                              <el-icon :size="12" class="tool-icon" :class="step.status">
                                <Loading v-if="step.status === 'running'" />
                                <CircleCheckFilled v-else-if="step.status === 'completed'" />
                                <CircleCloseFilled v-else />
                              </el-icon>
                              <span class="font-mono">{{ step.toolName }}</span>
                            </template>
                          </button>
                        </div>
                        <!-- 选中步骤详情：工具结果（markdown 渲染 / 原文切换）/ 思考内容 -->
                        <template v-if="pgSelectedDetail(block) === 'result'">
                          <div class="tool-detail-bar">
                            <button
                              v-if="isMarkdownish(formatToolResult(pgState(block.lead).selected.result))"
                              type="button"
                              class="tool-view-toggle"
                              @click="pgState(block.lead).rawView = !pgState(block.lead).rawView"
                            >
                              {{ pgState(block.lead).rawView ? '渲染视图' : '查看原文' }}
                            </button>
                          </div>
                          <div
                            v-if="!pgState(block.lead).rawView && isMarkdownish(formatToolResult(pgState(block.lead).selected.result))"
                            class="chat-md tool-md thin-scroll"
                            v-html="renderMd(formatToolResult(pgState(block.lead).selected.result))"
                          ></div>
                          <pre v-else class="tool-result thin-scroll">{{ formatToolResult(pgState(block.lead).selected.result) }}</pre>
                        </template>
                        <div v-else-if="pgSelectedDetail(block) === 'thinking'" class="think-body">{{ pgState(block.lead).selected.content }}</div>
                      </template>
                    </div>
                  </div>

                  <!-- todo 执行计划面板（TodoWrite 参数侧信道） -->
                  <div v-else-if="block.item.type === 'todo'" class="rounded-xl border border-gray-100 bg-gray-50/70 px-3.5 py-3">
                    <div class="mb-2 flex items-center gap-1.5 text-xs font-semibold text-gray-600">
                      <el-icon :size="12"><List /></el-icon>
                      执行计划
                      <span class="ml-auto font-normal text-gray-400">
                        {{ completedCount(block.item.items) }}/{{ block.item.items.length }}
                      </span>
                    </div>
                    <div v-for="(t, idx) in block.item.items" :key="idx" class="flex items-center gap-2 py-[3px] text-xs text-gray-600">
                      <el-icon :size="12" class="todo-icon" :class="t.status">
                        <CircleCheckFilled v-if="t.status === 'completed'" />
                        <Loading v-else-if="t.status === 'in_progress'" class="is-loading" />
                        <MoreFilled v-else />
                      </el-icon>
                      <span
                        :class="{
                          'text-gray-300 line-through': t.status === 'completed',
                          'text-blue-600': t.status === 'in_progress'
                        }"
                      >{{ t.text }}</span>
                    </div>
                  </div>

                  <!-- 错误 -->
                  <el-alert
                    v-else-if="block.item.type === 'error'"
                    :title="block.item.message"
                    type="error"
                    :closable="false"
                  />

                  <!-- 正文 markdown -->
                  <div v-else-if="block.item.type === 'text' && block.item.content" class="chat-md" v-html="renderMd(block.item.content)"></div>
                </template>

                <span v-if="msg.streaming && !hasTailText(msg)" class="chat-cursor">▍</span>
                <div
                  v-if="msg.interrupted"
                  class="mt-2 w-fit rounded-md bg-amber-50 px-2.5 py-1.5 text-xs text-amber-600"
                >
                  ⏸ 已中断 — 发送消息（可为空发送/继续）可从断点恢复
                </div>
              </div>
            </div>
          </template>

          <!-- 空状态 -->
          <div v-if="!activeSession?.messages?.length" class="flex flex-col items-center justify-center gap-3 pt-28">
            <span class="flex h-12 w-12 items-center justify-center rounded-2xl bg-blue-50 text-blue-500">
              <el-icon :size="22"><MagicStick /></el-icon>
            </span>
            <p class="text-sm text-gray-400">问点什么吧，比如：各片区的项目数量和投资完成率？</p>
          </div>
        </div>
      </div>

      <!-- 输入区：居中浮动容器 -->
      <div class="shrink-0 px-6 pb-5 pt-1">
        <div class="mx-auto w-full max-w-6xl">
          <!-- 待发送附件（上传即开始，发送时携带 fileIds） -->
          <div v-if="pendingFiles.length" class="mb-2.5 flex flex-col gap-1.5">
            <div
              v-for="pf in pendingFiles"
              :key="pf.localId"
              class="flex items-center gap-3 rounded-xl border border-gray-100 bg-white px-3 py-2 shadow-sm"
            >
              <img
                v-if="pf.status === 'done' && isImageType(pf.fileType)"
                :src="filePreviewUrl(pf.fileId)"
                class="h-10 w-10 shrink-0 cursor-pointer rounded-lg object-cover"
                alt=""
                @click="openPreview(pf)"
              />
              <el-icon v-else :size="20" class="shrink-0" :class="pf.status === 'failed' ? 'text-red-400' : 'text-gray-400'">
                <Document />
              </el-icon>
              <div class="min-w-0 flex-1">
                <div class="truncate text-[13px] text-gray-600" :title="pf.fileName">{{ pf.fileName }}</div>
                <el-progress
                  v-if="pf.status === 'uploading'"
                  :percentage="pf.progress"
                  :stroke-width="4"
                  class="mt-1 w-44"
                />
                <div v-else-if="pf.status === 'failed'" class="text-xs text-red-500">{{ pf.error || '上传失败' }}</div>
                <div v-else class="text-xs text-emerald-600">{{ pf.embed === 1 ? '已解析 · 大文件 RAG' : '已解析' }}</div>
              </div>
              <button
                type="button"
                title="移除"
                class="flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-gray-400 transition-colors duration-200 hover:bg-gray-100 hover:text-red-500"
                @click="removePending(pf)"
              >
                <el-icon :size="12"><Close /></el-icon>
              </button>
            </div>
          </div>

          <!-- 输入容器：附件 | 无边框输入 | 圆形发送（textarea 单行高与按钮等高，天然居中对齐） -->
          <div
            class="input-shell rounded-2xl border border-gray-200 bg-white px-3 py-2 shadow-lg shadow-gray-200/60 transition-all duration-200 focus-within:border-blue-300"
          >
            <div class="flex items-center gap-2">
              <el-tooltip content="上传文件（单轮最多 3 个，支持 PDF/Office/文本/图片，≤100MB）" placement="top" :show-after="300">
                <button
                  type="button"
                  class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-gray-400 transition-all duration-200 hover:bg-gray-100 hover:text-gray-600 active:scale-90"
                  @click="onUploadClick"
                >
                  <el-icon :size="17"><Paperclip /></el-icon>
                </button>
              </el-tooltip>
              <input ref="fileInputRef" type="file" multiple style="display: none" @change="onFilesSelected" />
              <!-- M6 联网开关：开启后本条请求注入 Tavily 联网搜索工具；后端未配 key 时置灰 -->
              <el-tooltip
                :content="onlineAvailable ? '联网搜索（开启后使用 Tavily 搜索实时信息）' : '联网不可用：后端未配置 tavily.api-key，配置并重启后端后生效'"
                placement="top"
                :show-after="300"
              >
                <button
                  type="button"
                  class="online-toggle flex h-9 shrink-0 items-center gap-1.5 rounded-full px-2.5 text-xs transition-all duration-200"
                  :class="!onlineAvailable
                    ? 'cursor-not-allowed bg-gray-50 text-gray-300'
                    : online
                      ? 'bg-blue-50 text-blue-600 active:scale-95'
                      : 'text-gray-400 hover:bg-gray-100 hover:text-gray-600 active:scale-95'"
                  @click="onlineAvailable && (online = !online)"
                >
                  <el-icon :size="14"><Connection /></el-icon>
                  <span>联网</span>
                </button>
              </el-tooltip>
              <el-input
                v-model="draft"
                type="textarea"
                :autosize="{ minRows: 1, maxRows: 6 }"
                resize="none"
                class="chat-textarea"
                placeholder="输入问题，Enter 发送（回答进行中发送新消息将自动顶掉旧回答）；可先上传文件再提问"
                @keydown.enter.exact.prevent="send"
              />
              <div class="flex shrink-0 items-center gap-1.5">
                <el-tooltip v-if="activeSession?.streaming" content="停止生成" placement="top" :show-after="300">
                  <button
                    type="button"
                    class="flex h-9 w-9 items-center justify-center rounded-full bg-red-50 text-red-500 transition-all duration-200 hover:bg-red-100 active:scale-90"
                    @click="onStop"
                  >
                    <el-icon :size="16"><VideoPause /></el-icon>
                  </button>
                </el-tooltip>
                <el-tooltip v-else :content="sendTip" placement="top" :show-after="300">
                  <button
                    type="button"
                    class="flex h-9 w-9 items-center justify-center rounded-full bg-blue-600 text-white transition-all duration-200 hover:bg-blue-700 active:scale-90 disabled:cursor-not-allowed disabled:opacity-40"
                    :disabled="sendDisabled"
                    @click="send"
                  >
                    <el-icon :size="16"><Top /></el-icon>
                  </button>
                </el-tooltip>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  ArrowDown, CircleCheckFilled, CircleCloseFilled, Close, Delete, Document, Connection, List, Loading,
  MoreFilled, Paperclip, Plus, Top, VideoPause, MagicStick, View
} from '@element-plus/icons-vue'
import {
  deleteSession, getAgentCapabilities, getAgentStreamStatus, getSessionDetail, listSessions,
  openAgentStream, stopAgent
} from '@/api/agent'
import { deleteFile, filePreviewUrl, isImageType, uploadFile } from '@/api/file'
import md from '@/utils/markdown'

/** 单轮最多上传文件数（对齐后端 data-agent.file.max-count） */
const MAX_FILES_PER_TURN = 3

// ── 会话窗口与历史列表 ──
const conversations = ref([])
const conversationsPage = ref(0)
const conversationsHasMore = ref(false)
const conversationsLoading = ref(false)
const CONV_PAGE_SIZE = 10

/** 标题是否溢出（溢出才启用 Tooltip 显示全称） */
const truncMap = reactive({})

function checkTrunc(e, cid) {
  const title = e.currentTarget?.querySelector('.conv-title')
  truncMap[cid] = !!(title && title.scrollWidth > title.clientWidth)
}

/** 会话所属时间分组标签 */
function dayLabel(isoStr) {
  const d = new Date((isoStr || '').replace(' ', 'T'))
  if (Number.isNaN(d.getTime())) return '更早'
  const startOfDay = (x) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const diffDays = Math.round((startOfDay(new Date()) - startOfDay(d)) / 86400000)
  if (diffDays <= 0) return '今天'
  if (diffDays === 1) return '昨天'
  if (diffDays === 2) return '前天'
  if (diffDays <= 7) return '近 7 天'
  return '更早'
}

const GROUP_ORDER = ['今天', '昨天', '前天', '近 7 天', '更早']

/** 历史记录按时间分组（今天 / 昨天 / 前天 / 近 7 天 / 更早），空组不显示 */
const groupedConversations = computed(() => {
  const map = new Map()
  for (const c of conversations.value) {
    const label = dayLabel(c.lastActiveAt || c.createdAt)
    if (!map.has(label)) map.set(label, [])
    map.get(label).push(c)
  }
  return GROUP_ORDER.filter((k) => map.has(k)).map((label) => ({ label, items: map.get(label) }))
})

/** cid → { id, title, messages, streaming, loaded }（按需创建） */
const sessions = reactive({})
const activeCid = ref('')
const activeSession = computed(() => sessions[activeCid.value])

function ensureSession(cid) {
  if (!sessions[cid]) {
    sessions[cid] = reactive({ id: cid, title: '', messages: [], streaming: false, loaded: false, interrupted: false })
  }
  return sessions[cid]
}

function newConversation() {
  const cid = 'dodo_conv_' + crypto.randomUUID()
  const s = ensureSession(cid)
  s.loaded = true
  activeCid.value = cid
  syncUrl(cid)
}

function syncUrl(cid) {
  const u = new URL(window.location.href)
  u.searchParams.set('conversationId', cid)
  window.history.replaceState({}, '', u.toString())
}

function genTitle(text) {
  return text.length > 40 ? text.slice(0, 40) + '...' : text
}

// ── 进行中的流注册表（对齐原版 chat.js liveStreams） ──
// cid -> { cid, aiMsg, question, controller, lastSeq, retries, paused }
const liveStreams = new Map()

const draft = ref('')
/** M6 联网开关：发送时随请求携带 online，后端据此注入 Tavily 联网工具（未配 key 自动降级） */
const online = ref(false)
/** 后端 Tavily 是否可用（/agent/capabilities 探测）：false 时开关置灰并提示配置方法 */
const onlineAvailable = ref(true)
const listRef = ref(null)
const autoScroll = ref(true)
const SCROLL_BOTTOM_THRESHOLD = 80

function newAssistantMsg() {
  return reactive({ role: 'ai', timeline: [], streaming: true, interrupted: false, error: '' })
}

function safeParse(s, fallback) {
  try { return JSON.parse(s) } catch { return fallback }
}

// ── 打开历史会话 ──
async function openConversation(cid) {
  ensureSession(cid)
  activeCid.value = cid
  syncUrl(cid)
  if (liveStreams.has(cid)) {
    // 本地已有活跃流：直接渲染实时消息
    const s = sessions[cid]
    if (!s.messages.length) {
      const live = liveStreams.get(cid)
      if (live.question) s.messages.push({ role: 'user', content: live.question })
      s.messages.push(live.aiMsg)
    }
    s.streaming = true
    scrollToBottom()
    return
  }
  await loadConversation(cid)
}

/** 拉取会话历史并按 timeline 回放渲染 */
async function loadConversation(cid) {
  const s = ensureSession(cid)
  const live = liveStreams.get(cid)
  try {
    const detail = await getSessionDetail(cid)
    const loaded = []
    for (const round of detail?.messages || []) {
      loaded.push({ role: 'user', content: round.question, attachments: round.attachments || [] })
      let items = safeParse(round.timeline || '[]', [])
      if (!Array.isArray(items)) items = []
      if (items.length === 0 && round.answer) items = [{ type: 'text', content: round.answer }]
      // 该轮还没有可回放内容（流式进行中，终态才落库）：只保留问题，不渲染空助手气泡
      if (items.length === 0) continue
      loaded.push({
        role: 'ai',
        timeline: items.map((it) => (it.type === 'tool' ? { ...it, showResult: false } : it)),
        streaming: false,
        interrupted: false,
        error: ''
      })
    }
    if (live) {
      // 本地有进行中的流：当前轮是终态才落库（不在历史里），直接把实时消息接在历史后面
      loaded.push(live.aiMsg)
      s.streaming = true
    } else {
      s.streaming = false
    }
    s.messages = loaded
    s.loaded = true
    if (!s.title && loaded.length && loaded[0].role === 'user') s.title = genTitle(loaded[0].content)
    scrollToBottom()
    if (!live) tryResumeOnLoad(cid)
  } catch (e) {
    // 服务端记录还没可见（首轮刚发起）：有本地流就直接渲染
    if (live) {
      s.messages = live.question ? [{ role: 'user', content: live.question }, live.aiMsg] : [live.aiMsg]
      s.streaming = true
      s.loaded = true
    } else {
      ElMessage.error(e.message || '加载会话失败')
    }
  }
}

/**
 * 探测服务端是否有进行中的流（刷新/关页重开/点开历史会话的场景）。
 * 有则发 recovery 请求从第 1 个事件全量补发，瞬间补齐到当前进度再续实时流。
 */
async function tryResumeOnLoad(cid) {
  if (liveStreams.has(cid)) return
  let active = false
  try {
    const st = await getAgentStreamStatus(cid)
    active = !!st?.active
  } catch {
    return
  }
  if (!active) return
  const s = sessions[cid]
  if (!s) return
  // 当前轮终态才落库（不在历史里）：直接追加实时消息，从事件 1 全量补发
  const aiMsg = newAssistantMsg()
  s.messages.push(aiMsg)
  s.streaming = true
  autoScroll.value = true
  openStream(cid, aiMsg, { conversationId: cid, recovery: true, startSeq: 1 })
  scrollToBottom()
}

// ── 流式消费 ──

/** 打开一条流（首发/恢复共用）：注册进 liveStreams 并发起请求 */
function openStream(cid, aiMsg, body, question = '') {
  const entry = { cid, aiMsg, question, controller: null, lastSeq: 0, retries: 0, paused: false }
  liveStreams.set(cid, entry)
  const s = sessions[cid]
  if (s) s.streaming = true
  // openAgentStream 同步返回 abort 句柄：停止按钮随时可取消本地读取
  entry.controller = openAgentStream(body, onStreamEvent(cid, entry), streamHandlers(cid, entry))
  return entry
}

/** SSE 事件分发到 timeline（对齐原版 processAgentxEvent） */
function onStreamEvent(cid, entry) {
  return ({ data, id }) => {
    // 按序号防重：补发重叠时丢掉已收到的事件
    if (id) {
      const seq = Number(id)
      if (!Number.isNaN(seq)) {
        if (seq <= entry.lastSeq) return
        entry.lastSeq = seq
      }
    }
    const msg = entry.aiMsg
    const timeline = msg.timeline
    switch (data.type) {
      case 'THINKING': {
        const content = data.content || ''
        const last = timeline[timeline.length - 1]
        if (last && last.type === 'thinking') last.content += content
        else timeline.push({ type: 'thinking', content, open: false })
        break
      }
      case 'TEXT': {
        const content = data.content || ''
        if (!content) break
        const last = timeline[timeline.length - 1]
        if (last && last.type === 'text') last.content += content
        else timeline.push({ type: 'text', content })
        break
      }
      case 'TOOL_START': {
        // TodoWrite 走独立 todo 面板，不进 tool 卡片
        if ((data.toolName || '').toLowerCase() === 'todowrite') {
          const args = safeParse(data.arguments || '{}', {})
          const todos = Array.isArray(args.todos) ? args.todos : []
          if (todos.length) {
            timeline.push({
              type: 'todo',
              items: todos.map((t) => ({ text: t.content || '', status: t.status || 'pending' }))
            })
          }
          break
        }
        timeline.push({
          type: 'tool',
          toolName: data.toolName || 'unknown',
          toolCallId: data.toolCallId || '',
          status: 'running',
          result: '',
          showResult: false
        })
        break
      }
      case 'TOOL_END': {
        if ((data.toolName || '').toLowerCase() === 'todowrite') break
        const item = timeline.find(
          (it) => it.type === 'tool' && it.toolCallId === data.toolCallId && it.status === 'running'
        ) || timeline.filter((it) => it.type === 'tool' && it.toolName === data.toolName && it.status === 'running').at(-1)
        if (item) {
          // success=false：工具失败也发 TOOL_END（result 为错误 JSON），徽章标红不再空转
          item.status = data.success === false ? 'failed' : 'completed'
          item.result = data.result || ''
        }
        break
      }
      case 'ERROR': {
        timeline.push({ type: 'error', message: data.message || '执行出错', detail: data.detail || '' })
        break
      }
      case 'PAUSED': {
        // 本轮被终止（点停止/被新消息顶掉）：running 工具置为中断态
        entry.paused = true
        applyPausedState(cid, entry)
        break
      }
      case 'COMPLETE': {
        msg.streaming = false
        // 总耗时/轮次（COMPLETE 终态事件带回，整条消息一份）；历史回放的消息没有该字段
        if (data.elapsedMs != null) msg.elapsedMs = data.elapsedMs
        if (data.rounds != null) msg.rounds = data.rounds
        const sDone = sessions[cid]
        if (sDone && liveStreams.get(cid) === entry && !entry.paused) sDone.interrupted = false
        break
      }
      default:
        break
    }
    if (cid === activeCid.value) scrollToBottom()
  }
}

/** PAUSED 落地：中断态标注（幂等，onClose 收尾时再兜底一次） */
function applyPausedState(cid, entry) {
  const msg = entry.aiMsg
  msg.streaming = false
  msg.interrupted = true
  entry.aiMsg.timeline.forEach((it) => {
    if (it.type === 'tool' && it.status === 'running') {
      it.status = 'interrupted'
      it.result = `工具调用被用户中断，未产生执行结果。\n原工具调用：${it.toolName}(${it.arguments || ''})`
    }
  })
  const s = sessions[cid]
  if (s && liveStreams.get(cid) === entry) s.interrupted = true
  markConversationIdle(cid)
}

/** SSE 回调：断线自动重连（退避 1s/2s/3s），404 视为流已结束 */
function streamHandlers(cid, entry) {
  return {
    onError(err) {
      console.error('[chat] 流式请求失败', err)
      // 服务端已无进行中的流（断开期间跑完了）：按已结束处理，拉最终历史
      if (err.status === 404 || (err.message || '').includes('HTTP 404')) {
        finishStream(cid, entry)
        if (cid === activeCid.value) loadConversation(cid)
        refreshConversationList()
        return
      }
      // 409 = takeOver 等待超时 / 400 等：直接展示错误
      if (err.status === 409 || err.status === 400 || err.status === 401 || err.status === 403) {
        entry.aiMsg.timeline.push({ type: 'error', message: err.message || '请求被拒绝' })
        entry.aiMsg.streaming = false
        finishStream(cid, entry)
        return
      }
      // 流已收到过事件才自动重连（增量续传）；还没收到过说明首发就失败
      if (entry.lastSeq > 0 && entry.retries < 3) {
        entry.retries++
        setTimeout(() => {
          if (liveStreams.get(cid) !== entry) return // 已被新一轮替换
          openAgentStream(
            { conversationId: cid, recovery: true, startSeq: entry.lastSeq + 1 },
            onStreamEvent(cid, entry),
            streamHandlers(cid, entry)
          ).then((ctrl) => {
            if (liveStreams.get(cid) === entry) entry.controller = ctrl
          }).catch(() => {})
        }, 1000 * entry.retries)
        return
      }
      if (err.name === 'AbortError') {
        // 本地主动 abort（停止按钮/切走）：服务端已收到 stop，走 onClose 收尾
        return
      }
      entry.aiMsg.timeline.push({ type: 'error', message: '连接中断', detail: err.message || '' })
      entry.aiMsg.streaming = false
      finishStream(cid, entry)
    },
    onClose() {
      const stillCurrent = liveStreams.get(cid) === entry
      // 被打断的流：先兜底标注中断态（PAUSED 事件可能因本地 abort 而错过）
      if (entry.paused) applyPausedState(cid, entry)
      finishStream(cid, entry)
      // 服务端已终态落库，刷新侧栏；被打断的会话在刷新后强制归位
      refreshConversationList().finally(() => {
        if (entry.paused) markConversationIdle(cid)
      })
      if (stillCurrent && cid === activeCid.value) scrollToBottom()
    }
  }
}

/** 流结束（完成/停止/彻底断开）：清理注册表，恢复界面状态（identity 守卫防误清 takeOver 新流） */
function finishStream(cid, entry) {
  if (liveStreams.get(cid) === entry) liveStreams.delete(cid)
  entry.aiMsg.streaming = false
  const s = sessions[cid]
  // 只有当没有接管中的新流时才把会话置为空闲
  if (s && !liveStreams.has(cid)) s.streaming = false
}

function markConversationIdle(cid) {
  // takeOver 场景：旧流被打断但新流仍在跑（liveStreams 还有该会话）时不能误标空闲
  const item = conversations.value.find((c) => c.conversationId === cid)
  if (item && !liveStreams.has(cid)) item.streaming = false
  const s = sessions[cid]
  if (s && !liveStreams.has(cid)) s.streaming = false
}

// ── 发送 / 停止 ──

// 待发送附件：{localId, fileId, fileName, fileType, embed, status: uploading|done|failed, progress, error, abort}
const pendingFiles = reactive([])
const fileInputRef = ref(null)
let localFileSeq = 0

function onUploadClick() {
  fileInputRef.value?.click()
}

/** 选中文件立即上传（选择即在 pending 区可见，发送时携带 fileIds） */
function onFilesSelected(e) {
  const files = Array.from(e.target.files || [])
  e.target.value = ''
  if (!files.length) return
  const overflow = pendingFiles.length + files.length - MAX_FILES_PER_TURN
  if (overflow > 0) {
    ElMessage.warning(`单轮最多上传 ${MAX_FILES_PER_TURN} 个文件，已忽略多出的 ${overflow} 个`)
  }
  for (const file of files.slice(0, Math.max(0, MAX_FILES_PER_TURN - pendingFiles.length))) {
    startUpload(file)
  }
}

function startUpload(file) {
  const item = reactive({
    localId: ++localFileSeq,
    fileId: '',
    fileName: file.name,
    fileType: (file.name.split('.').pop() || '').toLowerCase(),
    embed: 0,
    status: 'uploading',
    progress: 0,
    error: '',
    abort: null
  })
  pendingFiles.push(item)
  const { abort } = uploadFile(file, (percent) => {
    item.progress = percent
  })
    .then((resp) => {
      item.fileId = resp.fileId
      item.embed = resp.embed
      item.status = 'done'
      item.progress = 100
    })
    .catch((err) => {
      item.status = 'failed'
      item.error = err?.message || '上传失败'
    })
  item.abort = abort
}

/** 移除待发送附件：已上传成功则顺带删除服务端文件 */
async function removePending(item) {
  const idx = pendingFiles.indexOf(item)
  if (idx >= 0) pendingFiles.splice(idx, 1)
  if (item.status === 'uploading') {
    item.abort?.()
    return
  }
  if (item.status === 'done' && item.fileId) {
    try {
      await deleteFile(item.fileId)
    } catch {
      /* 服务端删除失败不阻断界面，文件留存由用户在 MinIO/DB 层面管理 */
    }
  }
}

/** 附件点击预览（新窗口；浏览器按 MIME 决定展示或下载） */
function openPreview(att) {
  if (!att?.fileId) return
  window.open(filePreviewUrl(att.fileId), '_blank')
}

const sendDisabled = computed(() => {
  const s = activeSession.value
  if (pendingFiles.some((p) => p.status === 'uploading')) return true // 附件上传中禁止发送
  if (pendingFiles.some((p) => p.status === 'failed')) return true // 失败附件需先移除
  return (!draft.value.trim() && !s?.interrupted) || (s?.streaming && !draft.value.trim())
})

/** 发送按钮 Tooltip 文案（空发送可从断点恢复） */
const sendTip = computed(() => {
  if (activeSession.value?.interrupted && !draft.value.trim()) return '从断点恢复（Enter 亦可）'
  return '发送（Enter）'
})

function send() {
  const cid = activeCid.value
  const s = ensureSession(cid)
  const query = draft.value.trim()
  // 会话存在中断快照时允许空发送（纯恢复：本地工具重放续跑，docs/03 M3 B02 语义）
  if (!query && !s.interrupted) return
  const hasPendingDone = pendingFiles.some((p) => p.status === 'done')
  if (!query && hasPendingDone) {
    ElMessage.warning('已上传文件，请输入关于文件的问题再发送')
    return
  }
  if (liveStreams.has(cid) && liveStreams.get(cid).lastSeq === 0) {
    // 首个事件都没到的极短窗口内不连发（避免抖动双击）
    return
  }

  // 本次上传组：发送即随请求携带 fileIds，并清空 pending 区
  const fileIds = pendingFiles.filter((p) => p.status === 'done' && p.fileId).map((p) => p.fileId)
  const attachments = pendingFiles
    .filter((p) => p.status === 'done' && p.fileId)
    .map((p) => ({ fileId: p.fileId, fileName: p.fileName, fileType: p.fileType, fileSize: null }))
  pendingFiles.splice(0, pendingFiles.length)

  if (!s.title) s.title = genTitle(query || '从断点恢复')
  draft.value = ''
  s.interrupted = false
  s.loaded = true
  if (query || attachments.length) {
    s.messages.push({ role: 'user', content: query, attachments })
  }
  const aiMsg = newAssistantMsg()
  s.messages.push(aiMsg)
  autoScroll.value = true
  openStream(cid, aiMsg, { query, conversationId: cid, fileIds, online: online.value }, query)
  syncConversationEntry(cid, query)
}

/** 发送瞬间刷新侧栏；万一服务端记录还没可见就本地补一条，保证马上能点回这个会话 */
async function syncConversationEntry(cid, title) {
  await refreshConversationList()
  if (conversations.value.some((c) => c.conversationId === cid)) return
  const now = new Date().toISOString()
  conversations.value.unshift({
    conversationId: cid,
    title: genTitle(title),
    messageCount: 1,
    createdAt: now,
    lastActiveAt: now,
    streaming: true
  })
}

async function onStop() {
  const cid = activeCid.value
  const entry = liveStreams.get(cid)
  if (!entry) return
  try {
    // 后端 interrupt 在 /agent/stop 返回前已同步发出 PAUSED 并关流；
    // SSE 收到 PAUSED 后由 onClose 统一收尾，本地不抢着 abort（避免丢 PAUSED 事件）
    await stopAgent(cid)
  } catch {
    // 打断请求失败才本地断开（流可能仍在跑，auto-reconnect 会接回）
    entry.controller?.abort?.()
  }
}

async function onDeleteConversation(cid) {
  if (!window.confirm('确认删除该会话？删除后不可恢复。')) return
  try {
    await deleteSession(cid)
    if (cid === activeCid.value) newConversation()
    await refreshConversationList()
    ElMessage.success('已删除')
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

// ── 历史列表 ──
async function refreshConversationList() {
  return loadConversations(true)
}

async function loadConversations(reset = true) {
  if (conversationsLoading.value) return
  if (!reset && !conversationsHasMore.value) return
  conversationsLoading.value = true
  try {
    const page = reset ? 0 : conversationsPage.value + 1
    const p = await listSessions(page, CONV_PAGE_SIZE)
    const items = Array.isArray(p?.conversations) ? p.conversations : []
    if (reset) {
      conversations.value = items
    } else {
      const existIds = new Set(conversations.value.map((c) => c.conversationId))
      conversations.value = conversations.value.concat(items.filter((c) => !existIds.has(c.conversationId)))
    }
    conversationsPage.value = p?.page ?? page
    conversationsHasMore.value = !!p?.hasMore
  } catch (e) {
    console.warn('[chat] 加载历史会话失败', e)
  } finally {
    conversationsLoading.value = false
  }
}

function formatConvTime(isoStr) {
  if (!isoStr) return ''
  try {
    const d = new Date(isoStr.replace(' ', 'T'))
    const diffMs = Date.now() - d.getTime()
    const diffMin = Math.floor(diffMs / 60000)
    const diffHour = Math.floor(diffMs / 3600000)
    const diffDay = Math.floor(diffMs / 86400000)
    if (diffMin < 1) return '刚刚'
    if (diffMin < 60) return diffMin + ' 分钟前'
    if (diffHour < 24) return diffHour + ' 小时前'
    if (diffDay < 7) return diffDay + ' 天前'
    return d.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })
  } catch {
    return ''
  }
}

// ── 渲染辅助 ──
function renderMd(text) {
  return md.render(text)
}

function completedCount(items) {
  return (items || []).filter((t) => t.status === 'completed').length
}

function hasTailText(msg) {
  const last = msg.timeline[msg.timeline.length - 1]
  return (last && (last.type === 'text' || last.type === 'error')) || msg.interrupted
}

// ── 执行过程聚合组：连续 thinking/tool 压成一条折叠条，避免多轮工具把消息拉得过长 ──

/** 组状态挂 WeakMap（键=组首项）：timeline 增长重算分组时，用户展开态/选中态不丢 */
const pgStates = new WeakMap()

function pgState(lead) {
  let st = pgStates.get(lead)
  if (!st) {
    st = reactive({ userOpen: null, selected: null, rawView: false })
    pgStates.set(lead, st)
  }
  return st
}

/** timeline 分块：连续 thinking/tool 归为一个执行过程组，text/todo/error 独立成块并断开分组 */
function groupedTimeline(msg) {
  const blocks = []
  let cur = null
  for (const it of msg.timeline) {
    if (it.type === 'thinking' || it.type === 'tool') {
      if (!cur) {
        cur = { group: true, lead: it, items: [] }
        blocks.push(cur)
      }
      cur.items.push(it)
    } else {
      cur = null
      blocks.push({ group: false, item: it })
    }
  }
  return blocks
}

function pgTools(block) {
  return block.items.filter((it) => it.type === 'tool')
}

function pgDoneCount(block) {
  return pgTools(block).filter((t) => t.status === 'completed').length
}

function pgRunningTool(block) {
  return pgTools(block).find((t) => t.status === 'running')
}

/** 流式自动模式（极简单行）；用户点过展开/收起后进入手动模式，不再自动切换 */
function pgCompact(msg, block) {
  return pgState(block.lead).userOpen === null && pgIsRunning(msg, block)
}

/** 极简行里点"已完成 N 步/异常"：展开全量徽章流 */
function openGroup(block) {
  pgState(block.lead).userOpen = true
}

/** 组是否仍在执行：有 running 工具，或整条流的最后一个事件还停在组内（思考进行中） */
function pgIsRunning(msg, block) {
  if (!msg.streaming) return false
  if (pgTools(block).some((t) => t.status === 'running')) return true
  const last = msg.timeline[msg.timeline.length - 1]
  return block.items.includes(last)
}

/** 组展开态：用户点过的以用户为准；否则只在执行中自动展开（正文开始输出/流结束即自动收起） */
function pgExpanded(msg, block) {
  const st = pgState(block.lead)
  if (st.userOpen !== null) return st.userOpen
  return pgIsRunning(msg, block)
}

function toggleGroup(msg, block) {
  const st = pgState(block.lead)
  st.userOpen = !pgExpanded(msg, block)
  if (!st.userOpen) st.selected = null
}

/** 点击徽章：切换选中步骤（工具结果 / 思考内容在徽章流下方展示） */
function toggleStep(block, step) {
  const st = pgState(block.lead)
  st.selected = st.selected === step ? null : step
}

function pgIssueCount(block) {
  return pgTools(block).filter((t) => t.status === 'interrupted' || t.status === 'failed').length
}

/**
 * 工具结果格式化（仅展示层，不改后端数据）：
 * 1) 还原双重转义——后端序列化把换行/引号转成字面转义序列，SSE 解析一次后仍残留 \n、\"
 * 2) JSON 内容美化缩进（tool_search / executeSql 的 resultSchema 等结构化返回）
 */
function formatToolResult(raw) {
  if (raw == null) return ''
  let text = String(raw)
  if (text.includes('\\n') || text.includes('\\"')) {
    text = text
      .replace(/\\r\\n/g, '\n')
      .replace(/\\n/g, '\n')
      .replace(/\\t/g, '  ')
      .replace(/\\"/g, '"')
      .replace(/\\\\/g, '\\')
  }
  const trimmed = text.trim()
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try {
      text = JSON.stringify(JSON.parse(trimmed), null, 2)
    } catch {
      /* 非 JSON 原样展示 */
    }
  }
  return text
}

/**
 * markdown 特征检测（宽松）：命中任一特征即按渲染视图展示，可切"原文"回退。
 * JSON 美化结果不含这些特征，自然走纯文本分支。
 */
function isMarkdownish(text) {
  if (!text) return false
  return (
    /^#{1,6}\s/m.test(text) || // 标题
    /^\s*[-*]\s+\S/m.test(text) || // 无序列表
    /^\s*\d+\.\s+\S/m.test(text) || // 有序列表
    /^```/m.test(text) || // 代码围栏
    /\n\|.+\|\s*\n\|?[\s:|-]+\|/m.test(text) || // 表格
    /\*\*[^*\n]+\*\*/.test(text) // 加粗
  )
}

function pgMetaText(msg, block) {
  const tools = pgTools(block)
  const run = tools.find((t) => t.status === 'running')
  const done = tools.filter((t) => t.status === 'completed').length
  if (run) return `${done}/${tools.length} · 正在 ${run.toolName}`
  if (!tools.length) return pgIsRunning(msg, block) ? '思考中…' : '思考'
  const issues = pgIssueCount(block)
  const base = issues ? `${tools.length} 步 · ${issues} 项异常` : `${tools.length} 步`
  // 回答结束后展示总耗时（COMPLETE 事件带回；正在跑时不显示）
  if (msg.elapsedMs != null && !pgIsRunning(msg, block)) {
    return `${base} · 耗时 ${(msg.elapsedMs / 1000).toFixed(1)}s`
  }
  return base
}

function pgProgress(block) {
  const tools = pgTools(block)
  return tools.length ? Math.round((tools.filter((t) => t.status === 'completed').length / tools.length) * 100) : 0
}

function pgSelectedDetail(block) {
  const sel = pgState(block.lead).selected
  if (!sel) return null
  if (sel.type === 'thinking' && sel.content) return 'thinking'
  if (sel.type === 'tool' && sel.result) return 'result'
  return null
}

function onListScroll() {
  const el = listRef.value
  if (!el) return
  autoScroll.value = el.scrollHeight - el.scrollTop - el.clientHeight < SCROLL_BOTTOM_THRESHOLD
}

function scrollToBottom() {
  nextTick(() => {
    const el = listRef.value
    if (el && autoScroll.value) el.scrollTop = el.scrollHeight
  })
}

// ── 生命周期 ──
onMounted(async () => {
  const u = new URL(window.location.href)
  const urlCid = u.searchParams.get('conversationId')
  await refreshConversationList()
  if (urlCid) {
    await openConversation(urlCid)
  } else {
    newConversation()
  }
  // 探测联网能力：后端未配 tavily.api-key 时置灰开关（开了也不会注入工具）
  try {
    const caps = await getAgentCapabilities()
    onlineAvailable.value = !!caps.onlineAvailable
    if (!onlineAvailable.value) online.value = false
  } catch {
    /* 探测失败保持默认可用（发送时后端仍有降级兜底） */
  }
})
</script>

<style scoped>
/* ── 思考折叠区正文 ── */
.think-body {
  margin-top: 6px;
  padding-left: 19px;
  max-height: 240px;
  overflow-y: auto;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
  white-space: pre-wrap;
}

/* ── 工具卡片 ── */
.tool-icon.running {
  color: #f59e0b;
  animation: spin 1s linear infinite;
}

.tool-icon.completed {
  color: #10b981;
}

.tool-icon.interrupted,
.tool-icon.failed {
  color: #ef4444;
}

.tool-result {
  width: 100%;
  margin: 6px 0 0;
  padding: 10px 12px;
  background: #f9fafb;
  border: 1px solid #f3f4f6;
  color: #4b5563;
  border-radius: 10px;
  font-size: 11px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  line-height: 1.6;
  max-height: 208px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  cursor: default;
}

/* 工具结果的 markdown 渲染视图：复用 .chat-md 内容样式，容器限高滚动 */
.tool-md {
  max-height: 320px;
  overflow: auto;
  margin-top: 6px;
  padding: 10px 12px;
  background: #f9fafb;
  border: 1px solid #f3f4f6;
  border-radius: 10px;
  font-size: 12px;
}

.tool-detail-bar {
  display: flex;
  justify-content: flex-end;
  margin-top: 6px;
}

.tool-view-toggle {
  border: 1px solid #e5e7eb;
  background: #fff;
  color: #6b7280;
  border-radius: 6px;
  padding: 2px 8px;
  font-size: 11px;
  cursor: pointer;
}

.tool-view-toggle:hover {
  color: #2563eb;
  border-color: #93c5fd;
}

/* ── 执行过程聚合组（方案A） ── */
.pg-header {
  display: flex;
  align-items: center;
  gap: 6px;
  width: fit-content;
  margin-left: -8px;
  padding: 4px 8px;
  border-radius: 8px;
  font-size: 12px;
  color: #9ca3af;
  cursor: pointer;
  transition: background-color 0.2s, color 0.2s;
}

.pg-header:hover {
  background-color: #f9fafb;
  color: #6b7280;
}

.pg-icon.done {
  color: #10b981;
}

.pg-icon.running {
  color: #f59e0b;
  animation: spin 1s linear infinite;
}

.pg-icon.issue {
  color: #ef4444;
}

.pg-meta {
  color: #b3bac1;
}

/* 执行中头部右侧的细进度条 */
.pg-bar {
  width: 88px;
  height: 3px;
  overflow: hidden;
  background: #e5e7eb;
  border-radius: 2px;
}

.pg-bar i {
  display: block;
  height: 100%;
  background: #3b82f6;
  border-radius: 2px;
  transition: width 0.3s;
}

/* 徽章流：横向流式换行，点击看该步结果/思考 */
.pg-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}

.pg-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 9px;
  border: 1px solid transparent;
  border-radius: 999px;
  background: #f9fafb;
  font-size: 11px;
  color: #6b7280;
  cursor: pointer;
  transition: background-color 0.15s, color 0.15s, border-color 0.15s;
}

.pg-badge:hover {
  background: #f3f4f6;
}

.pg-badge.running {
  background: #fffbeb;
  color: #d97706;
}

/* 极简行的异常计数徽章 */
.pg-badge.issue {
  background: #fef2f2;
  color: #dc2626;
}

/* 非交互徽章（运行中/思考中/异常计数中不可点的）：不显示手型 */
span.pg-badge {
  cursor: default;
}

.pg-badge.sel {
  border-color: #93c5fd;
  background: #eff6ff;
  color: #2563eb;
}

.pg-think-icon {
  color: #a78bfa;
}

.pg-think-icon.is-loading {
  color: #f59e0b;
}

/* 选中步骤详情与徽章流的间距 */
.pg-body .tool-result,
.pg-body .think-body {
  margin-top: 8px;
}

/* ── todo 面板 ── */
.todo-icon.completed {
  color: #10b981;
}

.todo-icon.in_progress {
  color: #3b82f6;
}

.todo-icon.pending {
  color: #d1d5db;
}

/* ── AI 气泡内 timeline 元素节奏 ── */
.chat-timeline > * + * {
  margin-top: 8px;
}

/* ── markdown 正文：浅色系、斑马纹表格、浅灰代码块 ── */
.chat-md {
  font-size: 14px;
  line-height: 1.7;
  color: #374151;
  word-break: break-word;
}

.chat-md :deep(p) {
  margin: 6px 0;
}

.chat-md :deep(h1),
.chat-md :deep(h2),
.chat-md :deep(h3) {
  margin: 14px 0 8px;
  font-size: 15px;
  font-weight: 600;
  color: #111827;
}

.chat-md :deep(strong) {
  color: #111827;
  font-weight: 600;
}

.chat-md :deep(a) {
  color: #2563eb;
}

/* M6 图表图片（mcp-echarts 外链）：约束在气泡宽度内不撑破布局，点击经包层 <a> 看原图 */
.chat-md :deep(img) {
  display: block;
  box-sizing: border-box;
  max-width: 100%;
  height: auto;
  margin: 10px 0;
  border: 1px solid #f3f4f6;
  border-radius: 10px;
}

.chat-md :deep(table) {
  width: 100%;
  margin: 10px 0;
  border-collapse: collapse;
  border: 1px solid #f3f4f6;
  border-radius: 8px;
  font-size: 13px;
  display: block;
  overflow-x: auto;
}

.chat-md :deep(th) {
  background: #f9fafb;
  padding: 8px 12px;
  text-align: left;
  font-weight: 600;
  color: #4b5563;
  border-bottom: 1px solid #e5e7eb;
  white-space: nowrap;
}

.chat-md :deep(td) {
  padding: 8px 12px;
  color: #4b5563;
  border-bottom: 1px solid #f3f4f6;
}

.chat-md :deep(tbody tr:nth-child(even)) {
  background: #f9fafb;
}

.chat-md :deep(tbody tr:last-child td) {
  border-bottom: none;
}

.chat-md :deep(code) {
  background: #f3f4f6;
  color: #1f2937;
  padding: 2px 6px;
  border-radius: 6px;
  font-size: 12px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.chat-md :deep(pre) {
  background: #f3f4f6;
  color: #1f2937;
  padding: 12px 14px;
  border-radius: 10px;
  overflow-x: auto;
  font-size: 12px;
  line-height: 1.6;
}

.chat-md :deep(pre code) {
  background: transparent;
  color: inherit;
  padding: 0;
}

.chat-md :deep(blockquote) {
  margin: 8px 0;
  padding: 2px 12px;
  border-left: 2px solid #e5e7eb;
  color: #6b7280;
}

.chat-md :deep(ul),
.chat-md :deep(ol) {
  padding-left: 20px;
  margin: 6px 0;
}

.chat-md :deep(hr) {
  border: none;
  border-top: 1px solid #f3f4f6;
  margin: 12px 0;
}

.chat-cursor {
  display: inline-block;
  color: #2563eb;
  animation: blink 1s step-start infinite;
}

/* ── 输入区：无边框 textarea ── */
.chat-textarea :deep(.el-textarea__inner) {
  border: none;
  box-shadow: none;
  background: transparent;
  padding: 7px 6px;
  font-size: 14px;
  line-height: 1.6;
  color: #1f2937;
}

.chat-textarea :deep(.el-textarea__inner::placeholder) {
  color: #9ca3af;
}

.chat-textarea :deep(.el-textarea__inner:focus) {
  border: none;
  box-shadow: none;
}

/* el-progress 百分比文字收紧 */
.input-shell :deep(.el-progress__text) {
  font-size: 11px !important;
  color: #9ca3af;
  min-width: 32px !important;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

@keyframes blink {
  50% { opacity: 0; }
}
</style>
