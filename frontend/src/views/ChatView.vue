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
    <section class="relative flex min-w-0 flex-1 flex-col">
      <!-- 对话内时间线导航：纯浮层挂在聊天区左缘（不占布局列），每轮提问一条，点击跳转 -->
      <TimelineNav
        v-if="activeSession?.messages?.length"
        :rounds="timelineRounds"
        :active-index="activeRoundIndex"
        @jump="scrollToMessage"
      />
      <!-- 消息流：max-w-6xl 居中，不贴边 -->
      <div ref="listRef" class="thin-scroll min-h-0 flex-1 overflow-y-auto [scrollbar-gutter:stable_both-edges]" @scroll="onListScroll">
        <div class="mx-auto flex w-full max-w-6xl flex-col gap-5 px-6 py-8">
          <template v-for="(msg, i) in activeSession?.messages || []" :key="i">
            <!-- 用户消息（含附件 chips：图片缩略图 / 文件图标，点击预览）：靠右 -->
            <div v-if="msg.role === 'user'" class="flex flex-col items-end" :data-msg-index="i">
              <div
                class="max-w-[85%] whitespace-pre-wrap rounded-2xl rounded-tr-sm bg-blue-600 px-5 py-3 text-sm leading-relaxed text-white shadow-sm"
              >
                <!-- 业务文件上传行（发送时注入 query 的线上格式，LLM 绑定链路依赖 URL）：渲染层剥离成 chip -->
                <div v-if="msgBizFiles(msg).length" class="mb-2 flex flex-wrap gap-2">
                  <div
                    v-for="(bf, bfi) in msgBizFiles(msg)"
                    :key="bfi"
                    class="flex max-w-[240px] cursor-pointer items-center gap-2 rounded-lg bg-white/15 px-2.5 py-1.5 transition-colors duration-200 hover:bg-white/25"
                    :title="`已上传主平台：${bf.fileName}`"
                    @click="openBizFile(bf)"
                  >
                    <img
                      v-if="isImageType(bf.fileName.split('.').pop())"
                      :src="bf.url"
                      class="h-9 w-9 shrink-0 rounded-md object-cover"
                      alt=""
                    />
                    <el-icon v-else :size="18" class="shrink-0"><Document /></el-icon>
                    <span class="truncate text-xs">{{ bf.fileName }}</span>
                  </div>
                </div>
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
                <div v-if="msgSkill(msg)" class="mb-1.5 w-fit rounded-md bg-white/20 px-2 py-0.5 text-[11px]">
                  ⚡ {{ msgSkill(msg) }}
                </div>
                <div>{{ msgContent(msg) }}</div>
              </div>
              <div v-if="msg.ts" class="msg-time">{{ formatMsgTime(msg.ts) }}</div>
            </div>

            <!-- 助手消息：白色卡片 + 微阴影，timeline 驱动（thinking / tool / card / text / error）：靠左（执行计划在右侧独立面板） -->
            <div v-else class="flex flex-col items-start" :data-msg-index="i">
              <div class="chat-timeline w-full rounded-2xl rounded-tl-sm border border-gray-100 bg-white px-5 py-4 shadow-sm">
                <!-- 占位：流式刚发起、第一个 SSE 事件还没到（卡片正文内的柔和过渡，内容到达后被替换） -->
                <div v-if="msg.streaming && !msg.timeline.length" class="think-placeholder">
                  <el-icon :size="13" class="pg-think-icon is-loading"><Loading /></el-icon>
                  <span>正在思考</span>
                  <span class="dot-flashing" aria-hidden="true"><i></i><i></i><i></i></span>
                </div>
                <template v-for="(entry, bi) in msgBlocks(msg)" :key="bi">
                  <!-- 完成后过程折叠摘要头：已工作 · 总耗时（点击展开回看全过程，答案保持可见） -->
                  <button
                    v-if="entry.kind === 'summary'"
                    type="button"
                    class="work-summary"
                    @click="toggleWork(msg)"
                  >
                    <span>{{ workSummaryText(msg) }}</span>
                    <el-icon :size="11" class="transition-transform duration-200" :class="workCollapsed(msg) ? '' : 'rotate-180'">
                      <ArrowDown />
                    </el-icon>
                  </button>

                  <!-- 工具段：段内有运行中→只显示运行中直播行（参数一行滚动、点击展开参数全文），同段已完成工具先隐藏；
                       全部完成→汇聚成组（组头按实际工具动态组装），点击某行结果就地展开在该行下方 -->
                  <div v-else-if="entry.block.group">
                    <!-- 直播行：正在执行（loading + 参数单行滚动）/ 刚完成最短停留（绿勾 + 耗时），到点自动折入下方组 -->
                    <template v-if="pgLiveTools(msg, entry.block).length">
                      <template v-for="(step, si) in pgLiveTools(msg, entry.block)" :key="si">
                        <div
                          class="tool-row live-row"
                          :class="{ sel: pgSel(entry.block) === step }"
                          @click="toggleStep(entry.block, step)"
                        >
                          <el-icon v-if="step.status === 'running'" :size="13" class="tool-icon running"><Loading /></el-icon>
                          <span v-else class="tool-emoji">🔧</span>
                          <span class="tool-row-name">{{ step.status === 'running' ? `正在执行 · ${toolLabel(step.toolName)}` : toolLabel(step.toolName) }}</span>
                          <span class="live-line" v-think-scroll>{{ liveArgsTail(step.arguments) }}</span>
                          <span class="tool-row-dur">{{ step.status === 'running' ? preciseElapsed(step.startAt) : toolDurText(step) }}</span>
                        </div>
                        <!-- 直播行展开：参数全文 -->
                        <pre v-if="pgSel(entry.block) === step" class="tool-result thin-scroll">{{ formatToolArgs(step.arguments) }}</pre>
                      </template>
                    </template>
                    <template v-else>
                      <!-- 单工具段：不折叠成组，直接平铺（格式同组头：🔍 查阅 + 工具名 + 参数文案），点击行就地展开结果原文 -->
                      <template v-if="entry.block.items.length === 1">
                        <template v-for="(step, si) in entry.block.items" :key="si">
                          <div
                            class="tool-row"
                            :class="{ sel: pgSel(entry.block) === step }"
                            @click="toggleStep(entry.block, step)"
                          >
                            <span class="tool-emoji">🔍</span>
                            <span class="tool-row-action">查阅</span>
                            <span class="tool-row-name font-mono">{{ step.toolName }}</span>
                            <span class="tool-row-preview font-mono">{{ argPreview(step.arguments) }}</span>
                            <span v-if="toolDurText(step)" class="tool-row-dur">{{ toolDurText(step) }}</span>
                          </div>
                          <pre v-if="pgSel(entry.block) === step && step.result" class="tool-result thin-scroll">{{ formatToolResult(step.result) }}</pre>
                        </template>
                      </template>
                      <template v-else>
                        <button type="button" class="pg-header" @click="toggleGroup(entry.block)">
                          <span class="tool-emoji">🔍</span>
                          <span>{{ pgHeaderText(entry.block) }}</span>
                          <span class="pg-meta">{{ pgMetaText(entry.block) }}</span>
                          <el-icon :size="11" class="transition-transform duration-200" :class="pgOpen(entry.block) ? 'rotate-180' : ''">
                            <ArrowDown />
                          </el-icon>
                        </button>
                        <div v-if="pgOpen(entry.block)" class="pg-body">
                          <template v-for="(step, si) in entry.block.items" :key="si">
                            <div
                              class="tool-row"
                              :class="{ sel: pgSel(entry.block) === step }"
                              @click="toggleStep(entry.block, step)"
                            >
                              <span class="tool-row-action">工具</span>
                              <span class="tool-row-name font-mono">{{ step.toolName }}</span>
                              <span class="tool-row-preview font-mono">{{ argPreview(step.arguments) }}</span>
                              <span v-if="toolDurText(step)" class="tool-row-dur">{{ toolDurText(step) }}</span>
                            </div>
                            <!-- 选中条目详情：就地展开在该工具行下方（统一原文展示） -->
                            <pre v-if="pgSel(entry.block) === step && step.result" class="tool-result thin-scroll">{{ formatToolResult(step.result) }}</pre>
                          </template>
                        </div>
                      </template>
                    </template>
                  </div>

                  <!-- 思考块：进行中 header 一行不展开、思考内容在行内横向滚动；点击展开才在下方区域显示全文 -->
                  <div v-else-if="entry.block.item.type === 'thinking'" class="think-block">
                    <button type="button" class="think-block-header" @click="toggleThink(entry.block)">
                      <el-icon v-if="thinkLive(msg, entry.block.item)" :size="13" class="pg-think-icon is-loading"><Loading /></el-icon>
                      <span v-else class="think-icon">🧠</span>
                      <span class="think-header-label">{{ thinkHeaderText(msg, entry.block.item) }}</span>
                      <!-- 进行中：单行滚动尾（横向钉最右，始终呈现"正在写"的最新内容） -->
                      <span v-if="thinkLive(msg, entry.block.item)" class="live-line" v-think-scroll>{{ thinkTail(entry.block.item.content) }}</span>
                      <el-icon :size="11" class="ml-auto transition-transform duration-200" :class="thinkIsOpen(entry.block) ? 'rotate-180' : ''">
                        <ArrowDown />
                      </el-icon>
                    </button>
                    <!-- 展开回看（进行中/已完成统一）：全文区域；进行中内容增长自动跟随滚底（用户上翻即暂停） -->
                    <div v-if="thinkIsOpen(entry.block)" class="think-body" v-think-pin="thinkLive(msg, entry.block.item)">{{ entry.block.item.content }}</div>
                  </div>

                  <!-- M-E1 业务卡片（工具结果 __card 提取）：W2 确认卡（D2：按钮=注入用户消息，经 LLM 单路径） -->
                  <!-- 执行计划已移到聊天区右侧独立面板（TodoWrite 参数侧信道 → 会话级 plan 状态），不再进消息 timeline -->
                  <div
                    v-else-if="entry.block.item.type === 'card' && entry.block.item.card?.__card === 'confirm'"
                    class="biz-card confirm-card"
                  >
                    <div class="biz-card-title">
                      <el-icon :size="14" class="text-amber-500"><WarningFilled /></el-icon>
                      <span>{{ entry.block.item.card.title || '高危操作确认' }}</span>
                      <span class="ml-auto text-[11px] font-normal text-gray-400">
                        {{ Math.ceil((entry.block.item.card.expiresInSeconds || 300) / 60) }} 分钟内有效
                      </span>
                    </div>
                    <table class="biz-diff-table">
                      <thead>
                        <tr><th>类别</th><th>字段</th><th>从</th><th>改为</th></tr>
                      </thead>
                      <tbody>
                        <tr v-for="(d, di) in entry.block.item.card.diffs || []" :key="di">
                          <td>{{ d.category }}</td>
                          <td class="font-mono">{{ d.field }}</td>
                          <td class="text-gray-400">{{ d.from }}</td>
                          <td class="font-medium text-emerald-600">{{ d.to }}</td>
                        </tr>
                      </tbody>
                    </table>
                    <div class="biz-card-impact">
                      <div>影响：{{ entry.block.item.card.impact?.task }}（{{ entry.block.item.card.impact?.docNumber }}）</div>
                      <div class="text-gray-400">
                        台账状态 {{ entry.block.item.card.impact?.statusFrom }} → {{ entry.block.item.card.impact?.statusTo }}；{{ entry.block.item.card.impact?.note }}
                      </div>
                    </div>
                    <div class="mt-2.5 flex items-center gap-2">
                      <button
                        type="button"
                        class="biz-btn primary"
                        :disabled="activeSession?.streaming"
                        @click="sendText(`确认执行 ${entry.block.item.card.confirmToken}`)"
                      >
                        ✅ 确认执行
                      </button>
                      <button
                        type="button"
                        class="biz-btn"
                        :disabled="activeSession?.streaming"
                        @click="sendText(`取消 ${entry.block.item.card.confirmToken}`)"
                      >
                        取消
                      </button>
                      <span class="text-[11px] text-gray-300">点击即发送确认消息（与手打「确认」等效）</span>
                    </div>
                  </div>

                  <!-- 错误 -->
                  <el-alert
                    v-else-if="entry.block.item.type === 'error'"
                    :title="entry.block.item.message"
                    type="error"
                    :closable="false"
                  />

                  <!-- 正文 markdown -->
                  <div v-else-if="entry.block.item.type === 'text' && entry.block.item.content" class="chat-md report-md report-md--chat" :class="{ 'chat-md--placeholder': entry.block.item.placeholder }" v-html="renderMd(entry.block.item.content)"></div>
                </template>

                <span v-if="msg.streaming && msg.timeline.length && !hasTailText(msg)" class="chat-cursor">▍</span>
                <div
                  v-if="msg.interrupted"
                  class="mt-2 w-fit rounded-md bg-amber-50 px-2.5 py-1.5 text-xs text-amber-600"
                >
                  ⏸ 已中断 — 发送消息（可为空发送/继续）可从断点恢复
                </div>
              </div>
              <!-- 消息级状态行（独立块，不与工具/思考同卡片）：流式全程计时，COMPLETE 后随 streaming=false 消失 -->
              <div v-if="msg.streaming" class="live-status">
                <el-icon :size="12" class="pg-think-icon is-loading"><Loading /></el-icon>
                <span>工作中{{ liveStatusText(msg) }}</span>
              </div>
              <div v-if="msg.ts" class="msg-time">{{ formatMsgTime(msg.ts) }}</div>
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
        <div class="relative mx-auto w-full max-w-6xl">
          <!-- 模型选择面板：点击输入栏「模型」按钮弹出（点击面板外自动关闭） -->
          <div
            v-if="modelMenuVisible"
            class="model-picker-anchor absolute bottom-full left-0 z-20 mb-2 w-80 overflow-hidden rounded-xl border border-gray-100 bg-white shadow-lg"
          >
            <div class="border-b border-gray-50 px-3.5 py-2 text-[11px] font-medium text-gray-400">
              选择模型 — 本轮回答使用的大模型
            </div>
            <div class="thin-scroll max-h-72 overflow-y-auto py-1">
              <button
                v-for="m in chatModels"
                :key="m.code"
                type="button"
                class="flex w-full items-start gap-2.5 px-3.5 py-2 text-left transition-colors duration-100"
                :class="currentModel?.code === m.code ? 'bg-blue-50' : 'hover:bg-gray-50'"
                @click="selectModel(m)"
              >
                <el-icon :size="15" class="mt-0.5 shrink-0 text-blue-500"><Cpu /></el-icon>
                <span class="min-w-0 flex-1">
                  <span class="flex items-center gap-1.5">
                    <span class="block truncate text-[13px] font-medium leading-5 text-gray-700">{{ m.name }}</span>
                    <span v-if="m.isDefault" class="shrink-0 rounded bg-gray-100 px-1 text-[10px] leading-4 text-gray-400">默认</span>
                    <el-icon v-if="currentModel?.code === m.code" :size="13" class="ml-auto shrink-0 text-blue-500">
                      <CircleCheckFilled />
                    </el-icon>
                  </span>
                  <span v-if="m.remark || m.provider" class="mt-0.5 line-clamp-1 block text-[11px] leading-4 text-gray-400">
                    {{ m.remark || m.provider }}
                  </span>
                </span>
              </button>
            </div>
          </div>
          <!-- 斜杠技能选择面板：draft 为单个 "/token" 时弹出，↑↓/Enter/Esc/点击选择 -->
          <div
            v-if="skillMenu.visible"
            class="absolute bottom-full left-0 right-0 z-20 mb-2 overflow-hidden rounded-xl border border-gray-100 bg-white shadow-lg"
          >
            <div class="border-b border-gray-50 px-3.5 py-2 text-[11px] font-medium text-gray-400">
              技能 — 指定本轮回答使用的技能
            </div>
            <div class="thin-scroll max-h-64 overflow-y-auto py-1">
              <div v-if="!filteredSkills.length" class="px-3.5 py-3 text-xs text-gray-300">无匹配技能（Enter 按原文发送）</div>
              <button
                v-for="(s, si) in filteredSkills"
                :key="s.name"
                type="button"
                class="flex w-full items-start gap-2.5 px-3.5 py-2 text-left transition-colors duration-100"
                :class="si === skillMenu.index ? 'bg-blue-50' : 'hover:bg-gray-50'"
                @mousemove="skillMenu.index = si"
                @click="selectSkill(s)"
              >
                <el-icon :size="15" class="mt-0.5 shrink-0 text-blue-500"><MagicStick /></el-icon>
                <span class="min-w-0">
                  <span class="block text-[13px] font-medium leading-5 text-gray-700">{{ s.name }}</span>
                  <span v-if="s.description" class="mt-0.5 line-clamp-1 block text-[11px] leading-4 text-gray-400">{{ s.description }}</span>
                </span>
              </button>
            </div>
          </div>
          <!-- 业务文件（直传主平台）：上传后在对话里说明绑定目标，发送时注入结构化上传消息 -->
          <div v-if="bizFiles.length" class="mb-2.5 flex flex-col gap-1.5">
            <div
              v-for="f in bizFiles"
              :key="f.localId"
              class="flex items-center gap-3 rounded-xl border border-gray-100 bg-white px-3 py-2 shadow-sm"
            >
              <span class="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-blue-50 text-blue-500">
                <el-icon :size="17"><Document /></el-icon>
              </span>
              <div class="min-w-0 flex-1">
                <div class="truncate text-[13px] leading-5 text-gray-600" :title="f.fileName">{{ f.fileName }}</div>
                <div v-if="f.status === 'uploading'" class="flex items-center gap-1 text-[11px] text-gray-400">
                  <el-icon :size="11" class="is-loading"><Loading /></el-icon>直传主平台中…
                </div>
                <div v-else-if="f.status === 'failed'" class="text-[11px] text-red-500">{{ f.error || '上传失败' }}</div>
                <div v-else class="text-[11px] text-emerald-600">已上传主平台 · 在对话里说明要绑定的类别</div>
              </div>
              <button
                type="button"
                title="移除"
                class="flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-gray-300 transition-colors duration-200 hover:bg-gray-100 hover:text-red-500"
                @click="removeBizFile(f)"
              >
                <el-icon :size="12"><Close /></el-icon>
              </button>
            </div>
          </div>

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

          <!-- 添加上下文面板：点击输入栏「+」弹出（附件 / 业务文件 / 联网搜索，点击面板外自动关闭） -->
          <div
            v-if="ctxMenuVisible"
            class="ctx-menu-anchor absolute bottom-full left-0 z-20 mb-2 w-64 overflow-hidden rounded-xl border border-gray-100 bg-white shadow-lg"
          >
            <div class="border-b border-gray-50 px-3.5 py-2 text-[11px] font-medium text-gray-400">添加上下文 — 给本轮对话补充材料与能力</div>
            <div class="py-1">
              <button
                type="button"
                class="flex w-full items-start gap-2.5 px-3.5 py-2 text-left transition-colors duration-100 hover:bg-gray-50"
                @click="pickAttachment"
              >
                <el-icon :size="15" class="mt-0.5 shrink-0 text-blue-500"><Paperclip /></el-icon>
                <span class="min-w-0 flex-1">
                  <span class="block text-[13px] font-medium leading-5 text-gray-700">附件</span>
                  <span class="mt-0.5 block text-[11px] leading-4 text-gray-400">
                    上传本地文件（单轮最多 {{ MAX_FILES_PER_TURN }} 个，支持 PDF/Office/文本/图片，≤100MB）
                  </span>
                </span>
              </button>
              <button
                type="button"
                class="flex w-full items-start gap-2.5 px-3.5 py-2 text-left transition-colors duration-100 hover:bg-gray-50"
                @click="pickBizFile"
              >
                <el-icon :size="15" class="mt-0.5 shrink-0 text-blue-500"><UploadFilled /></el-icon>
                <span class="min-w-0 flex-1">
                  <span class="block text-[13px] font-medium leading-5 text-gray-700">业务文件</span>
                  <span class="mt-0.5 block text-[11px] leading-4 text-gray-400">直传主平台，用于补充/替换申报资料，上传后绑定类别</span>
                </span>
              </button>
              <!-- 联网开关：开关型操作不收起面板，右侧实时回显状态；后端未配 key 时置灰 -->
              <button
                type="button"
                class="flex w-full items-start gap-2.5 px-3.5 py-2 text-left transition-colors duration-100"
                :class="onlineAvailable ? 'hover:bg-gray-50' : 'cursor-not-allowed'"
                @click="onlineAvailable && (online = !online)"
              >
                <el-icon
                  :size="15"
                  class="mt-0.5 shrink-0"
                  :class="!onlineAvailable ? 'text-gray-300' : online ? 'text-blue-500' : 'text-gray-400'"
                ><Connection /></el-icon>
                <span class="min-w-0 flex-1">
                  <span class="block text-[13px] font-medium leading-5" :class="onlineAvailable ? 'text-gray-700' : 'text-gray-300'">联网搜索</span>
                  <span class="mt-0.5 block text-[11px] leading-4" :class="onlineAvailable ? 'text-gray-400' : 'text-gray-300'">
                    {{ onlineAvailable ? '开启后使用 Tavily 搜索实时信息' : '联网不可用：后端未配置 tavily.api-key，配置并重启后端后生效' }}
                  </span>
                </span>
                <span v-if="onlineAvailable" class="mt-0.5 flex shrink-0 items-center gap-0.5" :class="online ? 'text-blue-500' : 'text-gray-400'">
                  <el-icon v-if="online" :size="13"><CircleCheckFilled /></el-icon>
                  <span class="text-[11px] font-medium">{{ online ? '已开启' : '已关闭' }}</span>
                </span>
              </button>
              <!-- 深度思考开关：开关型操作不收起面板；关闭时后端按所选模型注入关思考请求参数 -->
              <button
                type="button"
                class="flex w-full items-start gap-2.5 px-3.5 py-2 text-left transition-colors duration-100 hover:bg-gray-50"
                @click="toggleThinking"
              >
                <el-icon
                  :size="15"
                  class="mt-0.5 shrink-0"
                  :class="thinking ? 'text-blue-500' : 'text-gray-400'"
                ><MagicStick /></el-icon>
                <span class="min-w-0 flex-1">
                  <span class="block text-[13px] font-medium leading-5" :class="thinking ? 'text-gray-700' : 'text-gray-300'">深度思考</span>
                  <span class="mt-0.5 block text-[11px] leading-4 text-gray-400">
                    {{ thinking ? '开启后模型先深度推理再回答，更准但更慢' : '已关闭：回答更快，复杂推理能力下降' }}
                  </span>
                </span>
                <span class="mt-0.5 flex shrink-0 items-center gap-0.5" :class="thinking ? 'text-blue-500' : 'text-gray-400'">
                  <el-icon v-if="thinking" :size="13"><CircleCheckFilled /></el-icon>
                  <span class="text-[11px] font-medium">{{ thinking ? '已开启' : '已关闭' }}</span>
                </span>
              </button>
            </div>
          </div>
          <!-- 输入容器：附件 | 无边框输入 | 圆形发送（textarea 单行高与按钮等高，天然居中对齐） -->
          <div
            class="input-shell rounded-2xl border border-gray-200 bg-white px-3 py-2 shadow-lg shadow-gray-200/60 transition-all duration-200 focus-within:border-blue-300"
          >
            <div class="flex items-center gap-2">
              <!-- 添加上下文：+ 号弹出菜单（附件 / 业务文件 / 联网搜索）；联网开启时常亮蓝底保留状态可见性 -->
              <el-tooltip content="添加上下文" placement="top" :show-after="300">
                <button
                  type="button"
                  class="ctx-menu-anchor flex h-9 w-9 shrink-0 items-center justify-center rounded-full transition-all duration-200 active:scale-90"
                  :class="online || ctxMenuVisible
                    ? 'bg-blue-50 text-blue-600 hover:bg-blue-100'
                    : 'text-gray-400 hover:bg-gray-100 hover:text-gray-600'"
                  @click.stop="toggleCtxMenu"
                >
                  <el-icon :size="18"><Plus /></el-icon>
                </button>
              </el-tooltip>
              <input ref="fileInputRef" type="file" multiple style="display: none" @change="onFilesSelected" />
              <input ref="bizFileInputRef" type="file" multiple style="display: none" @change="onBizFilesSelected" />
              <!-- 模型选择：本轮回答使用的对话模型；后端清单为空时隐藏（走默认），选中非默认时蓝色高亮 -->
              <el-tooltip
                v-if="chatModels.length"
                content="选择本轮回答使用的大模型"
                placement="top"
                :show-after="300"
              >
                <button
                  type="button"
                  class="online-toggle model-picker-anchor flex h-9 shrink-0 items-center gap-1.5 rounded-full px-2.5 text-xs transition-all duration-200"
                  :class="effectiveModelCode
                    ? 'bg-blue-50 text-blue-600 active:scale-95'
                    : 'text-gray-400 hover:bg-gray-100 hover:text-gray-600 active:scale-95'"
                  @click.stop="toggleModelMenu"
                >
                  <el-icon :size="14"><Cpu /></el-icon>
                  <span class="max-w-[96px] truncate">{{ currentModel?.name || '默认模型' }}</span>
                  <el-icon :size="10"><ArrowDown /></el-icon>
                </button>
              </el-tooltip>
              <!-- 深度思考快捷开关：常驻可见，与「添加上下文」菜单里的条目同状态；开启时蓝色高亮 -->
              <el-tooltip
                :content="thinking ? '深度思考已开启：模型先推理再回答，关闭响应更快' : '深度思考已关闭：开启后模型先推理再回答'"
                placement="top"
                :show-after="300"
              >
                <button
                  type="button"
                  class="thinking-toggle flex h-9 shrink-0 items-center gap-1.5 rounded-full px-2.5 text-xs transition-all duration-200"
                  :class="thinking
                    ? 'bg-blue-50 text-blue-600 active:scale-95'
                    : 'text-gray-400 hover:bg-gray-100 hover:text-gray-600 active:scale-95'"
                  @click.stop="toggleThinking"
                >
                  <el-icon :size="14"><MagicStick /></el-icon>
                  <span>深度思考</span>
                </button>
              </el-tooltip>
              <el-input
                ref="draftInputRef"
                v-model="draft"
                type="textarea"
                :autosize="{ minRows: 1, maxRows: 6 }"
                resize="none"
                class="chat-textarea"
                placeholder="输入问题，Enter 发送（回答进行中发送新消息将自动顶掉旧回答）；可先上传文件再提问；输 / 指定技能"
                @keydown="onDraftKeydown"
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

    <!-- 右侧：执行计划面板（TodoWrite 参数侧信道 → 会话级 plan 快照，跨轮取最新一份；无计划不渲染整列） -->
    <aside v-if="activePlan" class="flex w-[300px] shrink-0 flex-col border-l border-gray-100 bg-white">
      <!-- 面板头：标题 + 完成计数 + 折叠开关 -->
      <div class="flex shrink-0 items-center gap-1.5 border-b border-gray-100 px-4 py-3">
        <el-icon :size="14" class="text-blue-600"><List /></el-icon>
        <span class="text-[13px] font-semibold text-gray-700">执行计划</span>
        <span class="ml-auto font-mono text-[11px]" :class="planAllDone ? 'text-emerald-600' : 'text-gray-400'">
          {{ completedCount(planDisplay) }}/{{ activePlan.items.length }}
        </span>
        <button
          type="button"
          title="折叠/展开"
          class="ml-0.5 flex h-6 w-6 items-center justify-center rounded-md text-gray-400 transition-colors duration-200 hover:bg-gray-100 hover:text-gray-600"
          @click="planCollapsed = !planCollapsed"
        >
          <el-icon :size="12" class="transition-transform duration-200" :class="planCollapsed ? '' : 'rotate-180'"><ArrowDown /></el-icon>
        </button>
      </div>
      <template v-if="!planCollapsed">
        <!-- 进度条 + 完成态徽标 -->
        <div class="shrink-0 px-4 pt-3">
          <div class="h-1.5 overflow-hidden rounded-full bg-gray-100">
            <div
              class="h-full rounded-full transition-all duration-500"
              :class="planAllDone ? 'bg-emerald-500' : 'bg-blue-500'"
              :style="{ width: planProgress + '%' }"
            ></div>
          </div>
          <div v-if="planAllDone" class="mt-2 text-[11px] font-medium text-emerald-600">🎉 计划全部完成</div>
        </div>
        <!-- 条目列表：completed 划线 / in_progress 蓝色高亮+左竖条 / pending 灰点 -->
        <div class="thin-scroll min-h-0 flex-1 overflow-y-auto px-2.5 py-2">
          <div v-for="(t, idx) in planDisplay" :key="idx" class="plan-item" :class="t.status">
            <el-icon :size="14" class="plan-item-icon">
              <CircleCheckFilled v-if="t.status === 'completed'" />
              <Loading v-else-if="t.status === 'in_progress'" class="is-loading" />
              <MoreFilled v-else />
            </el-icon>
            <span class="plan-item-text">{{ t.text }}</span>
          </div>
        </div>
        <!-- 尾部：最近一次更新时间（历史回放无时间戳则不显示） -->
        <div v-if="activePlan.updatedAt" class="shrink-0 border-t border-gray-100 px-4 py-2 text-right text-[11px] text-gray-300">
          更新于 {{ formatMsgTime(activePlan.updatedAt) }}
        </div>
      </template>
    </aside>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  ArrowDown, CircleCheckFilled, Close, Cpu, Delete, Document, Connection, List, Loading,
  MoreFilled, Paperclip, Plus, Top, VideoPause, MagicStick, WarningFilled, UploadFilled
} from '@element-plus/icons-vue'
import {
  deleteSession, getAgentCapabilities, getAgentStreamStatus, getSessionDetail, listChatSkills, listSessions,
  openAgentStream, stopAgent
} from '@/api/agent'
import { listChatModels } from '@/api/model'
import { uploadToMainPlatform } from '@/api/mainPlatform'
import { deleteFile, filePreviewUrl, isImageType, uploadFile } from '@/api/file'
import { renderReportMarkdown } from '@/utils/reportMarkdown'
import '@/assets/report-markdown.css'
import TimelineNav from '@/components/TimelineNav.vue'

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

