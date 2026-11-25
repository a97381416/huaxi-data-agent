<template>
  <div class="flex h-full min-h-0 flex-col">
    <!-- 事件头：类型 + 轮次 + 状态 + 耗时（子事件带 [子Agent] 徽标与子块内双导航，M-TT §4.2） -->
    <div class="shrink-0 border-b border-gray-50 px-4 pt-3">
      <div class="flex items-center gap-2">
        <span class="text-sm font-semibold text-gray-800">{{ headTitle }}</span>
        <el-tag size="small" :type="headTagType" disable-transitions>{{ headTypeCn }}</el-tag>
        <el-tag v-if="isSubEvent" size="small" type="warning" disable-transitions>
          [子Agent] {{ agentName(subSession?.agentCode) }}
        </el-tag>
        <el-tag v-if="event.success === false" size="small" type="danger" disable-transitions>error</el-tag>
        <span v-if="event.errorMessage" class="truncate text-xs text-red-500" :title="event.errorMessage">
          {{ event.errorMessage }}
        </span>
        <span class="ml-auto shrink-0 text-xs text-gray-400">
          {{ formatDuration(event.durationMs) }}
          <span v-if="event.promptTokens != null" class="ml-2">
            {{ formatTokens(event.promptTokens) }}/{{ formatTokens(event.completionTokens) }} tok
          </span>
        </span>
        <!-- 双导航（M-TT §4.2）：主轴 tool → 父级决策；子事件 → 子块内父级决策 + 返回委派处 -->
        <el-button v-if="!isSubEvent && event.type === 'tool' && event.parentSeq != null" link size="small" type="primary" @click="$emit('locate', String(event.parentSeq))">
          父级决策 ▸
        </el-button>
        <template v-else-if="isSubEvent">
          <el-button v-if="event.parentSeq != null" link size="small" type="primary" @click="$emit('locate', subKey(event.parentSeq))">
            ◂ 父级决策
          </el-button>
          <el-button v-if="delegateKey" link size="small" type="primary" plain @click="$emit('locate', delegateKey)">
            ↩ 返回委派处
          </el-button>
        </template>
      </div>
      <div v-if="event.assistantText && event.type === 'llm'" class="mt-1 truncate text-xs text-gray-500" :title="event.assistantText">
        "{{ firstLine(event.assistantText) }}"
      </div>
      <el-tabs v-model="tab" class="mt-1" size="small">
        <!-- 由果溯因动线：执行结果 → 模型决策 → 模型思考 → 新传入 → 完整上下文 -->
        <el-tab-pane name="result" lazy>
          <template #label>
            执行结果<span v-if="resultBadge" class="ml-0.5 text-gray-400">({{ resultBadge }})</span>
          </template>
        </el-tab-pane>
        <!-- 终答的产出就是回答正文，已并入「执行结果」，不再单独给「最终回答」Tab -->
        <el-tab-pane v-if="event.type !== 'answer'" name="output" lazy>
          <template #label>{{ outputLabel }}</template>
        </el-tab-pane>
        <el-tab-pane name="think" lazy label="模型思考" />
        <el-tab-pane name="delta" lazy>
          <template #label>
            新传入<span v-if="deltaCount" class="ml-0.5 text-gray-400">({{ deltaCount }})</span>
          </template>
        </el-tab-pane>
        <el-tab-pane name="input" lazy>
          <template #label>
            完整上下文<span v-if="event.inputChars" class="ml-0.5 text-gray-400">({{ formatSize(event.inputChars) }})</span>
          </template>
        </el-tab-pane>
        <!-- 命中检测规则的事件才显示「规则异常」（放最后，红字突出） -->
        <el-tab-pane v-if="eventRisks.length" name="risk" lazy>
          <template #label>
            <span class="text-red-500">规则异常({{ eventRisks.length }})</span>
          </template>
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- 内容区（由果溯因：执行结果 → 模型决策 → 模型思考 → 新传入 → 完整上下文 → 规则异常） -->
    <div class="min-h-0 flex-1 overflow-auto px-3 py-2">
      <!-- ① 增量输入：本次调用相比上一轮新传入的消息 -->
      <template v-if="tab === 'delta'">
        <div v-if="event.type === 'tool'" class="rounded-lg bg-gray-50 p-4 text-center text-sm text-gray-400">
          工具事件无新传入消息（其结果会作为下一轮的「新传入」进入上下文，结果本身见「执行结果」Tab）
        </div>
        <div v-else-if="!deltaMessages.length" class="rounded-lg bg-gray-50 p-4 text-center text-sm text-gray-400">
          {{ deltaEmptyText }}
        </div>
        <template v-else>
          <div class="mb-1.5 text-[11px] text-gray-400">{{ deltaHint }}</div>
          <div
            v-for="(m, idx) in deltaMessages"
            :key="idx"
            class="mb-2 rounded-lg border border-gray-100 bg-white p-2.5"
          >
            <!-- 卡片头：点击展开/折叠；折叠态带内容首行摘要 -->
            <div
              class="flex items-center gap-1.5"
              :class="m.content ? 'cursor-pointer' : ''"
              @click="m.content && toggleCard(idx)"
            >
              <span
                v-if="m.content"
                class="shrink-0 select-none font-mono text-xs text-gray-400 hover:text-blue-500"
              >{{ expandedCards.has(idx) ? '▾' : '▸' }}</span>
              <span
                class="rounded px-1.5 py-0.5 font-mono text-[10px] font-semibold"
                :class="roleChipClass(m.role)"
              >{{ m.role }}</span>
              <span v-if="m.name" class="font-mono text-[11px] text-violet-600">{{ m.name }}</span>
              <span v-if="m.toolCallId" class="truncate font-mono text-[10px] text-gray-300" :title="m.toolCallId">
                {{ m.toolCallId }}
              </span>
              <!-- 折叠态：首行摘要 + 字符数 -->
              <span
                v-if="!expandedCards.has(idx) && m.content"
                class="min-w-0 flex-1 truncate text-[11px] text-gray-400"
                :title="firstLine(normalize(m.content).text)"
              >{{ abbreviate(firstLine(normalize(m.content).text), 60) }}</span>
              <span v-if="m.content && !expandedCards.has(idx)" class="shrink-0 text-[10px] text-gray-300">
                {{ formatSize(m.content.length) }}
              </span>
              <span v-if="m.truncated" class="shrink-0 text-[10px] text-orange-400">内容超长已截断</span>
              <el-button
                v-if="m.content && expandedCards.has(idx)"
                link size="small" class="ml-auto shrink-0"
                @click.stop="openPreview(deltaTitle(m, idx), m.content)"
              >原文/渲染 ▸</el-button>
            </div>
            <!-- 正文（展开态） -->
            <div v-if="expandedCards.has(idx)" class="mt-1.5">
            <!-- assistant：原话 + 发起的调用 -->
            <template v-if="m.role === 'assistant'">
              <div class="prose prose-sm max-w-none text-xs text-gray-700" v-html="renderMd(m.content)" />
              <div v-if="m.toolCalls && m.toolCalls.length" class="mt-1.5 flex flex-wrap gap-1.5">
                <span
                  v-for="c in m.toolCalls"
                  :key="c.callId"
                  class="max-w-full cursor-pointer truncate rounded bg-violet-50 px-1.5 py-0.5 font-mono text-[10px] text-violet-600 hover:bg-violet-100"
                  :title="c.arguments"
                  @click="openPreview('调用参数 · ' + c.name, prettyJson(c.arguments))"
                >🔧 {{ c.name }}({{ abbreviate(firstLine(c.arguments), 40) }})</span>
              </div>
            </template>
            <!-- tool：结果（自动解包 JSON 编码后渲染） -->
            <template v-else-if="m.role === 'tool'">
              <template v-if="normalize(m.content).jsonValue">
                <JsonTree :data="normalize(m.content).jsonValue" root-key="result" :expand-depth="3" />
              </template>
              <div
                v-else-if="normalize(m.content).text.length > 4000"
                class="prose prose-sm max-w-none text-xs text-gray-700"
                v-html="renderMd(normalize(m.content).text.slice(0, 4000))"
              />
              <div v-else class="prose prose-sm max-w-none text-xs text-gray-700" v-html="renderMd(normalize(m.content).text)" />
              <div v-if="normalize(m.content).layers" class="mt-1 text-[10px] text-amber-600">
                已自动解开 {{ normalize(m.content).layers }} 层 JSON 字符串编码
              </div>
            </template>
            <!-- system/user -->
            <div v-else class="prose prose-sm max-w-none text-xs text-gray-700" v-html="renderMd(normalize(m.content).text)" />
            </div>
          </div>
        </template>
      </template>

      <!-- ② 全量输入：本轮完整上下文（payload 懒加载 + >100KB 门控，沿用原 INPUT 口径） -->
      <template v-else-if="tab === 'input'">
        <div v-loading="inputLoading" class="min-h-[120px]">
          <div v-if="inputGated" class="rounded-lg bg-gray-50 p-4 text-center">
            <div class="text-sm text-gray-500">
              本轮全量输入 {{ formatSize(event.inputChars || roundData?.inputChars || 0) }}，为省内存默认不渲染
            </div>
            <el-button class="mt-2" size="small" type="primary" plain @click="confirmInput = true">加载完整明细</el-button>
          </div>
          <JsonTree
            v-else-if="roundData?.inputData"
            :raw="roundData.inputData" root-key="input_data" :expand-depth="2"
            :long-text-preview="(text, name) => openPreview('全量输入 · ' + (name || ''), text)"
          />
          <div v-else-if="!inputLoading" class="rounded-lg bg-gray-50 py-8 text-center text-sm text-gray-400">
            该轮无明细（历史行未回刷或写入失败）
          </div>
        </div>
      </template>

      <!-- ③ 思考：reasoning（MD 渲染，>8000 字截断同原 THINK 口径） -->
      <template v-else-if="tab === 'think'">
        <template v-if="event.think">
          <div v-if="event.thinkDurationMs" class="mb-1.5 text-[11px] text-gray-400">
            <el-icon class="align-middle"><Clock /></el-icon>
            思考耗时 {{ formatDuration(event.thinkDurationMs) }}
          </div>
          <div class="prose prose-sm max-w-none text-xs text-gray-700" v-html="renderThink" />
        </template>
        <div v-else class="rounded-lg bg-gray-50 py-8 text-center text-sm text-gray-400">
          本轮无思考内容（未开启深度思考或未落库）
        </div>
      </template>

      <!-- ④ 输出·决策 -->
      <template v-else-if="tab === 'output'">
        <template v-if="event.type === 'user'">
          <div class="rounded-lg bg-gray-50 p-4 text-center text-sm text-gray-400">
            提问事件无输出（后续见 R{{ event.round }} 的「输出·决策」）
          </div>
        </template>
        <template v-else>
          <div v-if="outputText" class="prose prose-sm max-w-none text-xs text-gray-700" v-html="renderMd(outputText)" />
          <div v-else class="mb-2 text-xs text-gray-400">（本轮模型无文本输出，仅发起工具调用）</div>
          <!-- llm：并发工具调用参数 -->
          <template v-if="event.type === 'llm' && event.toolCalls?.length">
            <div class="mb-1.5 mt-3 text-xs font-medium text-gray-600">决策并发调用 {{ event.toolCalls.length }} 个工具：</div>
            <div v-for="c in event.toolCalls" :key="c.callId" class="mb-2 rounded-lg border border-gray-100 bg-white p-2.5">
              <div class="mb-1 flex items-center gap-1.5">
                <span class="font-mono text-xs font-semibold text-violet-600">🔧 {{ c.name }}</span>
                <span class="truncate font-mono text-[10px] text-gray-300" :title="c.callId">{{ c.callId }}</span>
                <el-button link size="small" class="ml-auto shrink-0" @click="openPreview('调用参数 · ' + c.name, c.arguments)">原文 ▸</el-button>
              </div>
              <pre class="overflow-auto whitespace-pre-wrap break-words font-mono text-[11px] leading-4 text-gray-600">{{ abbreviate(prettyJson(c.arguments), 2000) }}</pre>
            </div>
            <div class="text-[11px] text-gray-400">执行结果见「执行结果」Tab 或左轴 🔧 子事件</div>
          </template>
          <!-- tool：所属决策 + 本工具参数 -->
          <template v-else-if="event.type === 'tool'">
            <div v-if="parentEvent?.assistantText" class="mb-2 text-xs text-gray-500">
              发起决策：<span class="text-gray-700">"{{ firstLine(parentEvent.assistantText) }}"</span>
              <el-button link size="small" type="primary" @click="$emit('locate', parentKey)">查看父级 ▸</el-button>
            </div>
            <div class="mb-1 text-xs font-medium text-gray-600">本工具调用参数：</div>
            <pre class="overflow-auto whitespace-pre-wrap break-words rounded-lg border border-gray-100 bg-white p-2.5 font-mono text-[11px] leading-4 text-gray-600">{{ abbreviate(prettyJson(event.arguments), 2000) }}</pre>
            <el-button link size="small" class="mt-1" @click="openPreview('调用参数 · ' + event.toolName, event.arguments)">参数原文 ▸</el-button>
          </template>
        </template>
      </template>

      <!-- ⑤ 执行结果 -->
      <template v-else-if="tab === 'result'">
        <!-- 文本轮（终答）：产出 = 最终回答正文 -->
        <template v-if="event.type === 'answer'">
          <div v-if="event.assistantText" class="prose prose-sm max-w-none text-xs text-gray-700" v-html="renderMd(event.assistantText)" />
          <div v-else class="rounded-lg bg-gray-50 py-8 text-center text-sm text-gray-400">（无回答内容）</div>
        </template>
        <template v-else-if="event.type === 'tool'">
          <!-- 子Agent执行块汇总卡（M-TT §4.2 左图）：委派事件点击即看子会话任务/状态/轮次/失败轮/内部明细，可点击定位 -->
          <div v-if="event.subSession" class="mb-2 rounded-lg border border-blue-100 bg-blue-50/30 p-2.5">
            <div class="flex items-center gap-1.5 text-xs">
              <span class="shrink-0">🤖</span>
              <span class="shrink-0 font-semibold text-blue-700">{{ agentName(event.subSession.agentCode) }}</span>
              <span class="shrink-0 font-mono text-[10px] text-gray-400">{{ event.subSession.agentCode }}</span>
              <el-tag size="small" :type="subTagType(event.subSession.status)" disable-transitions>
                {{ subStatusCn(event.subSession.status) }}
              </el-tag>
              <el-button
                v-if="event.subSession.events?.length"
                link size="small" class="ml-auto shrink-0"
                @click="$emit('toggle-sub', event.subSession.sessionId)"
              >展开/收起左轴子块 ▾</el-button>
            </div>
            <div class="mt-1 flex items-start gap-1 text-[11px] leading-4 text-gray-500" :title="event.subSession.task">
              <span class="shrink-0">🎯 任务:</span>
              <span class="min-w-0 flex-1">{{ event.subSession.task || '-' }}</span>
            </div>
            <div class="mt-1 text-[11px] text-gray-500">
              内部: {{ event.subSession.roundCount || 0 }} 轮 ·
              {{ subStepCount }} 步工具 ·
              {{ formatTokens((event.subSession.promptTokens || 0) + (event.subSession.completionTokens || 0)) }} tok
              <span v-if="(event.subSession.failRounds || 0) > 0" class="text-red-500">
                ❌ 失败{{ event.subSession.failRounds }}轮:
                {{ failRoundSummary }}
              </span>
            </div>
            <!-- 内部执行明细迷你列表：点击定位到左轴子块对应行 -->
            <div v-if="event.subSession.events?.length" class="mt-1.5 space-y-0.5 border-t border-blue-100/70 pt-1.5">
              <div
                v-for="ev in miniRows"
                :key="ev.seq"
                class="flex cursor-pointer items-center gap-1.5 rounded px-1 py-0.5 text-[11px] hover:bg-blue-100/60"
                :title="'点击定位到内部执行明细'"
                @click="$emit('locate', `sub-${event.subSession.sessionId}-${ev.seq}`)"
              >
                <span class="shrink-0">{{ miniIcon(ev) }}</span>
                <span class="shrink-0 font-mono text-gray-500">{{ miniLabel(ev) }}</span>
                <span v-if="ev.type === 'tool'" class="min-w-0 flex-1 truncate font-mono text-gray-400" :title="ev.arguments">{{ toolArgPreview(ev) }}</span>
                <span v-else class="min-w-0 flex-1 truncate text-gray-400">{{ firstLine(ev.assistantText || '') }}</span>
                <span v-if="ev.success === false" class="shrink-0 text-red-500">❌ {{ ev.errorMessage || '失败' }}</span>
                <span class="shrink-0 text-gray-400">{{ formatDuration(ev.durationMs) }}</span>
              </div>
            </div>
          </div>
          <div class="mb-2 flex items-center gap-2 text-xs">
            <span :class="resultIconClass">{{ resultIcon }}</span>
            <span :class="resultTextClass">{{ resultStatusText }}</span>
            <span v-if="event.resultChars != null" class="text-gray-400">{{ formatSize(event.resultChars) }}</span>
            <el-button
              v-if="resultFull?.content"
              link size="small" class="ml-auto"
              @click="openPreview('执行结果 · ' + event.toolName, resultFull.content)"
            >原文/渲染 ▸</el-button>
          </div>
          <div v-if="event.resultPreview" class="mb-2 truncate text-[11px] text-gray-400" :title="event.resultPreview">
            {{ event.resultPreview }}
          </div>
          <template v-if="resultFull">
            <JsonTree v-if="normalize(resultFull.content).jsonValue" :data="normalize(resultFull.content).jsonValue" root-key="result" :expand-depth="3" />
            <div
              v-else-if="normalize(resultFull.content).text.length > 8000"
              class="prose prose-sm max-w-none text-xs text-gray-700"
              v-html="renderMd(normalize(resultFull.content).text.slice(0, 8000))"
            />
            <div v-else class="prose prose-sm max-w-none text-xs text-gray-700" v-html="renderMd(normalize(resultFull.content).text)" />
            <div v-if="normalize(resultFull.content).layers" class="mt-1 text-[10px] text-amber-600">
              已自动解开 {{ normalize(resultFull.content).layers }} 层 JSON 字符串编码
            </div>
            <div v-if="resultFull.truncated" class="mt-1 text-[10px] text-orange-400">
              内容超长被截断，完整内容请看下一轮「完整上下文」
            </div>
          </template>
          <div v-else-if="event.resultChars == null" class="rounded-lg bg-gray-50 py-8 text-center text-sm text-gray-400">
            结果未落库（下一轮明细缺失，常见于会话中断或最后一轮）
          </div>
        </template>
        <!-- llm：子工具结果汇总 -->
        <template v-else-if="event.type === 'llm' && childTools.length">
          <div class="mb-1.5 text-xs font-medium text-gray-600">本轮发起的 {{ childTools.length }} 个工具调用结果：</div>
          <div
            v-for="c in childTools"
            :key="c.seq"
            class="mb-2 cursor-pointer rounded-lg border border-gray-100 bg-white p-2.5 hover:border-blue-200"
            @click="$emit('locate', childKey(c))"
          >
            <div class="flex items-center gap-1.5 text-xs">
              <span>{{ resultIconOf(c) }}</span>
              <span class="font-mono font-semibold text-violet-600">{{ c.toolName }}</span>
              <span class="ml-auto text-[11px] text-gray-400">
                {{ c.resultChars != null ? formatSize(c.resultChars) : '结果未落库' }}
              </span>
              <el-button link size="small" type="primary">详情 ▸</el-button>
            </div>
            <div v-if="c.resultPreview" class="mt-1 truncate text-[11px] text-gray-400">{{ c.resultPreview }}</div>
          </div>
        </template>
        <div v-else class="rounded-lg bg-gray-50 py-8 text-center text-sm text-gray-400">
          {{ event.type === 'answer' ? '文本轮无工具执行' : '提问事件无工具执行' }}
        </div>
      </template>

      <!-- ⑥ 规则异常：本事件所在轮次命中的检测规则详情（有命中才显示该 Tab） -->
      <template v-else-if="tab === 'risk'">
        <div class="mb-1.5 text-[11px] text-gray-400">
          本事件所在轮次（R{{ event.round }}）命中 {{ eventRisks.length }} 条检测规则：
        </div>
        <div
          v-for="r in eventRisks"
          :key="r.ruleId + '-' + r.round"
          class="mb-2 rounded-lg border bg-white p-2.5"
          :class="r.severity === 'high' ? 'border-red-200 bg-red-50/30' : r.severity === 'medium' ? 'border-orange-200 bg-orange-50/30' : 'border-gray-200'"
        >
          <div class="flex items-center gap-1.5">
            <span class="shrink-0 text-sm">{{ r.severity === 'high' ? '❗' : r.severity === 'medium' ? '⚠' : '·' }}</span>
            <span
              class="shrink-0 rounded px-1 py-0.5 text-[10px] font-semibold"
              :class="r.severity === 'high' ? 'bg-red-100 text-red-600' : r.severity === 'medium' ? 'bg-orange-100 text-orange-600' : 'bg-gray-100 text-gray-500'"
            >{{ severityCn(r.severity) }}</span>
            <span class="shrink-0 font-mono text-[11px] text-gray-400">{{ r.ruleId }}</span>
            <span class="truncate text-xs font-medium text-gray-700" :title="r.title">{{ r.title }}</span>
          </div>
          <div class="prose prose-sm mt-1.5 max-w-none text-xs text-gray-700" v-html="renderMd(restoreEscapes(r.detail))" />
        </div>
      </template>
    </div>

    <!-- 长文本 MD 预览弹窗（原文/渲染 + 复制） -->
    <MdPreviewDialog v-model="preview.visible" :title="preview.title" :content="preview.content" />
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { Clock } from '@element-plus/icons-vue'
import MarkdownIt from 'markdown-it'
import JsonTree from '@/components/JsonTree.vue'
import MdPreviewDialog from '@/components/MdPreviewDialog.vue'
import { normalizeJsonText } from '@/utils/jsonText'
import { agentName, subStatusCn, subTagType } from '@/utils/agentNames'

