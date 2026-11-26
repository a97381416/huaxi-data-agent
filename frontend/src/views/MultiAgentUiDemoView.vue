<template>
  <div class="relative flex h-full w-full overflow-hidden bg-gray-50">
    <!-- 左侧：10 大多 Agent UI 测试场景清单 -->
    <aside class="flex w-[300px] shrink-0 flex-col border-r border-gray-200 bg-white">
      <div class="border-b border-gray-100 px-4 py-3">
        <div class="text-sm font-semibold text-gray-800">多 Agent UI 10 场景验收台</div>
        <div class="mt-0.5 text-[11px] text-gray-400">
          直接复用真实 SubAgentCard / SubAgentDrawer 组件，点击左侧切换场景，点击卡片可滑出右侧多 Tab 抽屉
        </div>
      </div>
      <div class="thin-scroll flex-1 overflow-y-auto p-2 space-y-1">
        <button
          v-for="sc in scenarios"
          :key="sc.id"
          type="button"
          class="w-full rounded-lg px-3 py-2 text-left transition-colors"
          :class="sc.id === activeScenarioId ? 'bg-blue-50 text-blue-700' : 'text-gray-700 hover:bg-gray-50'"
          @click="selectScenario(sc.id)"
        >
          <div class="flex items-center gap-1.5 text-[12.5px] font-medium">
            <span class="rounded bg-blue-100 px-1.5 py-0.5 font-mono text-[10px] text-blue-700">{{ sc.tag }}</span>
            <span class="truncate">{{ sc.title }}</span>
          </div>
          <div class="mt-1 line-clamp-2 text-[11px] leading-4 text-gray-400">{{ sc.desc }}</div>
        </button>
      </div>
    </aside>

    <!-- 中间：主会话仿真预览区 -->
    <section class="relative flex min-w-0 flex-1 flex-col">
      <div class="flex items-center justify-between gap-4 border-b border-gray-200 bg-white px-6 py-3">
        <div class="min-w-0">
          <span class="text-sm font-semibold text-gray-800">{{ currentScenario.tag }} · {{ currentScenario.title }}</span>
          <span class="ml-2 text-xs text-gray-400">{{ currentScenario.desc }}</span>
        </div>
        <div class="flex shrink-0 items-center gap-2">
          <button
            v-if="currentScenario.id === 's9'"
            type="button"
            class="whitespace-nowrap rounded-lg bg-blue-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-blue-700"
            @click="toggleS9Mode"
          >
            {{ s9HistoryMode ? '切回：9a 用户强停（级联中断）' : '切换：9b 历史会话回放' }}
          </button>
          <button
            v-if="currentScenario.id === 's10'"
            type="button"
            class="whitespace-nowrap rounded-lg bg-blue-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-blue-700"
            @click="s10Done = !s10Done"
          >
            {{ s10Done ? '切回：后台运行 + 阶段简报态' : '模拟：后台专员全部完成并汇总' }}
          </button>
          <button
            type="button"
            class="whitespace-nowrap rounded-lg border border-gray-200 bg-white px-3 py-1.5 text-xs text-gray-600 hover:bg-gray-50"
            @click="openFirstSub"
          >
            打开右侧子会话抽屉
          </button>
        </div>
      </div>

      <div class="thin-scroll flex-1 overflow-y-auto px-6 py-6">
        <div class="mx-auto flex w-full max-w-4xl flex-col gap-5">
          <!-- 用户提问 -->
          <div class="flex justify-end">
            <div class="max-w-[80%] rounded-2xl rounded-tr-sm bg-blue-600 px-5 py-3 text-sm text-white shadow-sm">
              {{ currentScenario.userQuery }}
            </div>
          </div>

          <!-- 助手回复卡片 -->
          <div class="rounded-2xl rounded-tl-sm border border-gray-100 bg-white px-5 py-4 shadow-sm">
            <!-- 完成折叠摘要头 -->
            <button
              v-if="isCollapsible"
              type="button"
              class="mb-2 inline-flex items-center gap-1.5 rounded-full bg-gray-100 px-3 py-1 text-xs font-medium text-gray-600 hover:bg-gray-200"
              @click="collapsed = !collapsed"
            >
              <span>{{ summaryHeadText }}</span>
              <el-icon :size="11" class="transition-transform" :class="collapsed ? '' : 'rotate-180'"><ArrowDown /></el-icon>
            </button>

            <template v-for="(it, idx) in visibleTimeline" :key="idx">
              <!-- 子 Agent 委派卡片 -->
              <SubAgentCard
                v-if="it.type === 'tool' && isSubAgentTool(it)"
                :step="it"
                :now-tick="nowTick"
                :selected="drawer.open && drawer.activeKey === it.toolCallId"
                :tool-label-fn="toolLabel"
                :arg-preview-fn="argPreview"
                @inspect="openDrawer(it)"
              />

              <!-- 普通轻量工具条（如场景 10 主 Agent 快速核对） -->
              <div
                v-else-if="it.type === 'tool'"
                class="my-1.5 flex items-center gap-2 rounded-lg border border-gray-100 bg-gray-50 px-3 py-1.5 text-xs text-gray-600"
              >
                <span>🔍</span>
                <span class="font-medium">{{ it.label || it.toolName }}</span>
                <span class="ml-auto font-mono text-[11px] text-gray-400">{{ it.durationText }}</span>
              </div>

              <!-- 思考条 -->
              <div
                v-else-if="it.type === 'thinking'"
                class="my-1.5 flex items-center gap-1.5 text-xs text-gray-400"
              >
                <span>🧠</span>
                <span>{{ it.content }}</span>
              </div>

              <!-- 确认卡（场景 7） -->
              <div
                v-else-if="it.type === 'card'"
                class="my-2.5 rounded-xl border p-3.5 text-xs transition-colors"
                :class="
                  s7ActionState === 'confirmed'
                    ? 'border-emerald-200 bg-emerald-50/60'
                    : s7ActionState === 'cancelled'
                      ? 'border-gray-200 bg-gray-50'
                      : 'border-amber-200 bg-amber-50/50'
                "
              >
                <div class="flex items-center gap-1.5 font-semibold" :class="s7ActionState === 'confirmed' ? 'text-emerald-700' : s7ActionState === 'cancelled' ? 'text-gray-600' : 'text-amber-700'">
                  <span>{{ s7ActionState === 'confirmed' ? '✅' : s7ActionState === 'cancelled' ? '⏹' : '⚠️' }} {{ it.card.title }}</span>
                  <span class="ml-auto font-normal text-gray-400">
                    {{ s7ActionState === 'confirmed' ? '已确认执行' : s7ActionState === 'cancelled' ? '已取消' : '5 分钟内有效' }}
                  </span>
                </div>
                <div class="mt-2 text-gray-600">{{ it.card.impact }}</div>
                <div v-if="s7ActionState === 'pending'" class="mt-3 flex items-center gap-2">
                  <button
                    type="button"
                    class="cursor-pointer rounded-lg bg-blue-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-blue-700"
                    @click="handleConfirmCard('confirmed')"
                  >
                    ✅ 确认执行
                  </button>
                  <button
                    type="button"
                    class="cursor-pointer rounded-lg border border-gray-200 bg-white px-3 py-1.5 text-xs text-gray-600 hover:bg-gray-50"
                    @click="handleConfirmCard('cancelled')"
                  >
                    取消
                  </button>
                </div>
                <div v-else class="mt-3 flex items-center justify-between gap-2 rounded-lg bg-white/80 px-3 py-2 text-xs">
                  <span :class="s7ActionState === 'confirmed' ? 'text-emerald-700' : 'text-gray-500'">
                    {{
                      s7ActionState === 'confirmed'
                        ? '已提交审核（令牌 ct_hx2026_881 已消费），单据状态已流转为「待审核」。'
                        : '已取消本次提审操作，单据保持「草稿」状态。'
                    }}
                  </span>
                  <button
                    type="button"
                    class="shrink-0 cursor-pointer text-blue-600 hover:underline"
                    @click="s7ActionState = 'pending'"
                  >
                    重置确认卡
                  </button>
                </div>
              </div>

              <!-- 正文文本 -->
              <div
                v-else-if="it.type === 'text'"
                class="report-md my-2 text-sm leading-relaxed text-gray-700"
                v-html="renderMd(it.content)"
              ></div>
            </template>

            <!-- 场景 10 底部异步等待条 -->
            <div
              v-if="currentScenario.id === 's10' && !s10Done"
              class="mt-3 flex items-center gap-2 rounded-lg border border-blue-100 bg-blue-50/70 px-3 py-2 text-xs text-blue-700"
            >
              <el-icon :size="13" class="is-loading shrink-0 text-blue-500"><Loading /></el-icon>
              <span>等待后台专员返回（0/2 已完成），结果到达后将自动汇总…</span>
            </div>

            <!-- 场景 9a 中断横幅 -->
            <div
              v-if="currentMsg.interrupted"
              class="mt-2 w-fit rounded-md bg-amber-50 px-2.5 py-1.5 text-xs text-amber-600"
            >
              ⏸ 已中断 — 发送消息（可为空发送/继续）可从断点恢复
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- 右侧：真实 SubAgentDrawer 组件 -->
    <SubAgentDrawer
      :visible="drawer.open"
      :items="drawerItems"
      :active-key="drawer.activeKey"
      :now-tick="nowTick"
      model-name="海方-glm / GLM-5.3"
      :tool-label-fn="toolLabel"
      :arg-preview-fn="argPreview"
      :format-json-fn="formatJson"
      :format-result-fn="formatJson"
      :render-md-fn="renderMd"
      @select="(k) => (drawer.activeKey = k)"
      @close="drawer.open = false"
    />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowDown, Loading } from '@element-plus/icons-vue'