// ── 执行计划（右侧面板）：TodoWrite 参数侧信道的会话级全量快照 ──
// 计划不再进消息 timeline（跨轮取最新一份；历史回放取最后一个 todo 事件灌入），
// 也不受消息卡"已工作 X 秒"折叠影响——执行期间用户始终能在右侧看到进度。
/** 面板折叠态（仅折叠条目区，面板头常驻） */
const planCollapsed = ref(false)
/** 当前会话的执行计划快照：{ items: [{text, status}], updatedAt }（updatedAt 历史 回放无则为 null） */
const activePlan = computed(() => activeSession.value?.plan || null)

// ── 渲染渐入层：target=activePlan（模型快照），display=面板实际渲染状态 ──
// 弱模型会一次性补发多步状态（一格跳三格）：批量状态变化时逐条间隔落账，界面上始终一格一格走。
// 单条正常更新不受影响（首条延迟 0ms 即时落）；状态回退（completed→pending）不动画直接落。
const planDisplay = ref([])
let planDisplayCid = ''
let planAnimTimers = []
const PLAN_ANIM_STEP_MS = 400

function clearPlanAnim() {
  planAnimTimers.forEach(clearTimeout)
  planAnimTimers = []
}

watch([activePlan, activeCid], ([plan, cid]) => {
  clearPlanAnim()
  const switchSession = cid !== planDisplayCid
  planDisplayCid = cid
  if (!plan || !Array.isArray(plan.items)) {
    planDisplay.value = []
    return
  }
  const target = plan.items
  // 会话切换 / 条目增减：整份直接落定（不做动画）
  if (switchSession || planDisplay.value.length !== target.length) {
    planDisplay.value = target.map((t) => ({ text: t.text, status: t.status }))
    return
  }
  // 同会话快照更新：内容变化立即同步；状态升级按序间隔落账，回退直接落
  const next = target.map((t, i) => ({ text: t.text, status: planDisplay.value[i].status }))
  let pending = 0
  target.forEach((t, i) => {
    if (t.status === next[i].status) return
    if (t.status === 'pending') {
      next[i].status = t.status
      return
    }
    planAnimTimers.push(setTimeout(() => {
      if (planDisplay.value[i]) planDisplay.value[i].status = t.status
    }, pending * PLAN_ANIM_STEP_MS))
    pending++
  })
  planDisplay.value = next
}, { deep: true })
onBeforeUnmount(clearPlanAnim)