/** >100KB 全量输入门控（同提问详情页原 INPUT 口径） */
const BIG_FIELD_CHARS = 100 * 1024

/**
 * 事件流程快照（2026-11-20 排查视角改造）：选中左轴事件后的右栏五 Tab 视图，
 * 按「由果溯因」排查动线排列——执行结果（发生了什么）→ 模型决策（决定干什么）
 * → 模型思考（为什么这么决定）→ 新传入（刚收到什么信息）→ 完整上下文（刨根兜底）。
 * tool 结果按 tool_call_id 配对全文（后端 timeline 已回填 resultRefSeq 引用）。
 * 所有长文本「原文/渲染 ▸」统一走 MdPreviewDialog（自动解包 JSON 编码 + 防 XSS）。
 *
 * M-TT 子Agent穿透（ing-02 §4.2）：委派 tool 事件（event.subSession）渲染子会话汇总卡
 * （任务/状态/轮次/失败轮/内部明细迷你列表，点击定位）；子事件（subSession 非空 props）
 * 头部带 [子Agent] 徽标 + 子块内双导航（◂ 父级决策 / ↩ 返回委派处）。
 * locate 事件统一携带事件 key 字符串（主轴 `${seq}` / 子块 `sub-{subSessionId}-{seq}`），
 * 父组件 locateKey 统一处理跨命名空间定位。
 */
