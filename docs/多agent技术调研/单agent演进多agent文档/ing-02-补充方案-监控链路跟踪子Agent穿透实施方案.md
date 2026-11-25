# 监控链路跟踪子 Agent 穿透实施方案（追溯视图补齐子会话轮次树）

> 状态标记规则：每完成一个任务，把对应 `- [ ]` 改为 `- [x]`，并在任务后追加 `（完成于 YYYY-MM-DD）`。
> 里程碑总览表中的状态随进度更新：`未开始 / 进行中 / 已完成`。
> 前置：本文是 `02-主路由与有状态垂类子Agent架构演进技术方案.md` §3「子 Agent 全链路 Trace 留痕」的读侧收尾。
> M-SA3 已完成写侧落库（父子列 + 索引迁移），M-SA5 已完成 ChatView 嵌套子卡片；**遗留缺口 = 监控中心读侧**。

---

## 0. 里程碑总览

| 里程碑 | 范围 | 状态 |
|---|---|---|
| M-TT1 后端子会话轮次树组装 | `SessionTimelineVO` 扩展 + `buildEvents` 重构 + 子会话挂载/孤儿兜底 + 单测 | 已完成（2026-11-25） |
| M-TT2 风险与诊断穿透 | `sessionRisks` 联查子会话 + `RiskItem` 标注 + `overview()` 子会话标注（可选） | 已完成（T2.3 可选项未实施，2026-11-25） |
| M-TT3 前端子Agent块渲染 | 抽 `TimelineEventList.vue` + 委派行徽标/展开收起 + 失败自动展开 + 孤儿兜底区 | 已完成（2026-11-25） |
| M-TT4 前端联动 | EventSnapshot 子会话汇总卡/双导航 + 筛选联动 + 深链自动展开 + 风险徽标 | 已完成（T4.6 可选项未实施，2026-11-25） |
| M-TT5 验收 | testhub m-sa.js 扩展 + 浏览器自动化验收 + single 模式回归 | 已完成（2026-11-25） |

---

## 1. 背景：写入已闭环，读侧全部把子会话过滤/丢掉了

子 Agent 的 trace 数据**已经全部落库**（M-SA3 完成写侧）：子 Agent 是完整的 `ReactAgent`，带着同一个 `CompositeTraceCollector` 跑自己的 `AgentLoopExecutor` 循环，其轮次写进 `agentx_monitor_trace`（带 `agent_code` / `parent_session_id`），子会话行写进 `agentx_monitor_session`（带 `parent_session_id` / `parent_tool_call_id`），`conversation_id` 统一记为根会话 ID。`/test/subagent-check` 探针断言过这些。

**断点在监控中心读侧**，四个位置环环相扣：

| # | 位置 | 现状 |
|---|---|---|
| 1 | `MonitorQueryService.sessionTimeline()`（≈L325） | 只查 `WHERE session_id = 主会话`，从不遍历 `parent_session_id` |
| 2 | `SessionTimelineVO` | 事件流 VO 无 `subSession` / `agentCode` / 子会话概念，没有树可建 |
| 3 | `MonitorQueryService` sessions 列表（≈L96）与 conversationDetail（≈L258） | 刻意 `parent_session_id IS NULL`（一问一行口径正确，但导致没有任何入口能看到子会话） |
| 4 | `TraceDetailView.vue` + `RiskStore.listBySession` | 前端无嵌套渲染能力；风险查询只按根 session，子会话风险命中同样不可见 |

**结果**：追溯视图（`/monitor/traces/:id`）里子 Agent 委派只显示为一个普通工具行 `call_data_analysis` + 160 字结果预览，内部轮次/子工具/失败原因全部不可见。设计文档 `02` 的 M-SA4 清单「监控中心前端支持展开子 Agent Trace 轮次树」未实施。

---

## 2. 方案总览（读侧补齐，写侧零改动）