const planProgress = computed(() => {
  const items = planDisplay.value
  return items.length ? Math.round((completedCount(items) / items.length) * 100) : 0
})

const planAllDone = computed(() => {
  const items = planDisplay.value
  return items.length > 0 && items.every((t) => t.status === 'completed')
})

/** 写入会话级计划快照（全量覆盖；TodoWrite 每次调用携带完整状态） */
function setPlan(cid, items) {
  const s = ensureSession(cid)
  s.plan = { items, updatedAt: Date.now() }
}

/** TodoWrite 叙述兜底：按新旧快照 diff 生成一句系统反馈（仅模型本轮没写正文时上屏） */
function planDiffText(prev, items) {
  if (items.every((t) => t.status === 'completed')) return '执行计划已全部完成'
  if (!prev || !Array.isArray(prev.items)) {
    return `已制定 ${items.length} 步执行计划（见右侧面板）`
  }
  const prevStatus = new Map(prev.items.map((t) => [t.text, t.status]))
  const done = items
    .filter((t) => t.status === 'completed' && prevStatus.get(t.text) !== 'completed')
    .map((t) => t.text)
  const started = items.find((t) => t.status === 'in_progress' && prevStatus.get(t.text) !== 'in_progress')
  const parts = []
  if (done.length) parts.push(`已完成：${done.join('、')}`)
  if (started) parts.push(`开始：${started.text}`)
  return parts.length ? parts.join('，') : '执行计划已更新（见右侧面板）'
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
const draftInputRef = ref(null)
/** M6 联网开关：发送时随请求携带 online，后端据此注入 Tavily 联网工具（未配 key 自动降级） */
const online = ref(false)
/** 后端 Tavily 是否可用（/agent/capabilities 探测）：false 时开关置灰并提示配置方法 */
const onlineAvailable = ref(true)

// ── 深度思考开关：发送时随请求携带 thinking；false 时后端按所选模型注入关思考参数（extra_body）──
/** 默认开启；localStorage 记忆上次选择（'0' = 关） */
const thinking = ref(localStorage.getItem('da-thinking') !== '0')
/** 开关型操作不收起面板，实时回显状态 */
function toggleThinking() {
  thinking.value = !thinking.value
  localStorage.setItem('da-thinking', thinking.value ? '1' : '0')
}

// ── 模型选择：本轮回答使用的对话模型（/agent/models；空清单隐藏按钮走后端默认） ──
/** 启用模型清单（code/name/provider/isDefault/remark，按 sort 排序） */
const chatModels = ref([])
/** 当前选中模型 code；空串 = 跟随后端默认模型。localStorage 记住上次选择 */
const selectedModelCode = ref(localStorage.getItem('da-model-code') || '')
const modelMenuVisible = ref(false)
/** 当前选中模型对象（选中项被停用/删除时回退默认模型） */
const currentModel = computed(() => {
  if (!chatModels.value.length) return null
  return (
    chatModels.value.find((m) => m.code === selectedModelCode.value) ||
    chatModels.value.find((m) => m.isDefault) ||
    chatModels.value[0]
  )
})
/** 发送时携带的 model 字段（与后端当前生效模型不一致才传，等价于默认时不传） */
const effectiveModelCode = computed(() => {
  const m = currentModel.value
  return m && !m.isDefault ? m.code : ''
})

function selectModel(m) {
  selectedModelCode.value = m.code
  localStorage.setItem('da-model-code', m.code)
  modelMenuVisible.value = false
  nextTick(() => draftInputRef.value?.focus())
}

/** 点击面板/按钮以外区域关闭模型面板与「添加上下文」面板 */
function onDocClickCloseModelMenu(e) {
  if (modelMenuVisible.value && !e.target.closest?.('.model-picker-anchor')) modelMenuVisible.value = false
  if (ctxMenuVisible.value && !e.target.closest?.('.ctx-menu-anchor')) ctxMenuVisible.value = false
}

// ── 添加上下文菜单：输入栏「+」弹出（附件 / 业务文件 / 联网搜索），与模型面板互斥 ──
const ctxMenuVisible = ref(false)

function toggleCtxMenu() {
  ctxMenuVisible.value = !ctxMenuVisible.value
  if (ctxMenuVisible.value) modelMenuVisible.value = false
}

function toggleModelMenu() {
  modelMenuVisible.value = !modelMenuVisible.value
  if (modelMenuVisible.value) ctxMenuVisible.value = false
}

/** 菜单内选附件：收起面板后拉起文件选择框（复用原有上传流程） */
function pickAttachment() {
  ctxMenuVisible.value = false
  onUploadClick()
}

/** 菜单内选业务文件：收起面板后拉起文件选择框 */
function pickBizFile() {
  ctxMenuVisible.value = false
  onBizUploadClick()
}

// ── 斜杠技能选择：draft 为单个 "/token" 时弹出面板（仅消息开头，作用域当条消息）──
/** 启用技能清单（/agent/skills，登录即可；name + description 最小字段） */
const chatSkills = ref([])
const skillMenu = reactive({ visible: false, index: 0 })
const SLASH_SKILL_RE = /^\/(\S+?)(?:\s|$)/
/** draft 是否处于 "/token" 形态（单 token、消息开头、还没敲空格），返回过滤关键词或 null */
const draftToken = computed(() => {
  const m = /^\/(\S*)$/.exec(draft.value)
  return m ? m[1] : null
})
/** 按关键词过滤（name/description 包含即命中，大小写不敏感） */
const filteredSkills = computed(() => {
  const kw = (draftToken.value || '').toLowerCase()
  if (draftToken.value === null) return []
  return chatSkills.value.filter(
    (s) => !kw || s.name.toLowerCase().includes(kw) || (s.description || '').toLowerCase().includes(kw)
  )
})
watch(draftToken, (token) => {
  skillMenu.visible = token !== null && chatSkills.value.length > 0
  skillMenu.index = 0
})
watch(filteredSkills, (list) => {
  if (skillMenu.index >= list.length) skillMenu.index = Math.max(0, list.length - 1)
})

/** 选中技能：输入框保留 "/技能名 " 文本 token（发送时解析成 skill 字段，token 原样入库供徽标渲染） */
function selectSkill(skill) {
  draft.value = `/${skill.name} `
  skillMenu.visible = false
  nextTick(() => draftInputRef.value?.focus())
}

/** 面板打开时接管 ↑↓/Enter/Tab/Esc；返回 true 表示事件已消费 */
function onSkillKeydown(e) {
  if (!skillMenu.visible) return false
  const count = filteredSkills.value.length
  if (e.key === 'ArrowDown' && count) {
    e.preventDefault()
    skillMenu.index = Math.min(skillMenu.index + 1, count - 1)
    return true
  }
  if (e.key === 'ArrowUp' && count) {
    e.preventDefault()
    skillMenu.index = Math.max(skillMenu.index - 1, 0)
    return true
  }
  if ((e.key === 'Enter' || e.key === 'Tab') && count) {
    e.preventDefault()
    selectSkill(filteredSkills.value[skillMenu.index])
    return true
  }
  if (e.key === 'Escape') {
    e.preventDefault()
    skillMenu.visible = false
    return true
  }
  return false
}

/** 输入框总键盘入口：技能面板优先，否则还原原 Enter 发送（Shift+Enter 换行走默认） */
function onDraftKeydown(e) {
  if (onSkillKeydown(e)) return
  if (e.key === 'Enter' && !e.shiftKey && !e.ctrlKey && !e.metaKey && !e.altKey) {
    e.preventDefault()
    send()
  }
}

/** 用户消息头部的斜杠技能徽标（内容以 "/技能名" 开头即展示；本地与历史回放同源正则） */
function msgSkill(msg) {
  const m = SLASH_SKILL_RE.exec(msg.content || '')
  return m ? m[1] : null
}

// ── 业务文件上传行展示剥离：send() 注入 query 的 `[业务文件已上传] 文件名 → 预签名URL` 线上格式
// 保持不变（LLM 单路径绑定依赖该 URL），仅在气泡/标题渲染层剥离成 chip，历史消息同源生效 ──
/** 上传行正则（行级捕获文件名与 URL，与 send() 注入格式同源；文件名可含空格，以最后一个 " → " 分隔） */
const BIZ_UPLOAD_LINE_RE = /^\[业务文件已上传\]\s*(.+?)\s+→\s*(\S+)\s*$/gm

/** 解析用户消息中的业务文件上传行：files 供 chip 渲染，text 为剥离上传行后的正文 */
function splitBizUploads(content) {
  const files = []
  const text = String(content || '')
    .replace(BIZ_UPLOAD_LINE_RE, (_, fileName, url) => {
      files.push({ fileName, url })
      return ''
    })
    .replace(/\n{3,}/g, '\n\n') // 剥离行留下的连续空行收拢
    .trim()
  return { files, text }
}

/** 用户消息里的业务文件清单（气泡 chip 渲染用；本地新发与历史回放同源解析） */
function msgBizFiles(msg) {
  return splitBizUploads(msg.content).files
}

/** 业务文件 chip 点击：新窗口打开主平台预签名 URL（浏览器按 MIME 决定展示或下载） */
function openBizFile(bf) {
  if (bf?.url) window.open(bf.url, '_blank')
}

/** 气泡正文：隐藏技能 token 与业务文件上传行，只显示真实输入（纯 token/纯上传行时返回空串） */
function msgContent(msg) {
  return splitBizUploads((msg.content || '').replace(/^\/\S+?(?:\s|$)/, '')).text
}
const listRef = ref(null)
const autoScroll = ref(true)
const SCROLL_BOTTOM_THRESHOLD = 80

function newAssistantMsg() {
  return reactive({ role: 'ai', timeline: [], streaming: true, interrupted: false, error: '', ts: Date.now() })
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
      if (live.question) s.messages.push({ role: 'user', content: live.question, ts: live.questionTs })
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
    let planFromHistory = null
    for (const round of detail?.messages || []) {
      const roundTs = dbTimeToTs(round.createdAt)
      loaded.push({ role: 'user', content: round.question, attachments: round.attachments || [], ts: roundTs })
      let items = safeParse(round.timeline || '[]', [])
      if (!Array.isArray(items)) items = []
      // 执行计划不进对话流：取该轮最后一份 todo 灌入右侧面板（跨轮扫描，最后找到的胜出），timeline 里的全部剔除
      let lastTodo = null
      items.forEach((it) => { if (it.type === 'todo') lastTodo = it })
      if (lastTodo && Array.isArray(lastTodo.items) && lastTodo.items.length) {
        // 落库轮次都是终态：结论轮是纯文本、没有工具调用把最后一步标 completed，
        // 回放时把残留的 in_progress 统一落成完成态（避免面板转圈永不停止）
        lastTodo.items.forEach((t) => { if (t.status === 'in_progress') t.status = 'completed' })
        // 服务端 timeline 的 todo 不带时间戳，updatedAt 置空（面板尾部时间行隐藏）
        planFromHistory = { items: lastTodo.items, updatedAt: null }
      }
      items = items.filter((it) => it.type !== 'todo')
      if (items.length === 0 && round.answer) items = [{ type: 'text', content: round.answer }]
      // 该轮还没有可回放内容（流式进行中，终态才落库）：只保留问题，不渲染空助手气泡
      if (items.length === 0) continue
      loaded.push({
        role: 'ai',
        // M-E1：历史回放时在业务工具块后重建派生卡片块（卡片是前端从工具结果提取的，服务端不落卡片段）
        timeline: hydrateCards(items.map((it) => (it.type === 'tool' ? { ...it, showResult: false } : it))),
        streaming: false,
        interrupted: false,
        error: '',
        elapsedMs: round.elapsedMs ?? null,
        // 服务端只落提问时间，回答时间用 提问 + 该轮耗时 近似
        ts: roundTs != null && round.elapsedMs != null ? roundTs + round.elapsedMs : roundTs
      })
    }
    // 执行计划面板数据源：历史里最新的那份（无计划会话置空，面板整列隐藏）
    s.plan = planFromHistory
    if (live) {
      // 本地有进行中的流：当前轮是终态才落库（不在历史里），直接把实时消息接在历史后面
      loaded.push(live.aiMsg)
      s.streaming = true
    } else {
      s.streaming = false
    }
    s.messages = loaded
    s.loaded = true
    // 标题同源剥离上传行：老会话存量大消息仍带原始行，纯上传行时回退固定文案
    if (!s.title && loaded.length && loaded[0].role === 'user') {
      s.title = genTitle(splitBizUploads(loaded[0].content).text || '业务文件上传')
    }
    scrollToBottom()
    if (!live) tryResumeOnLoad(cid)
  } catch (e) {
    // 服务端记录还没可见（首轮刚发起）：有本地流就直接渲染
    if (live) {
      s.messages = live.question ? [{ role: 'user', content: live.question, ts: live.questionTs }, live.aiMsg] : [live.aiMsg]
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
  const entry = { cid, aiMsg, question, questionTs: question ? Date.now() : null, controller: null, lastSeq: 0, retries: 0, paused: false }
  aiMsg.startAt = Date.now()
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
        else timeline.push({ type: 'thinking', content, open: false, startAt: Date.now() })
        break
      }
      case 'TEXT': {
        const content = data.content || ''
        if (!content) break
        closeThink(timeline)
        // 标记本轮已有模型正文：TodoWrite 兜底行据此决定是否补计划反馈行
        msg.roundNarrated = true
        const last = timeline[timeline.length - 1]
        // placeholder 块（TodoWrite 兜底行）不拼接：答案紧随其后到达时另起新块，
        // 否则答案被粘进 placeholder 且 placeholder 标记不清，msgBlocks 的 ansStart
        // 会回退到过程叙述块，"已工作"折叠边界错位（过程内容露出在正文）。
        if (last && last.type === 'text' && !last.placeholder) last.content += content
        else timeline.push({ type: 'text', content })
        break
      }
      case 'TOOL_START': {
        closeThink(timeline)
        // TodoWrite：全量快照写入右侧执行计划面板（会话级 plan，不进消息 timeline）。
        // 新一轮首个工具且模型没写正文时，按新旧快照 diff 补一条系统反馈行兜底
        // （「已制定 N 步计划 / 已完成：X，开始：Y」），让计划推进在正文区也有呼应。
        if ((data.toolName || '').toLowerCase() === 'todowrite') {
          const args = safeParse(data.arguments || '{}', {})
          const todos = Array.isArray(args.todos) ? args.todos : []
          if (todos.length) {
            const items = todos.map((t) => ({ text: t.content || '', status: t.status || 'pending' }))
            const prev = sessions[cid]?.plan
            setPlan(cid, items)
            const anyRunning = timeline.some((it) => it.type === 'tool' && it.status === 'running')
            if (!anyRunning) {
              const narrated = !!msg.roundNarrated
              msg.roundNarrated = false
              if (!narrated) {
                timeline.push({ type: 'text', content: planDiffText(prev, items), placeholder: true })
              }
            }
          }
          break
        }
        timeline.push({
          type: 'tool',
          toolName: data.toolName || 'unknown',
          toolCallId: data.toolCallId || '',
          status: 'running',
          result: '',
          showResult: false,
          arguments: data.arguments || '',
          startAt: Date.now()
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
          // ③ 工具耗时：徽章展示「中文名 X.Xs」（历史回放无时间戳则只显示中文名）
          item.durationMs = Date.now() - (item.startAt || Date.now())
          // 完成时刻（本地展示字段）：快工具的"完成态直播行最短停留"用它判断是否还在停留期
          if (item.status === 'completed') item.completedAt = Date.now()
          // M-E1/E2：业务工具结果尾部的 __card JSON 提取成卡片块（确认卡/选择卡/填报面板等，可多张）
          if (CARD_TOOLS.has((item.toolName || '').toLowerCase())) {
            for (const card of extractCards(item.result)) {
              timeline.push({ type: 'card', card })
            }
          }
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
        closeThink(timeline)
        msg.streaming = false
        // 总耗时/轮次（COMPLETE 终态事件带回，整条消息一份）；历史回放的消息没有该字段
        if (data.elapsedMs != null) msg.elapsedMs = data.elapsedMs
        if (data.rounds != null) msg.rounds = data.rounds
        const sDone = sessions[cid]
        if (sDone && liveStreams.get(cid) === entry && !entry.paused) {
          sDone.interrupted = false
          // 计划面板兜底落账：结论轮是纯文本（无工具调用），模型来不及把最后一步标 completed；
          // 流正常结束 = 任务完成，残留的 in_progress 统一落成完成态（被顶掉/中断的不落）
          if (sDone.plan) {
            sDone.plan.items.forEach((t) => { if (t.status === 'in_progress') t.status = 'completed' })
            sDone.plan.updatedAt = Date.now()
          }
        }
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
  closeThink(msg.timeline)
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

// ── 业务文件主动上传（直传主平台；绑定目标由用户在对话里说明，发送时注入上传消息）──
// {localId, fileName, status: uploading|done|failed, url, error}
const bizFiles = reactive([])
const bizFileInputRef = ref(null)

function onBizUploadClick() {
  bizFileInputRef.value?.click()
}

function onBizFilesSelected(e) {
  const files = Array.from(e.target.files || [])
  e.target.value = ''
  for (const file of files) {
    startBizUpload(file)
  }
}

function startBizUpload(file) {
  const item = reactive({
    localId: ++localFileSeq,
    fileName: file.name,
    status: 'uploading',
    url: '',
    error: ''
  })
  bizFiles.push(item)
  uploadToMainPlatform(file)
    .then((url) => {
      item.url = url
      item.status = 'done'
    })
    .catch((err) => {
      item.status = 'failed'
      item.error = err?.message || '上传失败'
    })
}

function removeBizFile(item) {
  const idx = bizFiles.indexOf(item)
  if (idx >= 0) bizFiles.splice(idx, 1)
}

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
  if (bizFiles.some((f) => f.status === 'uploading')) return true // 业务文件上传中禁止发送
  if (bizFiles.some((f) => f.status === 'failed')) return true // 失败业务文件需先移除
  if (bizFiles.some((f) => f.status === 'done')) return false // 业务文件就绪：空文本也可直接发送
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
  // 业务文件（直传主平台）上传行随消息注入，绑定目标由用户文字说明（经 LLM 单路径完成绑定）。
  // 确认/取消指令（确认卡按钮）保持纯净：不夹带上传行——重提确认后台账不再是驳回态，新绑定无处落地
  const isConfirmCmd = /^(确认执行|取消)\s+ct_/i.test(query)
  const bizLines = isConfirmCmd
    ? []
    : bizFiles
        .filter((f) => f.status === 'done' && f.url)
        .map((f) => `[业务文件已上传] ${f.fileName} → ${f.url}`)
  const hasBiz = bizLines.length > 0
  // 会话存在中断快照时允许空发送（纯恢复：本地工具重放续跑，docs/03 M3 B02 语义）
  if (!query && !hasBiz && !s.interrupted) return
  const hasPendingDone = pendingFiles.some((p) => p.status === 'done')
  if (!query && !hasBiz && hasPendingDone) {
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
  if (!isConfirmCmd) bizFiles.splice(0, bizFiles.length) // 确认指令不夹带业务文件，chips 留待下一条

  const fullQuery = [...bizLines, query].filter(Boolean).join('\n')
  // 标题用剥离上传行后的正文：纯业务文件上传（无文字）回退固定文案，其余空发送仍是断点恢复
  const titleSource = splitBizUploads(fullQuery).text || (hasBiz ? '业务文件上传' : fullQuery || '从断点恢复')
  if (!s.title) s.title = genTitle(titleSource)
  draft.value = ''
  skillMenu.visible = false
  s.interrupted = false
  s.loaded = true
  if (fullQuery || attachments.length) {
    s.messages.push({ role: 'user', content: fullQuery, attachments, ts: Date.now() })
  }
  const aiMsg = newAssistantMsg()
  s.messages.push(aiMsg)
  autoScroll.value = true
  // 斜杠技能 token：保留在 query 头部原样入库（气泡徽标按内容正则渲染），同时解析成 skill 字段供后端定向装配
  const skillMatch = SLASH_SKILL_RE.exec(query)
  const skill = skillMatch ? skillMatch[1] : null
  openStream(
    cid,
    aiMsg,
    {
      query: fullQuery,
      conversationId: cid,
      fileIds,
      online: online.value,
      thinking: thinking.value,
      ...(skill ? { skill } : {}),
      // 选中的非默认模型随请求携带（默认模型不传 = 跟随后端默认）
      ...(effectiveModelCode.value ? { model: effectiveModelCode.value } : {})
    },
    fullQuery
  )
  syncConversationEntry(cid, titleSource)
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

// ── 业务卡片：仅保留 W2 确认卡（工具结果尾部 ```json {"__card":...}``` 提取）；其余交互全走对话 ──

/** 会产出业务卡片的工具（TOOL_END 拦截 + 历史回放重建共用）：只剩两个出确认卡的工具 */
const CARD_TOOLS = new Set(['resubmitledger', 'submitledgerforreview'])

/** 还原后端双重转义（工具结果里的 \n、\" 字面序列），展示与卡片提取共用 */
function unescapeToolText(raw) {
  let text = String(raw ?? '')
  if (text.includes('\\n') || text.includes('\\"')) {
    text = text
      .replace(/\\r\\n/g, '\n')
      .replace(/\\n/g, '\n')
      .replace(/\\t/g, '  ')
      .replace(/\\"/g, '"')
      .replace(/\\\\/g, '\\')
  }
  return text
}

/** 业务卡片类型白名单：只剩确认卡 */
const CARD_TYPES = new Set(['confirm'])

/** 从工具结果提取业务卡片（失败返回 []） */
function extractCards(result) {
  const text = unescapeToolText(result)
  const cards = []
  for (const fence of text.matchAll(/```json\s*([\s\S]*?)```/g)) {
    try {
      const obj = JSON.parse(fence[1])
      if (obj && CARD_TYPES.has(obj.__card)) cards.push(obj)
    } catch {
      /* 非卡片 JSON（普通 fenced 输出）忽略 */
    }
  }
  return cards
}

/** 历史回放：在业务工具块后补插派生卡片块（确定性重建，与实时路径同源） */
function hydrateCards(items) {
  const out = []
  for (const it of items) {
    out.push(it)
    if (it.type === 'tool' && CARD_TOOLS.has((it.toolName || '').toLowerCase())) {
      for (const card of extractCards(it.result || '')) {
        out.push({ type: 'card', card })
      }
    }
  }
  return out
}

/**
 * 以用户身份发送一条固定文本（确认卡按钮/取消按钮共用）。
 * 走正常 send() 链路 = 经 LLM 单路径执行（D2：不做前端直调 REST 的双路径）。
 */
function sendText(text) {
  if (activeSession.value?.streaming) return
  draft.value = text
  send()
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

/** 服务端时间字符串（yyyy-MM-dd HH:mm:ss）→ 毫秒（无效返回 null） */
function dbTimeToTs(isoStr) {
  const d = new Date((isoStr || '').replace(' ', 'T'))
  return Number.isNaN(d.getTime()) ? null : d.getTime()
}

/** 消息时间小字：当年 MM-DD HH:mm，跨年补年份 */
function formatMsgTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n) => String(n).padStart(2, '0')
  const core = `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  return d.getFullYear() === new Date().getFullYear() ? core : `${d.getFullYear()}-${core}`
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
  return renderReportMarkdown(text, {
    stripHeaderMeta: false,
    enableRoundLinks: false
  })
}

function completedCount(items) {
  return (items || []).filter((t) => t.status === 'completed').length
}

function hasTailText(msg) {
  const last = msg.timeline[msg.timeline.length - 1]
  return (last && (last.type === 'text' || last.type === 'error')) || msg.interrupted
}

// ── 实时反馈层：秒级计时（nowTick 每秒驱动耗时展示）+ 工具中文名 + 思考实时预览 ──

/** 100ms 跳动的时钟：驱动"思考中/正在执行/工作中"实时耗时文案（0.1s 精度，视频式） */
const nowTick = ref(Date.now())
const nowTickTimer = setInterval(() => { nowTick.value = Date.now() }, 100)
onBeforeUnmount(() => clearInterval(nowTickTimer))

/** 业务工具的中文短名（徽章/状态行展示；未收录的回退原始函数名） */
const TOOL_LABELS = {
  listmydrafts: '查我的草稿',
  listphasetree: '查阶段树',
  listfillnodes: '查填报节点',
  listmyprojects: '查我的项目',
  fillfileentry: '填报条目',
  stagedraft: '暂存草稿',
  submitledgerforreview: '发起提审',
  resubmitledger: '重新提审',
  partialapproveledger: '部分通过',
  listreviewoptions: '查审核人',
  listcoauditoptions: '查会签人',
  cancelconfirm: '取消确认',
  getreviewdetail: '查审核详情',
  gettaskdetail: '查任务详情',
  listmytasks: '查我的待办',
  listreviewtasks: '查待审任务',
  approvetask: '审核通过',
  rejecttask: '审核驳回',
  mypermissions: '查我的权限',
  executesql: '执行查询',
  listtables: '查表清单',
  describetables: '查表结构',
  validatesql: '校验 SQL',
  verifysql: '验证 SQL',
  calculate: '计算',
  currenttime: '取当前时间',
  tracefirstappear: '溯源首现',
  traceoverview: '溯源总览',
  traceround: '溯源单轮',
  tool_search: '搜索工具',
  context_reload: '取回上下文',
  tavily_search: '联网搜索',
  skill: '加载技能'
}
function toolLabel(name) {
  const key = (name || '').toLowerCase()
  return TOOL_LABELS[key] || name || 'unknown'
}

/** 新事件到来时给"进行中"的思考条目盖 endAt 戳（"思考 · 持续了 X 秒"的数据来源） */
function closeThink(timeline) {
  const last = timeline[timeline.length - 1]
  if (last && last.type === 'thinking' && last.endAt == null) last.endAt = Date.now()
}

/** 流式进行中的思考条目（整条 timeline 的最后一个事件且为 thinking 时才算"思考进行中"） */
function liveThinkItem(msg) {
  if (!msg.streaming) return null
  const last = msg.timeline[msg.timeline.length - 1]
  return last && last.type === 'thinking' ? last : null
}

/** 实时思考预览只显示尾部（内容持续增长，用户看到的是"正在写"的最近几行） */
const THINK_LIVE_TAIL = 480
function thinkTail(content) {
  const text = content || ''
  return text.length > THINK_LIVE_TAIL ? '…' + text.slice(-THINK_LIVE_TAIL) : text
}

/** 思考块是否进行中（流式 + 该条目是最后一个事件） */
function thinkLive(msg, item) {
  return !!msg.streaming && liveThinkItem(msg) === item
}

/** 思考耗时（毫秒）：实时取 endAt-startAt，历史取 trace 对齐出的 durationMs；拿不到返回 null */
function thinkDurationMs(msg, item) {
  if (item.endAt != null && item.startAt != null) return Math.max(0, item.endAt - item.startAt)
  if (item.durationMs != null) return item.durationMs
  if (thinkLive(msg, item) && item.startAt != null) return Math.max(0, nowTick.value - item.startAt)
  return null
}

/** 思考块 header 文案：思考中 · X 秒 / 思考 · 持续了 X 秒 / 思考（历史无时长） */
function thinkHeaderText(msg, item) {
  const ms = thinkDurationMs(msg, item)
  if (thinkLive(msg, item)) return ms != null ? `思考中 · ${Math.max(1, Math.round(ms / 1000))} 秒` : '思考中'
  return ms != null && ms > 0 ? `思考 · 持续了 ${Math.max(1, Math.round(ms / 1000))} 秒` : '思考'
}

/** 思考块展开态：默认收起（进行中只留一行滚动尾），用户点击才展开；展开状态跨 live→完成 保持 */
function thinkIsOpen(block) {
  return pgState(block.item).userOpen === true
}

function toggleThink(block) {
  const st = pgState(block.item)
  st.userOpen = !thinkIsOpen(block)
}

/** v-think-scroll：单行滚动尾——思考内容增长时把横向滚动钉在最右，始终显示最新片段 */
const vThinkScroll = {
  mounted(el) {
    el.scrollLeft = el.scrollWidth
  },
  updated(el) {
    el.scrollLeft = el.scrollWidth
  }
}

/** v-think-pin：绑定值为 true 时内容增长自动跟随滚底；用户上翻离开底部即暂停跟随，回到底部恢复 */
const vThinkPin = {
  mounted(el, binding) {
    el._follow = !!binding.value
    el._pin = true
    el.addEventListener('scroll', () => {
      el._pin = el.scrollHeight - el.scrollTop - el.clientHeight < 40
    })
    if (el._follow) el.scrollTop = el.scrollHeight
  },
  updated(el, binding) {
    el._follow = !!binding.value
    if (el._follow && el._pin !== false) el.scrollTop = el.scrollHeight
  }
}

/** 工具参数预览（单行截断；空参/{} 不显示） */
const ARG_PREVIEW_MAX = 72
function argPreview(args) {
  const text = (args || '').replace(/\s+/g, ' ').trim()
  if (!text || text === '{}') return ''
  return text.length > ARG_PREVIEW_MAX ? text.slice(0, ARG_PREVIEW_MAX) + '…' : text
}

/** 运行中工具的参数一行滚动尾（同思考尾：钉最右，始终显示"正在写"的最新片段） */
function liveArgsTail(args) {
  const text = (args || '').replace(/\s+/g, ' ').trim()
  if (!text) return ''
  return text.length > THINK_LIVE_TAIL ? '…' + text.slice(-THINK_LIVE_TAIL) : text
}

/** 运行中工具直播行展开的参数全文（JSON 美化；流式中 JSON 未闭合时原样展示） */
function formatToolArgs(args) {
  const text = unescapeToolText(args || '')
  const trimmed = text.trim()
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try {
      return JSON.stringify(JSON.parse(trimmed), null, 2)
    } catch {
      /* 参数流式传输中，JSON 还没闭合 */
    }
  }
  return text || '（无参数）'
}

/** 已完成工具的耗时文案（历史回放无 durationMs 返回 ''） */
function toolDurText(step) {
  return step.durationMs != null ? `${(step.durationMs / 1000).toFixed(1)}s` : ''
}

/** 运行中条目的已耗时（0.1s 精度跳动） */
function preciseElapsed(startAt) {
  if (!startAt) return ''
  return `${((nowTick.value - startAt) / 1000).toFixed(1)}s`
}

/** 消息级状态行文案：" · 1 分 32.8 秒"（流式全程计时；无起点只显示"工作中"） */
function liveStatusText(msg) {
  if (!msg.startAt) return ''
  const total = Math.max(0, nowTick.value - msg.startAt) / 1000
  const m = Math.floor(total / 60)
  const s = total - m * 60
  return m > 0 ? ` · ${m} 分 ${s.toFixed(1)} 秒` : ` · ${s.toFixed(1)} 秒`
}

// ── 执行过程聚合组：连续 thinking/tool 压成一条折叠条，避免多轮工具把消息拉得过长 ──

/** 组状态挂 WeakMap（键=组首项）：timeline 增长重算分组时，用户展开态/选中态不丢 */
const pgStates = new WeakMap()

function pgState(lead) {
  let st = pgStates.get(lead)
  if (!st) {
    st = reactive({ userOpen: null, selected: null })
    pgStates.set(lead, st)
  }
  return st
}

/** timeline 分块（视频式时间线）：连续工具（含运行中）聚成一个工具段——
 * 段内有运行中→渲染层只显示运行中直播行（同段已完成的先隐藏）；
 * 段内全部完成→整段折叠成组。thinking/text/todo/error 独立成块并断开分段。
 * 确认卡滞后一步：先缓存，待其后正文（TEXT 块）产出时先出文本再 flush 卡片——
 * 「确认表单紧跟正文」（TOOL_END 时卡片先入 timeline、LLM 正文后到，直接按序渲染会卡上文末） */
function groupedTimeline(msg) {
  const blocks = []
  let cur = null
  let pendingConfirm = []
  const flushConfirm = () => {
    for (const card of pendingConfirm) blocks.push({ group: false, item: card })
    pendingConfirm = []
  }
  for (const it of msg.timeline) {
    if (it.type === 'tool') {
      // 工具段（含运行中）：连续工具聚成一段，运行中不再打断分组
      if (!cur) {
        cur = { group: true, lead: it, items: [] }
        blocks.push(cur)
      }
      cur.items.push(it)
    } else if (it.type === 'card' && it.card?.__card === 'confirm') {
      cur = null
      pendingConfirm.push(it)
    } else {
      cur = null
      blocks.push({ group: false, item: it })
      if (it.type === 'text') {
        flushConfirm()
      }
    }
  }
  flushConfirm()
  return blocks
}

function pgTools(block) {
  return block.items.filter((it) => it.type === 'tool')
}

/** 段内直播工具（直播行只显示这些；同段已完成且不在停留期的先隐藏，全部过停留期才折叠成组）：
 * 1) 运行中的工具；
 * 2) 没有 running 时，"刚完成且停留期未满"的最后一个工具以完成态直播行显示——
 *    本地毫秒级工具 TOOL_CALL/END 同帧到达，没有最短停留直播行会一帧都不渲染、直接落成收起的组。
 * 停留期由 nowTick(100ms) 驱动到点自动折组；streaming 结束后残留的 running 视为陈旧数据不渲染 */
const MIN_LIVE_MS = 600
function pgLiveTools(msg, block) {
  if (!msg.streaming) return []
  const running = block.items.filter((it) => it.status === 'running')
  if (running.length) return running
  const last = block.items[block.items.length - 1]
  if (last && last.status === 'completed' && last.completedAt != null && nowTick.value - last.completedAt < MIN_LIVE_MS) {
    return [last]
  }
  return []
}

/** 组展开态：纯手动（视频式，默认收起） */
function pgOpen(block) {
  return pgState(block.lead).userOpen === true
}

function toggleGroup(block) {
  const st = pgState(block.lead)
  st.userOpen = !st.userOpen
  if (!st.userOpen) st.selected = null
}

/** 点击条目行：切换选中（完成行→结果就地展开；直播行→参数全文展开） */
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
  let text = unescapeToolText(raw)
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

/** 组头摘要：仅异常时提示（工具数已在组头"查看 N个工具"里） */
function pgMetaText(block) {
  const issues = pgIssueCount(block)
  return issues ? `${issues} 项异常` : ''
}

/** 组头文案："查阅 · N个工具"（🔍 图标在模板里，异常数在 pg-meta 提示） */
function pgHeaderText(block) {
  return `查阅 · ${pgTools(block).length}个工具`
}

/** 组内当前选中行（null = 无选中） */
function pgSel(block) {
  return pgState(block.lead).selected
}

// ── 完成后过程折叠（视频式）：答案输出完毕，思考/工具/叙事折叠为"已工作 · 总耗时"摘要头 ──

/** 过程折叠态：流式/被中断不折叠；完成后默认折叠，用户点开记 processCollapsed=false */
function workCollapsed(msg) {
  if (msg.streaming || msg.interrupted) return false
  return msg.processCollapsed !== false
}

function toggleWork(msg) {
  msg.processCollapsed = !workCollapsed(msg)
}

/** 摘要头文案："已工作 · 26 分 25 秒"（历史取 conversation 表完成时间差，实时取 COMPLETE 事件带回的 elapsedMs） */
function workSummaryText(msg) {
  const ms = msg.elapsedMs != null ? msg.elapsedMs
    : (msg.startAt != null && !msg.streaming ? Date.now() - msg.startAt : null)
  if (ms == null) return '已工作'
  const total = ms / 1000
  const m = Math.floor(total / 60)
  const s = Math.round(total - m * 60)
  return m > 0 ? `已工作 · ${m} 分 ${s} 秒` : `已工作 · ${(ms / 1000).toFixed(1)} 秒`
}

/**
 * 消息渲染块序列：最后一个非空 text 块 = 最终答案起点（含其后确认卡）。
 * 完成的消息：summary 头【常驻】在顶部——收起时其后只显示答案，展开时显示全部过程块；
 * 无答案文本/流式中/被中断不折叠（避免把全部内容藏起来、或丢失唯一可点的开关）。
 */
function msgBlocks(msg) {
  const blocks = groupedTimeline(msg)
  let ansStart = -1
  blocks.forEach((b, i) => {
    if (!b.group && b.item.type === 'text' && !b.item.placeholder && (b.item.content || '').trim()) ansStart = i
  })
  const collapsible = ansStart > 0 && !msg.streaming && !msg.interrupted
  const collapsed = collapsible && workCollapsed(msg)
  const list = []
  if (collapsible) {
    list.push({ kind: 'summary' })
    const visible = collapsed ? blocks.slice(ansStart) : blocks
    visible.forEach((b) => list.push({ kind: 'block', block: b }))
  } else {
    blocks.forEach((b) => list.push({ kind: 'block', block: b }))
  }
  return list
}

function onListScroll() {
  const el = listRef.value
  if (!el) return
  autoScroll.value = el.scrollHeight - el.scrollTop - el.clientHeight < SCROLL_BOTTOM_THRESHOLD
  updateActiveRound()
}

function scrollToBottom() {
  nextTick(() => {
    const el = listRef.value
    if (el && autoScroll.value) el.scrollTop = el.scrollHeight
  })
}

// ── 对话内时间线导航：每轮提问一条，点击跳转 + scrollspy 高亮 ──

/** 时间线导航项：每轮用户提问一条（index=消息锚点下标，ts=提问时间，preview=提问预览） */
const timelineRounds = computed(() => {
  const msgs = activeSession.value?.messages || []
  const rounds = []
  msgs.forEach((msg, i) => {
    if (msg.role !== 'user') return
    const text = (msgContent(msg) || '').trim()
    rounds.push({ index: i, ts: msg.ts || null, preview: text.slice(0, 100) })
  })
  return rounds
})

/** 当前滚动位置所在轮次（视口顶部以上最后一条用户消息的下标；-1 = 无命中） */
const activeRoundIndex = ref(-1)

function updateActiveRound() {
  const el = listRef.value
  if (!el) return
  const top = el.getBoundingClientRect().top
  let cur = -1
  for (const r of timelineRounds.value) {
    const node = el.querySelector(`[data-msg-index="${r.index}"]`)
    if (!node) continue
    if (node.getBoundingClientRect().top <= top + 4) cur = r.index
    else break // 导航项按消息顺序排列，首个落到视口顶之后的即可停
  }
  activeRoundIndex.value = cur
}

let msgFlashTimer = null

/** 跳转到指定轮次的提问消息（脱离贴底跟随 + 目标高亮闪烁） */
function scrollToMessage(index) {
  const el = listRef.value
  if (!el) return
  const node = el.querySelector(`[data-msg-index="${index}"]`)
  if (!node) return
  autoScroll.value = false // 跳转后脱离"贴底跟随"，避免流式事件把视图拽回底部
  node.scrollIntoView({ behavior: 'smooth', block: 'start' })
  node.classList.remove('msg-flash')
  void node.offsetWidth // 强制 reflow，连续点击同一目标也能重启动画
  node.classList.add('msg-flash')
  clearTimeout(msgFlashTimer)
  msgFlashTimer = setTimeout(() => node.classList.remove('msg-flash'), 1200)
}

// 历史加载/新增消息/切换会话后重算高亮位置
watch(() => activeSession.value?.messages?.length, () => nextTick(updateActiveRound))

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
  // 斜杠技能面板数据：仅启用技能；失败静默（面板空态"无匹配技能"兜底，不禁输入）
  try {
    chatSkills.value = (await listChatSkills()) || []
  } catch {
    /* 忽略：/agent/skills 不可用时面板显示空态 */
  }
  // 模型清单：仅启用模型；失败/为空则隐藏模型按钮（后端默认模型兜底）
  try {
    chatModels.value = (await listChatModels()) || []
  } catch {
    /* 忽略：/agent/models 不可用时按钮隐藏，走默认模型 */
  }
  // 模型面板：点击面板/按钮以外区域关闭
  document.addEventListener('click', onDocClickCloseModelMenu)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClickCloseModelMenu)
})
</script>

<style scoped>
/* ── 思考折叠区正文（展开回看：全文区域，进行中内容增长自动跟随滚底）；
 * 灰字 + 浅灰底 + 左侧竖线与组体（pg-body）同款同位，一眼和正文区分 ── */
.think-body {
  margin: 6px 0 2px 14px;
  padding: 8px 10px;
  max-height: 240px;
  overflow-y: auto;
  font-size: 12px;
  line-height: 1.7;
  color: #9ca3af;
  white-space: pre-wrap;
  background: #f9fafb;
  border-left: 2px solid #e5e7eb;
  border-radius: 0 8px 8px 0;
}

/* ── 单行滚动尾（思考内容 / 运行中工具参数共用）：不换行、横向钉最右，始终呈现"正在写"的最新内容 ── */
.live-line {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

/* ── 思考块（视频式独立块）：header 次级灰小字，进行中一行滚动尾，点击展开全文 ── */
.think-block-header {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  margin-top: 4px;
  padding: 3px 6px;
  border-radius: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  cursor: pointer;
  user-select: none;
  text-align: left;
}

.think-header-label {
  flex-shrink: 0;
}

.think-block-header:hover {
  background: #f3f4f6;
  color: var(--el-text-color-regular);
}

/* ── 工具行（执行过程组内条目 + 运行中直播行） ── */
.tool-row {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  margin-top: 4px;
  padding: 5px 6px;
  border-radius: 8px;
  font-size: 12px;
  color: var(--el-text-color-regular);
  cursor: pointer;
  user-select: none;
}

.tool-row:hover {
  background: #f9fafb;
}

.tool-row.sel {
  background: #eff6ff;
}

.tool-row.live-row {
  cursor: pointer;
  color: var(--el-text-color-secondary);
  background: #fafafa;
  border: 1px dashed #f3f4f6;
}

.tool-row-action {
  flex-shrink: 0;
  color: var(--el-text-color-secondary);
}

.tool-row-name {
  flex-shrink: 0;
  font-weight: 500;
}

.tool-row-preview {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.tool-row-dur {
  flex-shrink: 0;
  font-size: 11px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}

/* ── 流式占位（卡片正文内）：第一个 SSE 事件到达前的柔和过渡（"正在思考" + 三点跳动），内容到达后被替换 ── */
.think-placeholder {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  margin: 2px 0 6px;
  padding: 2px 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.dot-flashing {
  display: inline-flex;
  align-items: flex-end;
  gap: 3px;
}

.dot-flashing i {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #9ca3af;
  animation: dot-flash 1.2s infinite ease-in-out;
}

.dot-flashing i:nth-child(2) {
  animation-delay: 0.15s;
}

.dot-flashing i:nth-child(3) {
  animation-delay: 0.3s;
}

@keyframes dot-flash {
  0%, 60%, 100% {
    opacity: 0.25;
    transform: translateY(0);
  }
  30% {
    opacity: 1;
    transform: translateY(-3px);
  }
}

/* ── 消息级状态行（独立块）：淡蓝流光块——旋转图标 + 全程计时，流光背景持续流动表达"进行中" ── */
.live-status {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  padding: 5px 14px;
  border-radius: 2px;
  border: 1px solid #dbeafe;
  background: linear-gradient(90deg, #eff6ff 0%, #dbeafe 50%, #eff6ff 100%);
  background-size: 200% 100%;
  animation: live-status-flow 2.4s linear infinite;
  font-size: 12px;
  color: #2563eb;
  font-variant-numeric: tabular-nums;
}

@keyframes live-status-flow {
  0% {
    background-position: 0% 0;
  }
  100% {
    background-position: -200% 0;
  }
}

/* ── 消息时间小字：气泡外下侧，问右答左 ── */
.msg-time {
  margin-top: 4px;
  padding: 0 6px;
  font-size: 11px;
  color: #9ca3af;
  user-select: none;
  font-variant-numeric: tabular-nums;
}

/* ── 时间线导航跳转目标：背景高亮闪烁渐隐 ── */
.msg-flash {
  animation: msg-flash-bg 1.2s ease-out;
  border-radius: 12px;
}

@keyframes msg-flash-bg {
  0% { background-color: rgba(59, 130, 246, 0.14); }
  100% { background-color: transparent; }
}

/* ── 完成后过程折叠摘要头：已工作 · 总耗时（点击展开回看全过程） ── */
.work-summary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  padding: 3px 6px;
  border-radius: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  cursor: pointer;
  user-select: none;
  font-variant-numeric: tabular-nums;
}

.work-summary:hover {
  background: #f3f4f6;
  color: var(--el-text-color-regular);
}

/* ── 工具行图标：运行中旋转加载；完成/失败统一 🔧（失败由组头"N 项异常"提示） ── */
.tool-icon.running {
  color: #f59e0b;
  animation: spin 1s linear infinite;
}

/* emoji 图标列固定宽度居中：🔍/🧠 字形宽度不同也能保证"图标+两字+文案"的列对齐 */
.tool-emoji,
.think-icon {
  flex-shrink: 0;
  width: 16px;
  font-size: 12px;
  line-height: 1;
  text-align: center;
  user-select: none;
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

/* 工具结果详情：统一原文展示（pre.tool-result），不再提供 markdown 渲染切换 */

/* ── 业务卡片（仅 W2 确认卡）── */
.biz-card {
  margin-top: 10px;
  padding: 12px 14px;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  background: #fff;
}

.confirm-card {
  border-color: #fde68a;
  background: #fffbeb;
}

.biz-card-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: #374151;
}

.biz-diff-table {
  width: 100%;
  margin-top: 8px;
  border-collapse: collapse;
  font-size: 12px;
}

.biz-diff-table th,
.biz-diff-table td {
  border: 1px solid #f0f0f0;
  padding: 5px 8px;
  text-align: left;
}

.biz-diff-table th {
  background: rgba(0, 0, 0, 0.02);
  color: #6b7280;
  font-weight: 500;
}

.biz-card-impact {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.7;
  color: #4b5563;
}

.biz-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 1px solid #e5e7eb;
  background: #fff;
  color: #4b5563;
  border-radius: 8px;
  padding: 5px 12px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.biz-btn:hover:not(:disabled) {
  border-color: #d1d5db;
  background: #f9fafb;
}

.biz-btn.primary {
  border-color: transparent;
  background: #2563eb;
  color: #fff;
}

.biz-btn.primary:hover:not(:disabled) {
  background: #1d4ed8;
}

.biz-btn:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

/* ── 执行过程聚合组（与思考行/摘要头同缩进对齐） ── */
.pg-header {
  display: flex;
  align-items: center;
  gap: 6px;
  width: fit-content;
  padding: 3px 6px;
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

/* 组体：左侧竖线引导（终端式工具列表），行缩进挂在竖线右侧 */
.pg-body {
  margin: 4px 0 4px 14px;
  padding-left: 10px;
  border-left: 2px solid #e5e7eb;
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

/* ── 右侧执行计划面板 ── */
.plan-item {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 6px 8px 6px 12px;
  border-radius: 8px;
  font-size: 12px;
  line-height: 1.6;
  color: #6b7280;
}

.plan-item + .plan-item {
  margin-top: 2px;
}

.plan-item-icon {
  flex-shrink: 0;
  margin-top: 3px;
  color: #d1d5db;
}

/* 进行中：蓝字浅蓝底 + 左侧蓝色竖条（当前焦点） */
.plan-item.in_progress {
  color: #2563eb;
  background: rgba(59, 130, 246, 0.06);
}

.plan-item.in_progress .plan-item-icon {
  color: #3b82f6;
}

.plan-item.in_progress::before {
  content: '';
  position: absolute;
  left: 2px;
  top: 7px;
  bottom: 7px;
  width: 2px;
  border-radius: 1px;
  background: #3b82f6;
}

/* 已完成：绿勾 + 灰字删除线 */
.plan-item.completed {
  color: #9ca3af;
}

.plan-item.completed .plan-item-icon {
  color: #10b981;
}

.plan-item.completed .plan-item-text {
  text-decoration: line-through;
}

/* ── AI 气泡内 timeline 元素节奏 ── */
.chat-timeline > * + * {
  margin-top: 8px;
}

/* ── markdown 正文（基础排版/语义代码分色/高辨识度表格复用 report-markdown.css） ── */
/* 系统占位进度行（模型未写中间轮叙述时的兜底文案）：视觉弱化，与模型正文区分 */
.chat-md.chat-md--placeholder {
  color: #9ca3af;
  font-size: 13px;
}

/* M6 图表图片（mcp-echarts 外链）：约束在气泡宽度内不撑破布局，点击经包层 <a> 看原图 */
.chat-md :deep(img) {
  display: block;
  box-sizing: border-box;
  max-width: 100%;
  height: auto;
  margin: 10px 0;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
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