const props = defineProps({
  /** timeline 事件（SessionTimelineVO.Event） */
  event: { type: Object, required: true },
  /** 全部事件（tool 结果全文按 resultRefSeq 回查下轮 delta；主轴命名空间） */
  events: { type: Array, default: () => [] },
  /** 选中事件为子事件时所属的 SubSession（M-TT；主轴事件为 null） */
  subSession: { type: Object, default: null },
  /** 选中子事件时其主轴委派 tool 事件 key（「↩ 返回委派处」导航目标；空串隐藏按钮） */
  delegateKey: { type: String, default: '' },
  /** 拉取单轮明细（父组件缓存）：async traceId → RoundDetailVO */
  loadRound: { type: Function, required: true },
  /** 当前命名空间的风险列表（自动检测结果；按轮次关联出「规则异常」Tab） */
  risks: { type: Array, default: () => [] },
  /** 父组件指定的初始 Tab（如点 ⚠ 角标直达「规则异常」）；空串用事件类型默认值 */
  initialTab: { type: String, default: '' }
})
const emit = defineEmits(['locate', 'toggle-sub'])

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })
const tab = ref('delta')
const preview = reactive({ visible: false, title: '', content: '' })
const confirmInput = ref(false)
const inputLoading = ref(false)
const roundData = ref(null)
/** 增量输入卡片展开集合：默认只展开第一张（从最新传入的消息读起），其余折叠为单行摘要 */
const expandedCards = ref(new Set([0]))