```
 写侧(不动)  主AgentLoopExecutor ── trace(S_root, R1..Rn, agent_code='router')
             子AgentLoopExecutor ── trace(S_sub, Sub-R1..Rm, parent_session_id=S_root)
                                        │ 已落库: agentx_monitor_session/trace + payload
                                        ▼
 组装(改造)  MonitorQueryService.sessionTimeline(S_root)
               ① 主会话事件组装(现逻辑抽成 buildEvents(),复用)
               ② SELECT * FROM agentx_monitor_session WHERE parent_session_id = S_root
               ③ 每个子会话 buildEvents() → SubSessionVO{agentCode,task,events[]}
               ④ 子会话按 parent_tool_call_id == 委派tool事件的callId 挂到该tool事件上
               ⑤ 挂不上的 → VO顶层 orphanSubSessions 兜底展示
                                        ▼
 契约(扩展)  SessionTimelineVO.Event 新增 subSession 字段(嵌套一层,事件结构完全复用)
                                        ▼
 呈现(新增)  TraceDetailView 左轴: 委派行 ▸ 展开嵌套"子Agent块"; EventSnapshot 右栏联动
```

### 已决策事项

| # | 决策点 | 结论 |
|---|---|---|
| D1 | 子会话事件加载方式 | **随 timeline 一次性内嵌（eager）**。理由：子 Agent 有轮次上限（内部轮次 ≤ 10），瘦字段+delta 全量也就几十 KB，与聊天回放 `SessionService.buildTimeline` 全量内嵌 subSteps 口径一致；大字段仍走 `GET /monitor/rounds/{traceId}` 懒加载 |
| D2 | 嵌套结构 vs 扁平数组 | **嵌套结构**：子事件 `seq` 在子块内独立编号，前端 key 用 `sub:{subSessionId}:{seq}`；`parentSeq`/`resultRefSeq` 在子块内自洽。主轴现有 `childToolsOf(seq)` 一层树逻辑零改动 |
| D3 | 嵌套深度 | **只嵌一层**：子 Agent 工具池物理隔离（不能再派生），一层足够；VO 用独立 `SubSession` 类型，将来要递归再演进 |
| D4 | 旧数据兼容 | M-SA3 之前的旧数据无关联列 → 挂不上的进 `orphanSubSessions` 兜底区**可见**，不静默丢失 |

---

## 3. 后端改造

### 3.1 `SessionTimelineVO` 扩展（`monitor/dto/SessionTimelineVO.java`）

```java
public record SessionTimelineVO(
        ..., List<Event> events,
        List<SubSession> orphanSubSessions) {          // 新增：挂不上委派点的子会话兜底

    public record Event(..., SubSession subSession) {} // tool事件新增1个字段

    /** 委派子Agent执行块：挂在匹配 parent_tool_call_id 的 tool 事件上 */
    public record SubSession(
            String agentCode,        // data-analysis 等
            String sessionId,        // 子会话ID(事件seq在此命名空间内)
            String task,             // 子会话行 question = 父委派的任务文本
            String status,
            Integer roundCount, Integer failRounds,
            Long promptTokens, Long completionTokens,
            List<Event> events) {}   // 复用Event结构(含user起点=任务、llm、tool、answer=产出)
}
```

### 3.2 `sessionTimeline()` 组装（`MonitorQueryService.java` ≈L325）

1. 现有"逐轮解析→事件构建"抽成私有方法 `buildEvents(String sessionId, ...)`（主/子复用，一次重构不改行为）；
2. 先查子会话：`SELECT ... FROM agentx_monitor_session WHERE parent_session_id = ? ORDER BY created_at ASC`，建 `parentToolCallId → SubSession` 映射；
3. 构建 tool 事件时（现有 ≈L434 循环处），`call.callId()` 命中映射则填 `subSession`（record 不可变 → 构造时传入即可）；
4. 未命中的进 `orphanSubSessions`（含 agentCode + sessionId + 状态 + 轮次摘要，前端兜底区可见）。