import SubAgentCard from '@/components/SubAgentCard.vue'
import SubAgentDrawer from '@/components/SubAgentDrawer.vue'
import { isSubAgentTool, subAgentDisplayName } from '@/utils/agentNames'
import { renderReportMarkdown } from '@/utils/reportMarkdown'
import '@/assets/report-markdown.css'

const nowTick = ref(Date.now())
const timer = setInterval(() => { nowTick.value = Date.now() }, 200)
onBeforeUnmount(() => clearInterval(timer))

const activeScenarioId = ref('s3')
const collapsed = ref(false)
const s7ActionState = ref('pending')
const s9HistoryMode = ref(false)
const s10Done = ref(false)
const drawer = reactive({ open: false, activeKey: '' })

const baseNow = Date.now()

const scenarios = [
  {
    id: 's1',
    tag: '场景 1',
    title: '单子 Agent 运行中',
    desc: '主 Agent 派出数据分析专员，主会话只展示紧凑运行卡片与最新 1 步，不刷屏',
    userQuery: '帮我统计本月各项目的填报进度并画柱状图',
    build: () => ({
      streaming: true,
      timeline: [
        { type: 'thinking', content: '思考 持续了 2.4 秒' },
        { type: 'text', content: '我先派数据分析专员查库统计本月各项目的填报进度。' },
        {
          type: 'tool',
          toolName: 'call_data_analysis',
          toolCallId: 'tc_s1_1',
          agentCode: 'data-analysis',
          status: 'running',
          startAt: baseNow - 15000,
          arguments: JSON.stringify({ task: '统计本月各项目的填报进度，按项目分组统计已上报、已通过、未填报数量，并生成柱状图。' }),
          subSteps: [
            { type: 'thinking', content: '先查看业务库表结构，确认台账表与项目表关联字段。', durationMs: 2100 },
            { type: 'tool', toolName: 'listTables', status: 'completed', durationMs: 420, arguments: '{}', result: '["project_info","ledger_record"]' },
            { type: 'tool', toolName: 'describeTables', status: 'completed', durationMs: 360, arguments: '{"tables":["ledger_record"]}', result: '{"columns":["project_id","status","created_at"]}' },
            { type: 'tool', toolName: 'executeSql', status: 'running', startAt: baseNow - 3200, arguments: '{"sql":"SELECT project_name, COUNT(*) FROM ledger_record GROUP BY project_name"}' }
          ]
        }
      ]
    })
  },
  {
    id: 's2',
    tag: '场景 2',
    title: '子 Agent 独立会话抽屉',
    desc: '点击卡片在右侧滑出完整独立视图（模型、任务指令、思考、查阅/执行时间线、底部状态栏）',
    userQuery: '帮我分析近 3 个月合同变更趋势',
    autoOpenDrawer: true,
    build: () => ({
      streaming: false,
      elapsedMs: 14200,
      timeline: [
        {
          type: 'tool',
          toolName: 'call_data_analysis',
          toolCallId: 'tc_s2_1',
          agentCode: 'data-analysis',
          status: 'completed',
          durationMs: 12400,
          arguments: JSON.stringify({
            task: '按月统计近 3 个月（2026-09 至 2026-11）合同变更次数、变更总金额与环比增减趋势，按项目维度聚合统计变更次数排名前 3 的重点项目，同时核对是否存在未走完审批流程的异常变更单据；此外请额外关联项目主表与资金台账表，校验各项目累计变更金额占合同总额的比例是否超过 15% 预警红线，并分项列出超标项目的具体单据编号、经办人、变更原因与当前审批节点，最终输出结构化明晰结论供主会话汇总展示。'
          }),
          result: '近 3 个月共发生合同变更 27 次，环比增长 12.5%；变更最多的是华西二期、天府新区片区和成都东站项目。',
          subSteps: [
            { type: 'thinking', content: '查询合同变更台账表，按月份做聚合统计并取 TOP 3 项目。', durationMs: 1800 },
            { type: 'tool', toolName: 'listTables', status: 'completed', durationMs: 310, arguments: '{}', result: '["contract_change_log"]' },
            { type: 'tool', toolName: 'executeSql', status: 'completed', durationMs: 1450, arguments: '{"sql":"SELECT month, COUNT(*) cnt FROM contract_change_log GROUP BY month"}', result: '[{"month":"2026-09","cnt":7},{"month":"2026-10","cnt":9},{"month":"2026-11","cnt":11}]' },
            { type: 'text', content: '已核对近 3 个月合同变更明细，呈逐月上升趋势。' }
          ]
        },
        { type: 'text', content: '近 3 个月合同变更共 **27 次**（9月 7 次 → 10月 9 次 → 11月 11 次），主要集中在华西二期等 3 个重点项目。' }
      ]
    })
  },
  {
    id: 's3',
    tag: '场景 3',
    title: '多子 Agent 并行执行',
    desc: '同轮同时派出 2 个专员，纵向卡片清晰堆叠，右侧抽屉顶部多 Tab 秒切围观',
    userQuery: '查下本月有多少已上报项目，顺便看看我名下有哪些待审核任务',
    autoOpenDrawer: true,
    build: () => ({
      streaming: true,
      timeline: [
        { type: 'text', content: '我正在并行处理：已同时派出「数据分析专员」和「审核审批专员」。' },
        {
          type: 'tool',
          toolName: 'call_data_analysis',
          toolCallId: 'tc_s3_1',
          agentCode: 'data-analysis',
          status: 'running',
          startAt: baseNow - 12000,
          arguments: JSON.stringify({ task: '统计本月已上报项目总数及按片区分布情况' }),
          subSteps: [
            { type: 'thinking', content: '使用聚合 SQL 直接统计本月已上报项目数量。', durationMs: 1500 },
            { type: 'tool', toolName: 'executeSql', status: 'running', startAt: baseNow - 4000, arguments: '{"sql":"SELECT COUNT(DISTINCT project_id) FROM ledger WHERE status = \'SUBMITTED\'"}' }
          ]
        },
        {
          type: 'tool',
          toolName: 'call_reviewer_workflow',
          toolCallId: 'tc_s3_2',
          agentCode: 'reviewer-workflow',
          status: 'running',
          startAt: baseNow - 12000,
          arguments: JSON.stringify({ task: '查询当前登录用户待审核的任务清单及紧急程度' }),
          subSteps: [
            { type: 'tool', toolName: 'listReviewTasks', status: 'running', startAt: baseNow - 3000, arguments: '{"status":"PENDING"}' }
          ]
        }
      ]
    })
  },
  {
    id: 's4',
    tag: '场景 4',
    title: '并行一先一后返回',
    desc: '审核审批专员先跑完亮绿勾出结论，数据分析专员仍在跑，状态互不干扰',
    userQuery: '查下本月有多少已上报项目，顺便看看我名下有哪些待审核任务',
    build: () => ({
      streaming: true,
      timeline: [
        {
          type: 'tool',
          toolName: 'call_data_analysis',
          toolCallId: 'tc_s4_1',
          agentCode: 'data-analysis',
          status: 'running',
          startAt: baseNow - 21000,
          arguments: JSON.stringify({ task: '统计本月已上报项目总数并生成趋势图' }),
          subSteps: [
            { type: 'tool', toolName: 'executeSql', status: 'completed', durationMs: 1800, arguments: '{"sql":"SELECT ..."}', result: '{"total":18}' },
            { type: 'tool', toolName: 'generateChart', status: 'running', startAt: baseNow - 5000, arguments: '{"type":"bar","title":"本月各片区上报数"}' }
          ]
        },
        {
          type: 'tool',
          toolName: 'call_reviewer_workflow',
          toolCallId: 'tc_s4_2',
          agentCode: 'reviewer-workflow',
          status: 'completed',
          durationMs: 7800,
          arguments: JSON.stringify({ task: '查询当前登录用户待审核的任务清单' }),
          result: '您当前有 3 条待审核台账：华西二期桩基资料、天府片区月报、东站安全巡检记录。',
          subSteps: [
            { type: 'tool', toolName: 'listReviewTasks', status: 'completed', durationMs: 650, arguments: '{"status":"PENDING"}', result: '{"count":3}' }
          ]
        }
      ]
    })
  },
  {
    id: 's5',
    tag: '场景 5',
    title: '完成后收起与展开',
    desc: '全部跑完后默认折叠为顶部「已工作 · 协作 2 个专员」条，正文干干净净',
    userQuery: '帮我全面汇总本月上报情况与待审清单',
    defaultCollapsed: true,
    build: () => ({
      streaming: false,
      elapsedMs: 32100,
      timeline: [
        {
          type: 'tool',
          toolName: 'call_data_analysis',
          toolCallId: 'tc_s5_1',
          agentCode: 'data-analysis',
          status: 'completed',
          durationMs: 24500,
          arguments: JSON.stringify({ task: '统计本月已上报项目数' }),
          result: '本月共 18 个项目完成上报，完成率 85.7%。',
          subSteps: [{ type: 'tool', toolName: 'executeSql', status: 'completed', durationMs: 1200, arguments: '{"sql":"SELECT COUNT(*)..."}', result: '{"total":18}' }]
        },
        {
          type: 'tool',
          toolName: 'call_reviewer_workflow',
          toolCallId: 'tc_s5_2',
          agentCode: 'reviewer-workflow',
          status: 'completed',
          durationMs: 7600,
          arguments: JSON.stringify({ task: '查询待审核任务清单' }),
          result: '您名下有 3 条待审核任务。',
          subSteps: [{ type: 'tool', toolName: 'listReviewTasks', status: 'completed', durationMs: 520, arguments: '{}', result: '{"count":3}' }]
        },
        { type: 'text', content: '本月共有 **18 个项目**完成上报（完成率 85.7%），您名下还有 **3 条待审核任务**需及时处理。点击上方折叠条可回看两位专员的完整协作过程。' }
      ]
    })
  },
  {
    id: 's6',
    tag: '场景 6',
    title: '多子 Agent 串行接力',
    desc: '先派数据分析专员查出未报齐项目，再接力派资料填报专员拉取该项目节点清单',
    userQuery: '查一下哪个项目资料还没报齐，然后帮我把那个项目的填报节点拉出来',
    build: () => ({
      streaming: true,
      timeline: [
        {
          type: 'tool',
          toolName: 'call_data_analysis',
          toolCallId: 'tc_s6_1',
          agentCode: 'data-analysis',
          status: 'completed',
          durationMs: 9200,
          arguments: JSON.stringify({ task: '查询本月存在未填报资料的项目清单' }),
          result: '仅「华西二期项目」存在 2 项未填报资料（主体结构验收表、安全交底记录）。',
          subSteps: [{ type: 'tool', toolName: 'executeSql', status: 'completed', durationMs: 900, arguments: '{"sql":"SELECT ..."}', result: '["华西二期项目"]' }]
        },
        { type: 'text', content: '已定位到「华西二期项目」未报齐，下面委派资料填报专员为您拉取该项目的填报节点清单。' },
        {
          type: 'tool',
          toolName: 'call_declarant_fill',
          toolCallId: 'tc_s6_2',
          agentCode: 'declarant-fill',
          status: 'running',
          startAt: baseNow - 6000,
          arguments: JSON.stringify({ task: '查询「华西二期项目」的阶段树与待填报节点清单' }),
          subSteps: [
            { type: 'tool', toolName: 'listMyProjects', status: 'completed', durationMs: 320, arguments: '{}', result: '[{"id":102,"name":"华西二期项目"}]' },
            { type: 'tool', toolName: 'listFillNodes', status: 'running', startAt: baseNow - 2000, arguments: '{"projectId":102}' }
          ]
        }
      ]
    })
  },
  {
    id: 's7',
    tag: '场景 7',
    title: '子 Agent 触发高危确认卡',
    desc: '资料填报专员签发 ct_xxx 令牌后，确认卡直接冒泡到主会话正文区供用户一键确认',
    userQuery: '把华西二期项目的草稿提交给张工审核',
    build: () => ({
      streaming: false,
      elapsedMs: 11800,
      timeline: [
        {
          type: 'tool',
          toolName: 'call_declarant_fill',
          toolCallId: 'tc_s7_1',
          agentCode: 'declarant-fill',
          status: 'completed',
          durationMs: 10200,
          arguments: JSON.stringify({ task: '为华西二期项目草稿生成提交给张工审核的确认计划' }),
          result: '已生成提审确认计划（令牌 ct_hx2026_881），等待用户确认后正式提交。',
          subSteps: [
            { type: 'tool', toolName: 'listMyDrafts', status: 'completed', durationMs: 340, arguments: '{}', result: '{"draftId":88}' },
            { type: 'tool', toolName: 'submitLedgerForReview', status: 'completed', durationMs: 620, arguments: '{"draftId":88,"reviewer":"张工"}', result: '{"confirmToken":"ct_hx2026_881"}' }
          ]
        },
        { type: 'text', content: '请核对以下提审信息，点击「确认执行」后将正式提交给张工审核：' },
        {
          type: 'card',
          card: {
            __card: 'confirm',
            title: '提交审核确认',
            impact: '影响：华西二期项目（单号 DOC-2026-088），台账状态 草稿 → 待审核，审核人：张工'
          }
        }
      ]
    })
  },
  {
    id: 's8',
    tag: '场景 8',
    title: '局部报错自愈 & 整体超时',
    desc: '上方卡片展示“内部报错自愈（含 1 次重试）”，下方卡片展示“180s 超时标橙终止”',
    userQuery: '拉取近 5 年全量台账并交叉比对历史审批记录',
    build: () => ({
      streaming: false,
      elapsedMs: 181200,
      timeline: [
        {
          type: 'tool',
          toolName: 'call_reviewer_workflow',
          toolCallId: 'tc_s8_1',
          agentCode: 'reviewer-workflow',
          status: 'completed',
          durationMs: 6400,
          arguments: JSON.stringify({ task: '查询历史审批记录汇总' }),
          result: '已通过重试修正参数，成功拉取 142 条历史审批记录。',
          subSteps: [
            { type: 'tool', toolName: 'listReviewTasks', status: 'failed', durationMs: 280, arguments: '{"pageSize":500}', result: '{"error":"pageSize 不能超过 100"}' },
            { type: 'tool', toolName: 'listReviewTasks', status: 'completed', durationMs: 540, arguments: '{"pageSize":100}', result: '{"total":142}' }
          ]
        },
        {
          type: 'tool',
          toolName: 'call_data_analysis',
          toolCallId: 'tc_s8_2',
          agentCode: 'data-analysis',
          status: 'failed',
          durationMs: 180000,
          arguments: JSON.stringify({ task: '全表扫描近 5 年台账明细并与审批表做笛卡尔交叉比对' }),
          result: JSON.stringify({ error: '子助手执行超时（超过 180000ms），已自动终止子任务', agentCode: 'data-analysis', timeout: true }),
          subSteps: [
            { type: 'tool', toolName: 'executeSql', status: 'failed', durationMs: 175000, arguments: '{"sql":"SELECT * FROM ledger_5y CROSS JOIN review_5y"}', result: '{"error":"query timeout"}' }
          ]
        },
        { type: 'text', content: '审批记录已拉取完成，但数据分析专员因 5 年全量交叉比对超时（180s）被自动终止。建议按年份分批查询。' }
      ]
    })
  },
  {
    id: 's9',
    tag: '场景 9',
    title: '用户强停中断 & 历史回放',
    desc: '支持 9a 强停级联暂停（保留已执行步骤）与 9b 历史会话回放（还原折叠态、耗时与多 Tab 抽屉）',
    userQuery: '把所有驳回任务逐条拉取详情并整理修改建议',
    build: () => s9HistoryMode.value
      ? {
          streaming: false,
          elapsedMs: 28600,
          timeline: [
            {
              type: 'tool',
              toolName: 'call_declarant_resubmit',
              toolCallId: 'tc_s9_hist_1',
              agentCode: 'declarant-workflow',
              status: 'completed',
              durationMs: 18400,
              arguments: JSON.stringify({ task: '拉取全部被驳回任务明细并逐条分析原因' }),
              result: '已从历史会话还原：共梳理 12 条被驳回任务，其中 8 条缺少附件证明，4 条金额口径不一致。',
              subSteps: [
                { type: 'thinking', content: '拉取驳回任务列表后，按驳回原因分类汇总修改建议。', durationMs: 1600 },
                { type: 'tool', toolName: 'listMyTasks', status: 'completed', durationMs: 410, arguments: '{"type":"REJECTED"}', result: '{"count":12}' },
                { type: 'tool', toolName: 'getTaskDetail', status: 'completed', durationMs: 1200, arguments: '{"taskId":301}', result: '{"reason":"缺少施工验收单附件"}' }
              ]
            },
            {
              type: 'tool',
              toolName: 'call_reviewer_workflow',
              toolCallId: 'tc_s9_hist_2',
              agentCode: 'reviewer-workflow',
              status: 'completed',
              durationMs: 9200,
              arguments: JSON.stringify({ task: '核对驳回重提后的审批流转规则' }),
              result: '重提后将自动路由至原初审人张工复核。',
              subSteps: [
                { type: 'tool', toolName: 'listReviewTasks', status: 'completed', durationMs: 520, arguments: '{"status":"REJECTED"}', result: '{"rules":"route_to_original_reviewer"}' }
              ]
            },
            {
              type: 'text',
              content: '（历史会话回放）共梳理 **12 条被驳回任务**：\n1. **缺少附件（8 条）**：补齐施工验收单扫描件后可直接重提；\n2. **金额口径不一致（4 条）**：需按含税结算口径修正后提交原初审人张工复核。'
            }
          ]
        }
      : {
          streaming: false,
          interrupted: true,
          timeline: [
            {
              type: 'tool',
              toolName: 'call_declarant_resubmit',
              toolCallId: 'tc_s9_1',
              agentCode: 'declarant-workflow',
              status: 'interrupted',
              durationMs: 14300,
              arguments: JSON.stringify({ task: '拉取全部被驳回任务明细并逐条分析原因' }),
              result: '{"status":"PAUSED","agentCode":"declarant-workflow","message":"子助手已随主任务暂停"}',
              subSteps: [
                { type: 'tool', toolName: 'listMyTasks', status: 'completed', durationMs: 410, arguments: '{"type":"REJECTED"}', result: '{"count":12}' },
                { type: 'tool', toolName: 'getTaskDetail', status: 'interrupted', durationMs: 3200, arguments: '{"taskId":301}' }
              ]
            }
          ]
        }
  },
  {
    id: 's10',
    tag: '场景 10',
    title: '后台长跑 + 主会话阶段简报',
    desc: '对齐参考文档图 1：子专员后台长跑，主 Agent 先快扫并输出阶段简报，等全部完成后汇总',
    userQuery: '全面盘点本月重点项目进度和待审卡点，顺便核对下我上传的计划表',
    build: () => s10Done.value
      ? {
          streaming: false,
          elapsedMs: 112000,
          timeline: [
            {
              type: 'tool',
              toolName: 'call_data_analysis',
              toolCallId: 'tc_s10_1',
              agentCode: 'data-analysis',
              status: 'completed',
              durationMs: 96000,
              arguments: JSON.stringify({ task: '全面盘点本月所有重点项目填报完成率与滞后项' }),
              result: '本月 18/21 个重点项目已上报，3 个项目滞后超过 5 天。',
              subSteps: [{ type: 'tool', toolName: 'executeSql', status: 'completed', durationMs: 3200, arguments: '{"sql":"SELECT ..."}', result: '{"done":18,"total":21}' }]
            },
            {
              type: 'tool',
              toolName: 'call_reviewer_workflow',
              toolCallId: 'tc_s10_2',
              agentCode: 'reviewer-workflow',
              status: 'completed',
              durationMs: 68000,
              arguments: JSON.stringify({ task: '拉取全部待审卡点单据与停留时长' }),
              result: '共有 3 张单据在复审环节停留超 48 小时。',
              subSteps: [{ type: 'tool', toolName: 'listReviewTasks', status: 'completed', durationMs: 890, arguments: '{}', result: '{"overdue":3}' }]
            },
            { type: 'tool', toolName: 'analyzeFile', label: '主 Agent 快速核对：查阅 2 项本地计划表', durationText: '1.2s' },
            { type: 'text', content: '**阶段性简报（前期快速核对）**：当前会话已关联 3 个重点项目，本月截止日期为 30 日。' },
            { type: 'text', content: '### 最终全景汇总\n结合前期计划表核对与两位专员的排查结果：\n1. **填报进度**：21 个重点项目已完成 18 个，3 个项目滞后超 5 天；\n2. **审核卡点**：3 张单据在复审环节停留超 48 小时，建议优先催办。' }
          ]
        }
      : {
          streaming: true,
          timeline: [
            { type: 'text', content: '我正在并行排查（已派出 2 个专员在后台深度执行），等待期间我先快速核对手头的计划表：' },
            {
              type: 'tool',
              toolName: 'call_data_analysis',
              toolCallId: 'tc_s10_1',
              agentCode: 'data-analysis',
              status: 'running',
              startAt: baseNow - 42000,
              arguments: JSON.stringify({ task: '全面盘点本月所有重点项目填报完成率与滞后项' }),
              subSteps: [
                { type: 'tool', toolName: 'listTables', status: 'completed', durationMs: 310, arguments: '{}', result: '["project_info","ledger_record"]' },
                { type: 'tool', toolName: 'executeSql', status: 'running', startAt: baseNow - 8000, arguments: '{"sql":"SELECT project_name, rate FROM ..."}' }
              ]
            },
            {
              type: 'tool',
              toolName: 'call_reviewer_workflow',
              toolCallId: 'tc_s10_2',
              agentCode: 'reviewer-workflow',
              status: 'running',
              startAt: baseNow - 42000,
              arguments: JSON.stringify({ task: '拉取全部待审卡点单据与停留时长' }),
              subSteps: [
                { type: 'tool', toolName: 'listReviewTasks', status: 'running', startAt: baseNow - 6000, arguments: '{"includeOverdue":true}' }
              ]
            },
            { type: 'tool', toolName: 'analyzeFile', label: '主 Agent 快速核对：已查阅 2 项计划表与规则', durationText: '1.2s' },
            {
              type: 'text',
              content: '**📌 阶段性简报（子专员仍在后台执行，完成后我会继续汇总）：**\n- **已确认**：当前会话关联了 3 个重点项目，本月考核截止日期为 30 日；\n- **待专员确认**：各项目实时完成率及卡在审核环节的超时单据明细。'
            }
          ]
        }
  }
]