function toggleCard(idx) {
  const s = expandedCards.value
  if (s.has(idx)) s.delete(idx)
  else s.add(idx)
}

watch([() => props.event, () => props.initialTab], () => {
  // 切事件/父组件指定 Tab：⚠ 角标直达「规则异常」，其余按类型落默认（工具/LLM/终答 → 执行结果，提问 → 新传入）
  tab.value = props.initialTab || defaultTab(props.event)
  confirmInput.value = false
  roundData.value = null
  inputLoading.value = false
  expandedCards.value = new Set([0])
}, { immediate: true })

/** 事件类型的默认 Tab：排查者点左轴首要看的是这次调用的结果；终答的结果 = 回答正文 */
function defaultTab(ev) {
  if (ev.type === 'tool' || ev.type === 'llm' || ev.type === 'answer') return 'result'
  return 'delta'
}

// 全量输入 Tab 懒加载（沿用 detailCache 口径，由父组件提供缓存后的 loadRound）
watch([tab, () => props.event], async ([t, ev]) => {
  if (t !== 'input' || roundData.value || !ev?.traceId) return
  inputLoading.value = true
  try {
    roundData.value = await props.loadRound(ev.traceId)
  } finally {
    inputLoading.value = false
  }
})

const deltaMessages = computed(() => props.event.deltaMessages || [])
const deltaCount = computed(() =>
  ['user', 'llm', 'answer'].includes(props.event.type) ? deltaMessages.value.length : 0)