### 3.3 风险查询穿透子会话（`RiskStore.java` ≈L170）

```sql
WHERE session_id = ?
   OR session_id IN (SELECT session_id FROM agentx_monitor_session WHERE parent_session_id = ?)
```

`RiskItem` 增加 `sessionId`、`agentCode`（join `agentx_monitor_session` 取），供前端打「子Agent」徽标并按 `${subSessionId}:${round}` 挂到子事件上。

### 3.4 会话详情轮次标注（小改）

`conversationDetail()` 的 rounds 查询（≈L280，目前已按 conversation_id 混入子轮次）给 `RoundListItem` 加 `agentCode` 字段，前端深链 `?traceId=` 命中子轮次时可反查归属自动展开。

可选增强：AI 诊断 `TraceQueryService.overview()` 已天然含子会话（seq=0 行 + 全部轮次），提问列表行补 `[data-analysis 子Agent]` 标注。

---

## 4. 前端改造与界面交互（TraceDetailView / EventSnapshot）

### 4.1 追溯视图左轴 — 委派行升级为可展开「子Agent块」（核心交互）

```
 现状（子Agent内部完全不可见）:
 ┌─ 事件流 (5) ──────────────────────────────────────┐
 │ ❓ 提问: 统计上季度项目付款情况                     │
 │ ● 🤖 LLM 决策 R1 · 1.2s · 0.9k tok                │
 │ │   🔧 query_table ✅ 0.8s                         │
 │ │   🔧 call_data_analysis ✅ 23.4s                 │
 │ │       └ 仅160字JSON结果预览 ← 内部一片黑盒        │
 │ ● 🤖 LLM 决策 R2 · 2.1s                           │
 │ 💬 终答 · 3.1s                                     │
 └────────────────────────────────────────────────────┘

 目标（点开 ▾ 展开嵌套子Agent块,再点收起）:
 ┌─ 事件流 (5) ──────────────────────────────────────┐
 │ ❓ 提问: 统计上季度项目付款情况                     │
 │ ● 🤖 LLM 决策 R1 · 1.2s · 0.9k tok                │
 │ │   🔧 query_table ✅ 0.8s                         │
 │ │   🔧 call_data_analysis ✅ 23.4s                 │
 │ │      🤖 问数子Agent · 内部4轮/7步 · 8.2k tok  ▾  │ ← 委派行摘要徽标
 │ │   ┌─ data-analysis · ✅ completed ────────────┐  │
 │ │   │ 🎯 任务: 分析上季度付款数据并输出结论       │  │ ← SubSession.task
 │ │   │ ● 🤖 Sub-R1 · 2.0s · 1.1k tok             │  │
 │ │   │ │   🔧 lookupGlossary ✅ 0.3s             │  │ ← 子块内仍是
 │ │   │ │   🔧 listTables ✅ 0.6s                  │  │   llm→tool一层树
 │ │   │ ● 🤖 Sub-R2 ❌ · 5.2s                      │  │
 │ │   │ │   🔧 executeSql ❌ 语法错误 ⚠REACT-002   │  │ ← 风险角标(4.4)
 │ │   │ ● 🤖 Sub-R3 · 4.0s                         │  │
 │ │   │ │   🔧 executeSql ✅ 1.8s                  │  │
 │ │   │ 💬 子Agent产出 · 4.1k 字                    │  │
 │ │   └────────────────────────────────────────────┘  │
 │ ● 🤖 LLM 决策 R2 · 2.1s                           │
 │ 💬 终答 · 3.1s                                     │
 │                                                    │
 │ ⛓ 未关联子会话 (1) ▸  ← orphanSubSessions兜底区     │
 └────────────────────────────────────────────────────┘
```

交互规则：