const currentScenario = computed(() => scenarios.find((s) => s.id === activeScenarioId.value) || scenarios[0])
const currentMsg = computed(() => currentScenario.value.build())

const isCollapsible = computed(() => {
  const m = currentMsg.value
  if (m.streaming || m.interrupted) return false
  const tl = m.timeline || []
  const lastTextIdx = tl.map((x, i) => (x.type === 'text' ? i : -1)).filter((i) => i >= 0).at(-1)
  return lastTextIdx > 0
})

const visibleTimeline = computed(() => {
  const tl = currentMsg.value.timeline || []
  if (!isCollapsible.value || !collapsed.value) return tl
  const lastTextIdx = tl.map((x, i) => (x.type === 'text' ? i : -1)).filter((i) => i >= 0).at(-1)
  return tl.slice(lastTextIdx)
})

const summaryHeadText = computed(() => {
  const m = currentMsg.value
  const sec = m.elapsedMs ? `${(m.elapsedMs / 1000).toFixed(1)} 秒` : '12.0 秒'
  const subs = (m.timeline || []).filter((it) => isSubAgentTool(it))
  const names = [...new Set(subs.map((t) => subAgentDisplayName(t)))]
  return `已工作 · ${sec} · 协作 ${subs.length} 个专员（${names.join('、')}）`
})