const deltaHint = computed(() => {
  if (props.event.type === 'user') return '提问事件的「新传入」= 首轮完整上下文（system + user）'
  if (props.event.type === 'answer') return '终答轮相比上一轮新传入的消息'
  return `本次调用相比上一轮新传入 ${deltaMessages.value.length} 条消息（上轮模型决策 + 工具结果）`
})
const deltaEmptyText = computed(() => {
  if (props.event.type === 'user') return '提问事件无新传入消息'
  if (props.event.round === 1) return '首轮上下文即提问本身（见「提问」事件的「新传入」）'
  return '无新增传入消息（历史行明细缺失）'
})

// ==================== M-TT 子事件命名空间 ====================

/** 选中事件是否为子Agent事件（subSession prop 非空） */
const isSubEvent = computed(() => !!props.subSession)

/** 当前命名空间事件集（子事件 → 所属 subSession.events；主轴事件 → props.events） */
const scopeEvents = computed(() => (isSubEvent.value ? props.subSession?.events || [] : props.events))

/** 子命名空间事件 key：`sub-{subSessionId}-{seq}` */
function subKey(seq) {
  return `sub-${props.subSession?.sessionId || ''}-${seq}`
}

/** 任意事件的 key（按当前命名空间；模板定位/父链查找统一入口） */
function childKey(ev) {
  return isSubEvent.value ? subKey(ev.seq) : String(ev.seq)
}