- **默认收起**；子会话含失败轮或有 high 风险命中时**自动展开并红描边**（打开即看到病灶）；
- 展开是纯前端折叠（数据已随 timeline 返回，无二次请求）；
- 子块整体缩进 + 左竖线 + 浅底色（对齐 ChatView 嵌套子卡片的视觉语言 `border-l-2 + pl-3`）；
- 子事件行的耗时/tokens/失败标红等渲染**复用现有行逻辑**：把「事件+children 一层树」渲染抽成局部组件 `TimelineEventList.vue`（props: `events / risks / keyPrefix / collapsed`），主轴与子块共用，避免复制模板。

### 4.2 右栏事件快照 — 委派事件与子事件的联动

```
 点击委派行 call_data_analysis 时:              点击子块内 Sub-R2 行时:
 ┌─ 事件快照 ──────────────────────┐          ┌─ 事件快照 ──────────────────────────┐
 │ 🔧 call_data_analysis · R1      │          │ 🤖 LLM 决策 · Sub-R2                │
 │ 🤖 委派: data-analysis(问数子A) │          │ [子Agent] data-analysis · Sub-R2    │
 │────────────────────────────────│          │─────────────────────────────────────│
 │ 状态 ✅ completed · 23.4s       │          │ 状态   ❌ error                      │
 │ 任务: 分析上季度付款数据…        │          │ 耗时   5.2s · 0.8k tok              │
 │ 内部: 4轮 · 7步工具 · 8.2k tok  │          │ think  ⚙ 1.1s                        │
 │  ❌ 失败1轮: Sub-R2 executeSql  │          │ [执行结果][输出·决策][模型思考]       │
 │    语法错误 → 点击定位 ↗        │          │ [新传入delta][完整上下文]  ← 5个Tab   │
 │────────────────────────────────│          │   照常可用(payload懒加载不受影响)     │
 │ 执行结果(最终产出JSON,同现状)    │          │                                      │
 │ ┌────────────────────────────┐  │          │ ◂ 父级决策 (Sub-R2的llm父,子块内)    │
 │ │ {"summary":"上季度付款…"}    │  │          │ ↩ 返回委派处 (主轴R1·该tool事件)     │
 │ └────────────────────────────┘  │          └──────────────────────────────────────┘
 │ 内部执行明细 ▾                   │
 │  ▸ Sub-R1 llm    ✅ 2.0s        │          导航按钮交互:
 │  ▸ Sub-R2 llm    ❌ 5.2s ←定位  │           ◂ 父级决策 → locate(sub:{S_sub}:{seq-…})
 │  ▸ 产出 text     ✅ 4.1k字      │           ↩ 返回委派 → locate(主轴tool事件seq)
 └──────────────────────────────────┘          (EventSnapshot emit locate,父组件统一处理,
                                               key带sub前缀区分主/子命名空间)
```

### 4.3 筛选与深链联动

```
 用户点筛选 [失败●]                     左轴变化
 ─────────────────────────────────────────────────────────────────────
 ● 🤖 LLM 决策 R1 (保留: 子块内有命中)
 │   🔧 call_data_analysis ⚠ (保留: 委派行)
 │   └─┌ data-analysis ❌ ─────────────┐   ← 子块独立过滤:
 │      │ ● 🤖 Sub-R2 ❌ · 5.2s        │     "子命中保留父链"与主轴同规则
 │      │ │   🔧 executeSql ❌ 语法错误 │     (现有 visibleKeep 逻辑扩展一层)
 ● 🤖 LLM 决策 R5 ❌ (主轴自身命中)      │     未命中的兄弟子事件整行隐藏
 └──────┴───────────────────────────────┘

 深链 ?traceId=子轮次traceId:
 打开 → 详情页定位 → 不在主events → 扫各subSession.events命中
      → 自动切到该提问 → 自动展开该子Agent块 → 高亮该子事件行
```

### 4.4 检测区域风险卡 — 子会话风险标注