const drawerItems = computed(() => {
  return (currentMsg.value.timeline || [])
    .filter((it) => isSubAgentTool(it))
    .map((it) => ({ key: it.toolCallId, step: it }))
})

function selectScenario(id) {
  activeScenarioId.value = id
  s7ActionState.value = 'pending'
  s9HistoryMode.value = false
  s10Done.value = false
  const sc = scenarios.find((s) => s.id === id)
  collapsed.value = !!sc?.defaultCollapsed
  if (sc?.autoOpenDrawer) {
    openFirstSub()
  } else {
    drawer.open = false
  }
}

function handleConfirmCard(action) {
  s7ActionState.value = action
  if (action === 'confirmed') {
    ElMessage.success('已确认执行提审操作（演示模式）')
  } else {
    ElMessage.info('已取消提审操作（演示模式）')
  }
}

function toggleS9Mode() {
  s9HistoryMode.value = !s9HistoryMode.value
  collapsed.value = s9HistoryMode.value
  drawer.open = false
}

function openDrawer(step) {
  drawer.activeKey = step.toolCallId
  drawer.open = true
}

function openFirstSub() {
  const first = drawerItems.value[0]
  if (first) {
    drawer.activeKey = first.key
    drawer.open = true
  }
}

const LABELS = {
  listTables: '查表清单',
  describeTables: '查表结构',
  executeSql: '执行查询',
  generateChart: '生成图表',
  listReviewTasks: '查待审任务',
  listMyProjects: '查我的项目',
  listFillNodes: '查填报节点',
  listMyDrafts: '查我的草稿',
  submitLedgerForReview: '发起提审',
  listMyTasks: '查我的待办',
  getTaskDetail: '查任务详情'
}

function toolLabel(name) {
  return LABELS[name] || name || 'unknown'
}

function argPreview(args) {
  if (!args) return ''
  const s = String(args).replace(/\s+/g, ' ').trim()
  return s === '{}' ? '' : (s.length > 48 ? `${s.slice(0, 48)}…` : s)
}

function formatJson(raw) {
  if (!raw) return ''
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return String(raw)
  }
}

function renderMd(text) {
  return renderReportMarkdown(text, { stripHeaderMeta: false, enableRoundLinks: false })
}
</script>