const parentKey = computed(() =>
  props.event.parentSeq != null ? childKey({ seq: props.event.parentSeq }) : '')

const parentEvent = computed(() =>
  props.event.parentSeq != null ? scopeEvents.value.find((e) => e.seq === props.event.parentSeq) : null)
const childTools = computed(() =>
  scopeEvents.value.filter((e) => e.type === 'tool' && e.parentSeq === props.event.seq))

/** tool 结果全文：下轮 llm 事件 delta 内按 toolCallId 查找（后端 resultRefSeq 指向；子块内自洽） */
const resultFull = computed(() => {
  if (props.event.type !== 'tool' || props.event.resultRefSeq == null) return null
  const refEvent = scopeEvents.value.find((e) => e.seq === props.event.resultRefSeq)
  const msg = refEvent?.deltaMessages?.find(
    (m) => m.role === 'tool' && (!props.event.callId || m.toolCallId === props.event.callId))
  return msg ? { content: msg.content, truncated: msg.truncated } : null
})

// ==================== 委派事件子会话汇总卡（M-TT §4.2） ====================

/** 子Agent内部工具步数 */
const subStepCount = computed(() =>
  (props.event.subSession?.events || []).filter((e) => e.type === 'tool').length)

/** 失败轮摘要（❌ 失败N轮: 的后半句）：首个失败轮 + 其首个失败工具/原因 */
const failRoundSummary = computed(() => {
  const evs = props.event.subSession?.events || []
  const failLlm = evs.find((e) => e.type === 'llm' && e.success === false)
  if (!failLlm) return ''
  const failTool = evs.find((e) => e.type === 'tool' && e.round === failLlm.round && e.resultOk === false)
  const what = failTool ? `Sub-R${failLlm.round} ${failTool.toolName}` : `Sub-R${failLlm.round}`
  return `${what} ${failLlm.errorMessage || '执行失败'} → 点击「内部执行明细」定位`
})