```
 ┌─ 检测区域 (2) ─────────────────────────────────────────────┐
 │ ⚠ REACT-002 连续空结果轮次                     [high]       │
 │   命中: Sub-R3 · 🤖问数子Agent(data-analysis)  ← 新增徽标   │
 │   证据: executeSql 连续3轮返回空结果…          [查看 ↗定位] │
 │ ⚠ TOOL-001 连续工具失败                        [medium]     │
 │   命中: R5 · 主链路                                          │
 └─────────────────────────────────────────────────────────────┘
 (数据源: sessionRisks 联查子会话 + RiskItem.agentCode; 命中子轮的
  风险在左轴子事件行也打 ⚠ 角标,点击互跳)
```

### 4.5 顶部统计与聊天页入口（小增强）

- 追溯视图头部 "9 轮 · 45.2k tok" → "9 轮（含子Agent 4 轮）· 45.2k tok"（conversation 聚合列 M-SA3 已是主+子口径，只改文案展示）；
- ChatView 子卡片右上角加 "在监控中心查看 ↗"（可选，打通两套 UI：`/monitor/traces/{convId}?sessionId={rootSessionId}`）。

---

## 5. 兼容与边界

| 场景 | 影响 |
|---|---|
| single 模式（无子 Agent） | `parent_session_id` 查不到行 → VO 为空数组，行为与现状逐字节一致，零回归 |
| Event record 加字段 | 构造点全部在 `MonitorQueryService` 内部；前端序列化多一个 `null` 字段，无破坏 |
| 旧数据（M-SA3 前） | 无关联列 → 走 `orphanSubSessions` 兜底区可见，不静默丢失 |
| 大字段 | 不进 timeline（仍走 `rounds/{traceId}` 懒加载 + >100KB 摘要门控，策略不变） |
| 一问一行口径 | 列表/详情 sessions 仍 `parent_session_id IS NULL`，大盘提问数不虚增（不动） |
| 遗留小口径 | 列表「风险」筛选用根会话行的 `risk_total`，子会话风险未计入根行计数 → 后续小项（`refreshConversation` 级联吸收），本期先在详情页全量展示 |

---

## 6. 任务清单

### M-TT1 后端子会话轮次树组装

- [x] **T1.1** `SessionTimelineVO` 扩展：`Event.subSession` 字段 + `SubSession` record + 顶层 `orphanSubSessions`（§3.1）（完成于 2026-11-25）
- [x] **T1.2** `MonitorQueryService` 重构：事件组装抽 `buildEvents()` 私有方法（行为不变回归）（完成于 2026-11-25）
- [x] **T1.3** 子会话查询与挂载：`parent_session_id = ?` 查子会话 → `parent_tool_call_id == callId` 挂载 → 未命中进 `orphanSubSessions`（§3.2）（完成于 2026-11-25）
- [x] **T1.4** 单测：多子会话（同轮双委派/跨轮委派）/ 失败子会话 / 无子会话（现状回归）/ 旧数据孤儿兜底 / seq 命名空间自洽（完成于 2026-11-25：`MonitorQueryServiceTimelineTest` 8 用例 H2 真实跑 SQL）

### M-TT2 风险与诊断穿透

- [x] **T2.1** `RiskStore.listBySession` 联查子会话风险 + `RiskItem` 增加 `sessionId/agentCode`（§3.3）（完成于 2026-11-25：severity 仍是第一排序键，同级别内根会话排前；`RiskStoreSubSessionTest` 2 用例）
- [x] **T2.2** `RoundListItem` 增加 `agentCode`（§3.4）（完成于 2026-11-25；另加 `parentSessionId` 支撑深链子轮次归属反查）
- [ ] **T2.3**（可选）`TraceQueryService.overview()` 提问列表行补 `[agentCode 子Agent]` 标注（本期未实施）

### M-TT3 前端子Agent块渲染

- [x] **T3.1** 抽 `TimelineEventList.vue`（事件+children 一层树渲染，主轴/子块复用），主轴行为回归（完成于 2026-11-25）
- [x] **T3.2** 委派 tool 行升级：`subSession` 存在时显示徽标（agentCode 中文名/内部N轮M步/tok/状态点/▾）（完成于 2026-11-25）
- [x] **T3.3** 子Agent块渲染：`SubSession.task` 头部 + 子事件嵌套展示（key=`sub:{subSessionId}:{seq}`）+ 展开/收起（完成于 2026-11-25）
- [x] **T3.4** 默认收起；含失败轮或 high 风险自动展开 + 红描边；`orphanSubSessions` 兜底区（完成于 2026-11-25）

### M-TT4 前端联动

- [x] **T4.1** EventSnapshot：委派事件子会话汇总卡（任务/状态/轮次/失败轮/内部明细迷你列表）+「返回委派处」导航（完成于 2026-11-25）
- [x] **T4.2** 子事件快照：`[子Agent] agentCode · Sub-Rx` 头部徽标 +「父级决策」子块内定位（完成于 2026-11-25）
- [x] **T4.3** 筛选联动：失败/工具/风险筛选扫子事件，「子命中保留父链」（`visibleKeep` 扩展一层）（完成于 2026-11-25）
- [x] **T4.4** 风险标注：风险卡子会话徽标 + 子事件行 ⚠ 角标互跳（完成于 2026-11-25）
- [x] **T4.5** 深链：`?traceId=` 命中子轮次 → 自动切换提问 + 展开子块 + 高亮；头部统计「含子Agent N 轮」（完成于 2026-11-25）
- [ ] **T4.6**（可选）ChatView 子卡片「在监控中心查看 ↗」入口（本期未实施）

### M-TT5 验收

- [x] **T5.1** `testhub/scenarios/m-sa.js` 扩展：multi 模式跑通后断言 `GET /monitor/sessions/{rootSid}/timeline` 含 `subSession.events` 且 `parent_tool_call_id` 匹配委派 callId（完成于 2026-11-25：MSA-601 + `assert.js` 新增 `jsonSome` 断言，实跑通过）
- [x] **T5.2** 浏览器自动化验收：追溯视图展开子块可见 Sub 轮次/子工具/失败原因；点击子事件快照正确；双导航按钮互跳正确（完成于 2026-11-25）
- [x] **T5.3** 筛选/深链验收：失败筛选保留父链、深链子轮次自动展开高亮（完成于 2026-11-25）
- [x] **T5.4** 回归：single 模式 timeline 逐字段一致（无子会话零回归）；`TraceQueryServiceTest` 等现有测试全绿（完成于 2026-11-25：新增 10 用例 + 现有 127 用例全绿）
- [x] **T5.5** 文档收尾：本清单勾选 + `02-主路由…技术方案.md` M-SA4 遗留项标注完成（完成于 2026-11-25）

---

## 7. 决策记录

| 日期 | 决策 | 说明 |
|---|---|---|
| 2026-11-25 | D1 eager 内嵌 | 子会话事件随 timeline 一次返回，不做展开时二次请求；大字段仍懒加载 |
| 2026-11-25 | D2 嵌套结构 | 子事件 seq 独立命名空间，前端 key 带前缀；主轴一层树逻辑零改动 |
| 2026-11-25 | D3 只嵌一层 | 子 Agent 工具池物理隔离，无孙代；VO 独立 SubSession 类型留演进空间 |
| 2026-11-25 | D4 孤儿兜底 | 旧数据/关联缺失的子会话进 orphanSubSessions 可见区，不静默丢弃 |
| 2026-11-25 | 一问一行不动 | sessions 列表/详情维持 `parent_session_id IS NULL`，大盘口径不受影响 |

---

## 8. 实施记录（2026-11-25 完成）

按方案全部落地，M-TT1~M-TT5 完成；T2.3 / T4.6 两个可选项未实施。验收结果：