/** 内部执行明细迷你列表行：llm/tool/answer 事件（去掉 user 起点） */
const miniRows = computed(() =>
  (props.event.subSession?.events || []).filter((e) => e.type !== 'user'))

function miniIcon(ev) {
  if (ev.type === 'llm') return '🤖'
  if (ev.type === 'answer') return '💬'
  return ev.resultOk == null ? '◌' : ev.resultOk === false ? '❌' : ev.resultEmpty ? 'ⓘ' : '✅'
}

function miniLabel(ev) {
  if (ev.type === 'llm') return `Sub-R${ev.round} llm`
  if (ev.type === 'answer') return '产出 text'
  return `Sub-R${ev.round} ${ev.toolName}`
}

/** 迷你列表工具行参数摘要（同左轴 toolArgsPreview 口径：首键=首行值截断） */
function toolArgPreview(ev) {
  try {
    const args = JSON.parse(ev.arguments || '{}')
    const first = Object.entries(args)[0]
    if (!first) return '{}'
    const val = typeof first[1] === 'string' ? first[1] : JSON.stringify(first[1])
    const one = val.split('\n')[0]
    return `${first[0]}=${one.length > 30 ? one.slice(0, 30) + '…' : one}`
  } catch {
    return abbreviate(firstLine(ev.arguments || ''), 30)
  }
}