- **后端**：`SessionTimelineVO`（Event.subSession + SubSession + orphanSubSessions）、`MonitorQueryService.buildEvents()` 抽取复用 + 挂载/孤儿、`RiskStore.listBySession` 联查子会话（severity 第一排序键、同级内根会话排前）、`RiskItem`/`RoundListItem` 加 `agentCode`（RoundListItem 另加 `parentSessionId` 支撑深链归属反查）。单测 `MonitorQueryServiceTimelineTest`（8 用例）+ `RiskStoreSubSessionTest`（2 用例）H2 真实跑 SQL 全绿，现有 `TraceQueryServiceTest` 等 127 用例无回归。
- **前端**：抽 `TimelineEventList.vue`（递归复用渲染主轴/子块，`sub:{subSessionId}:{seq}` key 命名空间）；委派行徽标 + 子块（默认收起/失败自动展开红描边）+ 孤儿兜底区；EventSnapshot 子会话汇总卡 + `[子Agent]` 徽标 + 双导航（◂父级决策 / ↩返回委派处）；失败/工具/风险筛选「子命中保留父链」；深链 `?traceId=` 命中子轮次自动切问展开高亮；风险卡子会话徽标互跳；头部「含子Agent N 轮」。
- **testhub**：`m-sa.js` 新增 M6/MSA-601（timeline subSession 挂载断言），`assert.js` 新增 `jsonSome` 断言（数组任一元素子路径条件校验）；实跑通过。

实施中发现的两处补充修正（已随本方案落地）：

1. **Long→String 序列化**：后端全局 Jackson 把 Long 序列化为字符串（防雪花 ID 精度丢失），前端 `applyTimeline` 需对 SubSession/Event 数值字段做 `Number()` 归一化，否则徽标 token 求和变成字符串拼接（实测出现「16.3M tok」假象）。
2. **vite 代理目标可覆盖**：`vite.config.js` 各代理 target 改为读 `DATA_AGENT_API_TARGET` 环境变量（默认仍 8889）——本机 8889 被旧实例占用时可用 `DATA_AGENT_API_TARGET=http://localhost:8890 npm run dev` 指向新实例，不影响生产。

### 8.1 真实链路验收补记（2026-11-25 追加）

除单元测试外，用 **admin 账号走完整 chat 路径**再回看 trace 路径（不依赖历史样本数据）：

1. `admin / hxxg@!123` 登录 → 智能对话页新会话提问「帮我查一下当前一共有多少个项目，并按项目状态分组统计数量」→ 主路由委派 `call_data_analysis`（问数子Agent），子Agent 实际执行 `executeSql` 聚合查询并返回真实统计口径结论；
2. 该会话（`dodo_conv_0b465565-…`）的监控追溯视图：头部「2 轮（含子Agent 17 轮）」、委派行徽标「内部17轮/26步 · 533.0k tok」、子块含 🎯任务行 + 子事件树（lookupGlossary / listTables / executeSql）；子事件快照 `[子Agent] 问数子Agent` + 双导航（◂父级决策 / ↩返回委派处）互跳正确；子会话风险卡徽标「Sub-R15 · 🤖问数子Agent(data-analysis)」可定位到子轮次。

**验收中纠正的两处实现偏差**（已修复并回归）：

- **红描边/自动展开口径**：初版把「工具级失败（❌ 结果但轮次 success=1、自愈重试）」也算作失败轮，会把**正常完成**的子会话误标「含失败轮」+红描边。按方案 §4.1 口径收敛为：`failRounds > 0` / 子会话 `status = error` / 命中 **high** 风险三者之一才自动展开 + 红描边；工具级失败仍以行内 ❌ 呈现，不误标严重性。口径由父组件统一计算（`problemSubIds`），组件不再自行判定。
- **Vue setup TDZ 白屏**：`problemSubIds` computed 依赖 `risks`，若声明在 `risks` 之前，`watch(source)` 注册时会立即求值触发 `Cannot access 'risks' before initialization` → setup 抛错 → 追溯视图整页白屏（监控页无报错提示，只有空白页）。修复：把该 computed 与 watch 移到 `risks` 声明之后。教训：`<script setup>` 中 `watch` 会立即求值 source，被 watch 的依赖必须先声明。