/** 本事件所在轮次命中的检测规则（risk.round 命中或 roundsHit 包含）→「规则异常」Tab */
const eventRisks = computed(() =>
  props.risks.filter((r) => r.round === props.event.round || (r.roundsHit || []).includes(props.event.round)))

function severityCn(sev) {
  return { high: '异常', medium: '待人工确认', low: '风险提示' }[sev] || sev
}

const resultBadge = computed(() => {
  if (props.event.type === 'tool') return props.event.resultChars != null ? formatSize(props.event.resultChars) : '缺'
  if (props.event.type === 'llm') return childTools.value.length ? String(childTools.value.length) : ''
  return ''
})

const inputGated = computed(() =>
  (props.event.inputChars || roundData.value?.inputChars || 0) > BIG_FIELD_CHARS && !confirmInput.value)

const renderThink = computed(() => {
  const think = props.event.think || ''
  if (think.length > 8000) {
    return md.render(think.slice(0, 8000)) +
      '<div class="mt-2 rounded bg-gray-50 p-2 text-center text-gray-400">内容过长已截断</div>'
  }
  return md.render(think)
})

const outputText = computed(() => props.event.assistantText || '')
/** 决策 Tab 文案按事件类型：工具=调用参数，其余=模型决策（终答无此 Tab） */
const outputLabel = computed(() =>
  (props.event.type === 'tool' ? '调用参数' : '模型决策'))

const headTitle = computed(() => {
  const rPrefix = isSubEvent.value ? 'Sub-R' : 'R'
  if (props.event.type === 'user') return `提问`
  if (props.event.type === 'answer') return `${rPrefix}${props.event.round} · 最终回答`
  if (props.event.type === 'tool') return `${rPrefix}${props.event.round} · ${props.event.toolName}`
  return `${rPrefix}${props.event.round} · LLM 调用`
})
const headTypeCn = computed(() =>
  ({ user: '提问', llm: 'LLM 调用', tool: '工具调用', answer: '文本轮' }[props.event.type]))
const headTagType = computed(() =>
  ({ user: 'primary', llm: 'warning', tool: 'info', answer: 'success' }[props.event.type]))

const resultIcon = computed(() =>
  props.event.resultOk == null ? '◌' : props.event.resultEmpty ? 'ⓘ' : props.event.resultOk ? '✅' : '❌')
const resultIconClass = computed(() =>
  props.event.resultOk == null ? 'text-gray-300'
    : props.event.resultOk ? (props.event.resultEmpty ? 'text-orange-500' : 'text-green-600') : 'text-red-500')
const resultTextClass = computed(() =>
  props.event.resultOk == null ? 'text-gray-400'
    : props.event.resultOk ? (props.event.resultEmpty ? 'text-orange-500' : 'text-green-600') : 'text-red-500')
const resultStatusText = computed(() => {
  if (props.event.resultOk == null) return '结果缺失'
  if (!props.event.resultOk) return '执行失败'
  return props.event.resultEmpty ? '执行成功 · 未命中数据' : '执行成功'
})

function resultIconOf(toolEvent) {
  if (toolEvent.resultOk == null) return '◌'
  if (!toolEvent.resultOk) return '❌'
  return toolEvent.resultEmpty ? 'ⓘ' : '✅'
}

function normalize(content) {
  return normalizeJsonText(content)
}

function renderMd(text) {
  return md.render(text || '')
}

/** 规则详情里字面 \n / \" 是检测服务生成时转义的，富文本渲染前还原为真换行/引号 */
function restoreEscapes(text) {
  return (text || '').replaceAll('\\n', '\n').replaceAll('\\"', '"')
}

function prettyJson(text) {
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text || ''
  }
}

function deltaTitle(msg, idx) {
  const name = msg.role === 'tool' && msg.name ? msg.name : msg.role
  return `增量输入[${idx}] · ${name}`
}

function openPreview(title, content) {
  preview.title = title || '内容预览'
  preview.content = content || ''
  preview.visible = true
}

function roleChipClass(role) {
  return {
    system: 'bg-gray-100 text-gray-500',
    user: 'bg-blue-100 text-blue-600',
    assistant: 'bg-green-100 text-green-600',
    tool: 'bg-violet-100 text-violet-600'
  }[role] || 'bg-gray-100 text-gray-500'
}

function firstLine(text) {
  return (text || '').split('\n')[0]
}

function abbreviate(text, max) {
  const one = (text || '')
  return one.length > max ? one.slice(0, max) + '…' : one
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
