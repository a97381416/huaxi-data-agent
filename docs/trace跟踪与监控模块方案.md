# Trace 跟踪与监控模块方案（含里程碑任务清单）

> 状态标记规则：每完成一个任务，把对应 `- [ ]` 改为 `- [x]`，并在任务后追加 `（完成于 YYYY-MM-DD）`。
> 里程碑总览表中的状态随进度更新：`未开始 / 进行中 / 已完成`。

---

## 0. 里程碑总览

| 里程碑 | 范围 | 状态 |
|---|---|---|
| M1 数据层改造 | trace 瘦表 + payload 明细表拆分、双写迁移 | 未开始 |
| M2 后端查询服务与 API | monitor 包、查询/搜索/指标聚合 API | 未开始 |
| M3 前端监控框架与列表 | 路由框架、Dashboard、会话列表、命令面板 | 未开始 |
| M4 会话详情页 | 三栏布局、时间轴虚拟滚动、轮次明细懒加载 | 未开始 |
| M5 AI 诊断与报告 | SSE 诊断抽屉、列表行入口、报告落库、报告中心 | 未开始 |
| Backlog | DIFF 视图、原始消息链视图、告警预警、通知渠道 | 本期不做（已决策） |

---

## 1. 背景与目标

基于现有 trace 体系（`agentx_trace` 每轮一行、`agentx_conversation` 会话元信息、`agentx_session` 运行时状态切片）建设统一的跟踪与监控模块：

1. **人工排查问题**：按 user_id / session_id / conversation_id 快捷定位会话，逐轮查看 input/output/think 原文；
2. **Agent 自动分析并生成报告**：复用 M8 诊断 Agent 范式（ReactAgent + trace 工具 + 归因四分类），单会话手动触发 + 每日定时汇总；
3. **监控总览**：KPI、趋势、失败会话 TOP、进行中会话卡死检测。

**本期明确不做**（详见 §8 决策记录与 §7 Backlog）：轮次 DIFF 视图、原始消息链视图、告警预警、邮件/webhook 通知。

---

## 2. 已决策事项（2026-11-18 评审确认）

| # | 决策点 | 结论 |
|---|---|---|
| D1 | 会话详情页布局 | 采用三栏布局（会话信息 / 轮次时间轴 / 轮次明细） |
| D2 | DIFF 视图、原始消息链(item_index)视图 | 本期不做，列入 Backlog |
| D3 | 告警预警（规则引擎/告警中心/通知渠道） | 本期不做，列入 Backlog；Dashboard 仅保留监控展示 |
| D4 | AI 诊断入口 | 会话详情页右上角 + **会话列表页每行操作列** 两处入口 |

---

## 3. 总体架构

```
采集层   TraceManager(已有,每轮写) ──┬─> agentx_trace(瘦索引表,改造)
                                     └─> agentx_trace_payload(新增,大字段明细)
                  ActionAuditLogger(已有) ─> agentx_action_log(不动)
存储层   agentx_conversation / agentx_session(不动)
         agentx_monitor_metric(新增,分钟粒度聚合) ← 定时任务刷
         agentx_monitor_report(新增,AI 报告落库)
服务层   MonitorQueryService(人工排查查询)
         MetricAggregateJob(指标聚合定时任务)
         MonitorDiagnoseService(复用 AgentDiagnoseService 模式, SSE)
前端     /monitor 路由组: 总览 / 会话跟踪 / 报告中心
```

---

## 4. 数据层设计

### 4.1 结论：拆明细表，方向是"把大字段挪出去"

`agentx_trace.input_data` 存的是全量消息快照，随对话变长线性膨胀（长会话一轮可达数百 KB），而监控聚合、列表查询、count、排序不需要它。拆分收益：

- 列表/趋势查询只扫瘦表，TOAST / vacuum 压力大幅下降；
- 保留策略可分离：明细留 30 天、索引留 1 年，冷数据按 `created_at` 清理；
- 改动面小：`TraceManager.trace()` 一事务写两表；`TraceQueryService.renderRound()` 改为 join payload（仅一处），eval 三个工具其余不动。

**不拆到消息级明细表**：诊断 Agent 与人工排查均按轮消费，逐消息拆表只增加写入放大；`agentx_session.state_data` 为运行时记忆，保持与审计解耦，不动。

### 4.2 `agentx_trace` 改造为瘦索引表

```sql
-- 新增分析列(写入时顺手提取,避免运行时解析大JSON)
ALTER TABLE agentx_trace ADD COLUMN round_type varchar(10);   -- tool/text/error
ALTER TABLE agentx_trace ADD COLUMN tool_names text[];        -- 本轮调用的工具名
ALTER TABLE agentx_trace ADD COLUMN input_chars int;          -- 明细体积,前端"大字段懒加载"用

-- 瘦表专用索引
CREATE UNIQUE INDEX IF NOT EXISTS uk_trace_conv_round ON agentx_trace (conversation_id, round);
CREATE INDEX IF NOT EXISTS idx_trace_created ON agentx_trace (created_at);
CREATE INDEX IF NOT EXISTS idx_trace_failed ON agentx_trace (conversation_id) WHERE success = 0;
```

### 4.3 新增 `agentx_trace_payload` 明细表

```sql
CREATE TABLE IF NOT EXISTS agentx_trace_payload (
  trace_id bigint PRIMARY KEY,          -- = agentx_trace.id, 一对一
  input_data text,
  output_data text,
  think text,
  created_at timestamp NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_payload_created ON agentx_trace_payload (created_at);
```

### 4.4 新增聚合表与报告表

```sql
-- 分钟粒度指标(定时任务每5分钟滚动刷新, Dashboard 与后续预警窗口都查它, 不打明细表)
CREATE TABLE IF NOT EXISTS agentx_monitor_metric (
  id bigint NOT NULL,
  minute timestamp NOT NULL,
  metric varchar(50) NOT NULL,     -- convs/rounds/fail_rounds/error_convs/avg_ms/p95_ms/tokens
  dim_user varchar(100) NULL,      -- 维度可空, 支持按用户切片
  value double precision NOT NULL,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_metric ON agentx_monitor_metric (metric, minute);

-- AI 诊断/汇总报告
CREATE TABLE IF NOT EXISTS agentx_monitor_report (
  id bigint NOT NULL,
  type varchar(20) NOT NULL,               -- single(单会话诊断) / daily(日报)
  conversation_id varchar(100) NULL,       -- single 类型填, daily 为 NULL
  period_start timestamp NULL,
  period_end timestamp NULL,
  status varchar(20) NOT NULL,             -- running / done / failed
  summary_md text NULL,
  detail_md text NULL,
  metrics_json jsonb NULL,
  created_by varchar(20) NOT NULL,         -- manual / auto
  created_at timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_report_conv ON agentx_monitor_report (conversation_id);
```

### 4.5 迁移步骤（顺序执行，全程 eval 体系行为不变）

1. 建 payload 表 + 瘦表加列加索引（存量表 `ADD COLUMN IF NOT EXISTS` 兼容）；
2. `TraceManager` 改同事务双写（瘦表新列 + payload 表）；
3. 回刷历史：`INSERT INTO agentx_trace_payload SELECT id, input_data, output_data, think, created_at FROM agentx_trace`，同时补算新列；
4. `TraceQueryService.renderRound()` 切换为 join payload，跑 eval 回归（TraceQueryServiceTest）；
5. 稳定运行一周后，`agentx_trace` 旧大字段置 NULL 回收空间，写入侧停写旧列。

---

## 5. 后端设计

### 5.1 模块结构

```
dataagent/monitor/
  MonitorController.java        # 入口
  MonitorQueryService.java      # 会话列表/详情/单轮明细/快捷搜索
  MetricAggregateJob.java       # @Scheduled 每5分钟滚动聚合 → agentx_monitor_metric
  MonitorDiagnoseService.java   # SSE 诊断(复用 AgentDiagnoseService 范式)
  ReportScheduleService.java    # 每日 09:00 日报定时任务
  dto/                          # ConversationPageItem / RoundListItem / RoundDetail / ...
```

### 5.2 API 设计

```
GET  /monitor/overview?range=1h|24h|7d          # KPI + 趋势(查 metric 表)
GET  /monitor/conversations                     # 会话列表, 服务端分页
     ?userId=&sessionId=&conversationId=&status=&minMs=&maxMs=&start=&end=&page=&size=
GET  /monitor/search?q=                         # 快捷查询: 自动识别 ID 类型 + question 模糊
GET  /monitor/conversations/{convId}            # 会话详情: 元信息 + 轮次列表(只给瘦字段)
GET  /monitor/rounds/{traceId}?includeTools=    # 单轮明细(payload 懒加载), 复用 TraceQueryService 渲染
POST /monitor/diagnose/{convId}                 # 触发 AI 诊断, SSE 流式返回
GET  /monitor/reports?type=&page=               # 报告列表
GET  /monitor/reports/{id}                      # 报告详情(markdown)
```

- `search` 识别优先级：conversation_id 精确 → session_id 精确（经 `uk_conv_session` 1:1 反查）→ user_id → question ILIKE 模糊（最多 10 条建议）；
- 所有列表接口服务端分页；详情接口支持 `round` 参数用于深链定位；
- `POST /monitor/diagnose` 返回 SSE，事件三类：`thinking`（思考片段）/ `tool_call`（工具调用+返回摘要）/ `stage`（阶段结论），结束后落库报告并推 `report_id` 事件。

### 5.3 指标聚合任务

`@Scheduled(cron 每5分钟)`：滚动窗口对瘦表聚合 → upsert `agentx_monitor_metric`（minute=窗口起点）。指标：convs、rounds、fail_rounds、error_convs、avg_ms、p95_ms、tokens（prompt/completion 分开）。7d 查询由 API 层对分钟粒度降采样到 5 分钟点返回，前端不做重计算。

### 5.4 AI 自动分析（复用现有范式）

`AgentDiagnoseService` 已是"ReactAgent + 三个 trace 工具（全貌/单轮/首现）+ 归因四分类（模型/提示词/工具/框架）+ 结构化输出"的完整范式，直接复用：

- 诊断 Agent 构造不传 dataSource、enableTrace=false、temperature=0（不落库不写 trace，避免污染被测记录）——沿用现有约定；
- 报告落 `agentx_monitor_report`，evidence 带 `conversation_id + round + item_index` 深链；
- 日报（09:00）：聚合昨日指标 → 取 top N 失败会话逐个轻量诊断 → 归因分布 + 改进建议。

---

## 6. 前端设计（Vue3 + Element Plus + Pinia + ECharts 按需引入）

### 6.0 全局框架

```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│ ◉ Data-Agent 监控中心    [ 🔍 搜索 user_id / session_id / conversation_id …  Ctrl+K ]    │
├────────────┬─────────────────────────────────────────────────────────────────────────────┤
│ 监控        │                                                                             │
│  ▣ 总览     │                                                                             │
│            │                                                                             │
│  ▤ 会话跟踪 │                     主内容区（各页路由出口）                                │
│            │                                                                             │
│  📄 报告    │                                                                             │
│            │                                                                             │
│────────────│                                                                             │
│ « 收起侧栏  │                                                                             │
└────────────┴─────────────────────────────────────────────────────────────────────────────┘
```

① 顶栏搜索框常驻，任意页面 Ctrl+K 聚焦，回车弹命令面板（§6.3）；② 左侧导航可收起为纯图标模式；③ 本期无告警入口（D3 决策，Backlog）。

### 6.1 总览 Dashboard（`/monitor`）

```
┌─ 监控总览 ─────────────────────── [1h] [24h ●] [7d] [自定义] ─────────── 自动刷新 30s ● ─┐
│                                                                                          │
│ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐     │
│ │ 会话总数      │ │ 成功率        │ │ 轮次总数      │ │ P95 耗时     │ │ Tokens 消耗  │     │
│ │              │ │              │ │              │ │              │ │              │     │
│ │    1,284     │ │   94.2%  ▲   │ │   12,733     │ │   18.4s  ▼   │ │     8.2M     │     │
│ │  今日 +12%   │ │  昨日 91.0%  │ │  失败 312  ❗│ │  昨日 21.7s  │ │  ▸ 明细拆分   │     │
│ └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘     │
│                                                                                          │
│ ┌─ 会话 / 轮次趋势 ────────────────────────────────┐ ┌─ 失败率 & P95 耗时 ─────────────┐    │
│ │   ▂▄▆██▆▄▂▁▁▂▄▆  ██ ▆▄▂                          │ │  ▁▂▁▃▂▁▁▂▃▁ ▂ ▁▂              │    │
│ │  ─●─ 会话数   ─●─ 轮次数   ─●─ 失败轮次           │ │ ─●─ 失败率(%)  ┅●┅ P95(s)      │    │
│ │  00:00 ─── 06:00 ─── 12:00 ─── 18:00 ── 现在     │ │ ▬▬▬▬▬▬▬●▬▬▬  dataZoom          │    │
│ └──────────────────────────────────────────────────┘ └────────────────────────────────┘    │
│                                                                                          │
│ ┌─ 失败会话 TOP 10 ──────────────────────────────────────────────────────────────────┐     │
│ │ user_id  │ question 摘要            │ 状态      │ 失败轮/原因       │ 耗时   │ 发生时间  │     │
│ │ U10023   │ 统计上季度项目付款情况     │ ERROR    │ R7 · exec_sql 超时│ 45.2s │ 14:32:10 │     │
│ │ U10087   │ 生成月度经营分析报告       │ ERROR    │ R12 · JSON 解析  │ 88.1s │ 14:21:47 │     │
│ │ U10023   │ 查询华西项目合同台账       │ INTERRUPT│ R5 · 用户中断    │ 12.4s │ 13:58:02 │     │
│ └────────────────────────────────────────────────────────────────────────────────────┘     │
│                                                                                          │
│ ┌─ 进行中会话（卡死检测，仅 running） ────────────────────────────────────────────────┐     │
│ │ conv_id   │ user   │ 已运行 │ 轮次 │ 状态                     │ 操作                 │     │
│ │ 8847…c2   │ U10034 │ 11:32 │ 6    │ ⚠ 超过10分钟, 疑似卡死   │ [置为 interrupted]   │     │
│ │ 9120…ab   │ U10071 │ 00:47 │ 3    │ ● 正常                   │                      │     │
│ └────────────────────────────────────────────────────────────────────────────────────┘     │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

① 时间范围切换全局生效并同步 URL；② 图表全部查分钟级聚合表，7d 自动降采样；③ 失败会话行点击 → 直达会话详情并自动定位失败轮；④ 卡死会话提供「置为 interrupted」运维操作（需二次确认）。

### 6.2 会话跟踪 · 列表页（`/monitor/traces`）

```
┌─ 会话跟踪 ──────────────────────────────────────────────────────────────────────────────┐
│ ┌─ 筛选区 ────────────────────────────────────────────────────────────────────── [重置] ┐ │
│ │ user_id [U10023____]  session_id [____________]  conversation_id [____________]       │ │
│ │ 状态 [☐running ☑completed ☑error ☑interrupted▾]  时间 [11-17 00:00 ~ 11-18 23:59]    │ │
│ │ 总耗时 [0s ~ 120s▾]   ▸ 高级筛选（模型 / 工具名 / 最小轮数）                           │ │
│ │                                                           [ 查询 ]  [ 导出CSV ]      │ │
│ └──────────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                          │
│ 共 328 个会话，当前 1-20                                            显示列 ▾  密度 ▾  ⚙   │
│ ┌──────────────────────────────────────────────────────────────────────────────────────┐ │
│ │ user_id │ conversation_id │ question 摘要        │ 状态  │ 轮次 │ tokens │ 总耗时│ 操作 │ │
│ │ U10023  │ 8847…c2 (复制)  │ 统计上季度项目付款情况 │ ERROR │ 9    │ 45.2k │ 45.2s│[诊断]│ │
│ │ U10087  │ 9120…ab (复制)  │ 生成月度经营分析报告   │ DONE  │ 14   │ 88.1k │ 88.1s│[详情]│ │
│ │ U10023  │ 7712…f0 (复制)  │ 查询华西项目合同台账   │ DONE  │ 5    │ 12.4k │ 12.4s│      │ │
│ └──────────────────────────────────────────────────────────────────────────────────────┘ │
│                                    ‹ 1 2 3 … 17 ›                每页 [20▾] 条             │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

① 三个 ID 输入任意组合，回车即查，全部同步进 URL（`?userId=&conversationId=&status=&page=`），链接可直接分享；② 状态列彩色 tag，失败行左侧红条标识；③ **操作列每行提供两个入口（D4 决策）**：「详情」→ 会话详情页；「AI 诊断」→ 直接触发 `POST /monitor/diagnose/{convId}`，右侧滑出诊断抽屉（§6.5），完成后可在抽屉内打开报告；④ conversation_id 旁复制按钮方便贴工单；⑤ 服务端分页 + 排序，列表只查瘦表。

### 6.3 命令面板（Ctrl+K，全局悬浮）

```
                                    ┌──────────────────────────────────────────┐
                                    │ 🔍  conv_8847291c            ESC 关闭    │
                                    ├──────────────────────────────────────────┤
                                    │ 精确命中                                  │
                                    │ ▸ [会话] 统计上季度项目付款情况           │
                                    │    conversation_id: 8847291c2…  · 9 轮   │
                                    │    11-18 14:32 · ERROR · 耗时 45.2s      │
                                    │──────────────────────────────────────────│
                                    │ 模糊建议                                  │
                                    │ ▸ [用户] U10023 · 张三                   │
                                    │    近 7 天 23 个会话 · 失败 2            │
                                    │ ▸ [会话] 上季度项目付款同比分析           │
                                    │    9120…ab · 11-17 09:12 · DONE          │
                                    │──────────────────────────────────────────│
                                    │ ↵ 打开选中项   ↑↓ 选择   Tab 切换类型     │
                                    └──────────────────────────────────────────┘
```

① 输入防抖 300ms 调 `GET /monitor/search?q=`；② 后端优先精确匹配 conversation_id → session_id → user_id，命中项打类型标签；③ 未命中精确项时展示 question 模糊匹配，让人认出那次对话；④ 选中用户项 → 跳列表页并预填 userId。

### 6.4 会话详情页（`/monitor/traces/:conversationId`，三栏，D1 决策）

```
┌─ ← 返回列表   会话 8847291c2…   [复制ID] [分享链接] ──────────────────── [AI 诊断 ▸] ────┐
│ ┌─ 会话信息 ──────┐ ┌─ 轮次时间轴 (9) ──────────────────┐ ┌─ 轮次明细 ─────────────────┐ │
│ │ user            │ │ [全部●] [失败] [工具轮]   仅9轮    │ │ R7 ❌ error  第7轮          │ │
│ │  U10023 张三    │ │         全部展开 ⇅                │ │ ─────────────────────────  │ │
│ │ question        │ │                                      │ │ 状态   ❌ 超时             │ │
│ │  统计上季度项目  │ │  14:32:01                            │ │ 耗时   30.0s (P98)        │ │
│ │  付款情况       │ │  ● R1 💬 文本轮                      │ │ tokens 2.1k / 0.4k        │ │
│ │ 状态  ERROR ❌  │ │  │        842ms  1.2k tok           │ │ think  ⚙ 4.2s             │ │
│ │ 开始 14:32:01   │ │  ● R2 ⚙ query_table                 │ │ ─────────────────────────  │ │
│ │ 结束 14:32:47   │ │  │        1.2s   0.9k tok           │ │ [INPUT] [OUTPUT] [THINK]   │ │
│ │ 总耗时 45.2s    │ │  ● R3 ⚙ exec_sql                    │ │ ┌──────────────────────┐   │ │
│ │ 轮次  9 (失败1) │ │  │        0.9s   0.7k tok           │ │ │▼ messages [12]       │   │ │
│ │ tokens 45.2k    │ │  ⋮  (R4-R6 同类, 虚拟滚动省略)     │ │ │ ▼ [0] user            │   │ │
│ │                 │ │                                      │ │ │   ▼ content(3KB)    │   │ │
│ │ ── 关联报告 ──  │ │  ❌ R7 ⚙ exec_sql  ← 选中           │ │ │ ▶ [1] assistant      │   │ │
│ │ 📄 诊断报告 ▸   │ │  │        30.0s  2.1k tok           │ │ │ │   tool_calls[1] ▶   │   │ │
│ │  (11-18 14:35)  │ │     错误: statement timeout         │ │ │ ▶ [2] tool(查询结果)  │   │ │
│ │                 │ │                                      │ │ │   content 8.2KB ▶   │   │ │
│ │                 │ │  ● R8 💬 文本轮                      │ │ │ ⋮ (默认只展开2层)      │   │ │
│ │                 │ │  │        3.1s   1.8k tok           │ │ └──────────────────────┘   │ │
│ │                 │ │                                      │ │ 8.2KB/214KB 已加载        │ │
│ │                 │ │ ● R9 💬 文本轮 · 终答               │ │ [加载完整明细…]            │ │
│ │                 │ │          2.4s   2.0k tok             │ │                            │ │
│ └─────────────────┘ └──────────────────────────────────────┘ └────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

① 中栏时间轴纵向虚拟滚动（长会话 200+ 轮无压力），节点图标区分 文本💬/工具⚙/失败❌，失败轮可筛选置顶；② 点击轮次 → 右栏懒加载该轮 payload 明细（`GET /monitor/rounds/{traceId}`）；③ JSON 默认树状、只展开 2 层，>100KB 字段先显示摘要（input_chars 由瘦表带回），点「加载完整明细」才拉全文；④ THINK tab 用 markdown-it 渲染（已有依赖）；⑤ 左栏「关联报告」显示该会话已生成的诊断报告，点击进报告详情（M5）；⑥「AI 诊断」→ 右侧抽屉（§6.5）；⑦ 分享链接 = `?conversationId=&round=7`，打开自动定位并高亮该轮；⑧ DIFF 视图与原始消息链视图本期不做（D2 决策，Backlog B1/B2）。

### 6.5 AI 诊断抽屉（详情页/列表页触发，右侧滑出，SSE 流式）

```
                                              ┌─ AI 诊断 · conv 8847291c2… ───── [×] ─┐
                                              │ ● 诊断中 · 已用 23s · 已调用 3 个工具  │
                                              │──────────────────────────────────────│
                                              │ 🧠 思考                              │
                                              │ ┌────────────────────────────────┐   │
                                              │ │ 先看全貌确认失败轮，再取该轮     │   │
                                              │ │ 入参出参，核对是否 SQL 本身…    │   │
                                              │ └────────────────────────────────┘   │
                                              │ ▸ 🛠 trace_overview(conv=8847…) ✅0.8s│
                                              │   └ 9轮 · 失败于 R7 · R2起进入工具段 │
                                              │ ▸ 🛠 trace_round(round=7)      ✅1.2s│
                                              │   └ input 214KB · tool_calls[1]…    │
                                              │ ▸ 🛠 trace_first_appear("timeout")   │
                                              │   ⟳ 运行中…                         │
                                              │──────────────────────────────────────│
                                              │ ▼ 阶段结论（实时更新）                │
                                              │ 归因: 工具问题(置信度 高)            │
                                              │ R7 exec_sql 生成全表扫描, 30s 超时…  │
                                              │──────────────────────────────────────│
                                              │ [ 诊断完成后生成报告并保存 ]          │
                                              └──────────────────────────────────────┘
```

① SSE 推送三类事件：`thinking` / `tool_call` / `stage`，抽屉内滚动自动跟随；② 诊断 Agent 复用 `AgentDiagnoseService` 约定（不落库、不写 trace、temperature=0）；③ 完成后报告落 `agentx_monitor_report`，结论中的证据带 `conversation_id+round` 深链按钮，点击关抽屉并高亮对应轮次。

### 6.6 报告中心（`/monitor/reports`）

```
┌─ 报告中心 ───────────────────────────── [全部●] [单会话诊断] [日报] ─────────────────────┐
│ ┌────────────────────────────────────────────────────────────────────────────────────┐   │
│ │ 类型   │ 范围/周期        │ 生成 │ 状态   │ 摘要                       │ 时间        │   │
│ │ 🔍诊断 │ conv 8847…c2     │ 手动 │ ✅完成 │ 工具问题: exec_sql 全表…   │ 11-18 14:35│   │
│ │ 📅日报 │ 11-17            │ 自动 │ ✅完成 │ 成功率91.2%, 归因: 工具…   │ 11-18 09:00│   │
│ │ 🔍诊断 │ conv 9120…ab     │ 手动 │ ✅完成 │ 模型能力: 多表关联遗漏…    │ 11-17 16:20│   │
│ └────────────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                          │
│ 报告详情（点开后）:                                                                       │
│ ┌─ 🔍 单会话诊断报告 · conv 8847291c2… · 14:35 ──────────────────── [导出] [复制链接] ─┐  │
│ │ ## 结论摘要                                                                          │  │
│ │ 归因: 【工具问题】(置信度: 高)   影响轮: R7                                           │  │
│ │ exec_sql 生成的 SQL 对 payment 表全表扫描, 30s 超时; R2 拿到的表统计未走入索引…       │  │
│ │ ## 证据链                                                                           │  │
│ │ 1. R7 tool_calls: SELECT * FROM payment WHERE …   [查看 R7 原文 ↗]  ← 深链定位       │  │
│ │ 2. R2 query_table 返回表统计: rows≈2.1e7           [查看 R2 原文 ↗]                  │  │
│ │ 3. 首现 "timeout" 于 item_index=17                 [查看消息链 ↗]                    │  │
│ │ ## 建议                                                                             │  │
│ │ ① exec_sql 工具提示词中强制要求携带 LIMIT/分页  ② payment 统计信息预注入 schema      │  │
│ └────────────────────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

① 报告 markdown 渲染（复用 markdown-it），证据深链点击 → 打开会话详情并高亮对应轮；② 日报由 `ReportScheduleService` 每日 09:00 生成，打「自动」标；③ 订阅推送为 Backlog（B4）。

### 6.7 大数据量渲染策略汇总

| 场景 | 策略 |
|---|---|
| 轮次列表 | 只返回瘦字段（round/type/tokens/duration/success），明细点开才调 `GET /rounds/{traceId}` |
| 轮次时间轴 | `useVirtualList` 虚拟滚动（vueuse），只渲染可视区 |
| JSON 查看（input/output） | 懒解析 + 默认只展开 2 层；>100KB 先显示摘要（input_chars）+「加载完整明细」确认 |
| think 内容 | markdown-it 渲染，长文折叠 |
| 趋势图表 | 查分钟粒度 metric 表；7d 视图服务端降采样到 5 分钟粒度 + dataZoom |
| 实时刷新 | Dashboard 30s 轮询（一期），不引入 WebSocket；进行中会话详情手动刷新 |

---

## 7. 里程碑与任务清单

### M1 数据层改造

- [ ] **T1.1** 创建 `agentx_trace_payload` 表（DDL §4.3）
- [ ] **T1.2** `agentx_trace` 加列 round_type/tool_names/input_chars + 三个索引（§4.2）
- [ ] **T1.3** `TraceManager` 改造：同事务双写瘦表+明细表，写入时提取 round_type/tool_names/input_chars
- [ ] **T1.4** 历史数据回刷脚本（payload 回填 + 新列补算）
- [ ] **T1.5** `TraceQueryService.renderRound()` 切换为 join payload，TraceQueryServiceTest 回归通过
- [ ] **T1.6** 稳定后停写旧大字段并置 NULL 回收空间（上线一周后执行）

### M2 后端查询服务与 API

- [ ] **T2.1** `MonitorQueryService` 会话列表查询（三 ID 组合筛选 + status/时间/耗时，服务端分页）
- [ ] **T2.2** 会话详情 API（元信息 + 轮次瘦字段列表，支持 round 深链参数）
- [ ] **T2.3** 单轮明细 API（payload 懒加载，includeTools 参数契约对齐 TraceQueryService）
- [ ] **T2.4** `GET /monitor/search` 快捷查询（conversation_id → session_id → user_id → question 模糊）
- [ ] **T2.5** `agentx_monitor_metric` 表 + `MetricAggregateJob` 每 5 分钟聚合任务 + `GET /monitor/overview`（7d 降采样）
- [ ] **T2.6** 卡死会话查询 + 「置为 interrupted」运维接口（二次确认由前端承担）

### M3 前端监控框架与列表

- [ ] **T3.1** `/monitor` 路由组 + 左侧导航布局（总览/会话跟踪/报告）+ Ctrl+K 全局快捷键注册
- [ ] **T3.2** axios API 封装 + pinia store（筛选状态与 URL 双向同步）
- [ ] **T3.3** 会话列表页（筛选区/表格/分页/操作列：诊断+详情/复制 ID/URL 同步/导出 CSV）
- [ ] **T3.4** 命令面板组件（防抖 300ms、类型识别标签、模糊建议、键盘导航）
- [ ] **T3.5** Dashboard（KPI 卡 5 个、双趋势图 ECharts 按需引入、失败 TOP、进行中会话 + 置为 interrupted 确认框、30s 自动刷新）

### M4 会话详情页

- [ ] **T4.1** 三栏布局骨架 + 会话信息栏（元信息/统计/关联报告区占位）
- [ ] **T4.2** 轮次时间轴（useVirtualList 虚拟滚动、类型图标、失败筛选、URL round 定位高亮）
- [ ] **T4.3** 轮次明细面板（INPUT/OUTPUT/THINK 三 Tab、懒加载、JSON 树默认 2 层、>100KB 摘要+确认加载）
- [ ] **T4.4** think markdown 渲染 + 长文折叠
- [ ] **T4.5** 复制 ID / 分享链接（`?conversationId=&round=` 深链回放定位）

### M5 AI 诊断与报告

- [ ] **T5.1** `MonitorDiagnoseService`（复用 AgentDiagnoseService 范式与约定：不落库/enableTrace=false/temperature=0）
- [ ] **T5.2** `POST /monitor/diagnose/{convId}` SSE 接口（thinking/tool_call/stage/report_id 四类事件）
- [ ] **T5.3** 诊断抽屉组件（SSE 流式渲染、滚动跟随、证据深链点击定位轮次）
- [ ] **T5.4** 列表页每行「AI 诊断」入口（D4：不进详情页直接触发抽屉）
- [ ] **T5.5** `agentx_monitor_report` 表 + 诊断完成自动落库 + 详情页左栏「关联报告」联动
- [ ] **T5.6** 报告中心页（列表筛选 + markdown 详情 + 证据深链 + 导出/复制链接）
- [ ] **T5.7** `ReportScheduleService` 每日 09:00 日报（top N 失败会话轻量诊断 + 归因分布；可独立裁剪）

### Backlog（本期不做，已决策 D2/D3）

- **B1** 轮次 DIFF 视图：R(n) 与 R(n+1) input 逐消息对比，高亮新增/变化消息，定位上下文污染起点
- **B2** 原始消息链视图：按 `agentx_session` state_key 分组、item_index 切片对照（真相链 vs 工作视图）
- **B3** 告警预警：`agentx_alert_rule` / `agentx_alert_event` 表、规则评估引擎、告警中心页面、站内角标
- **B4** 通知渠道：邮件 / webhook / RabbitMQ 路由、日报订阅推送
- **B5** action 审计视图（`agentx_action_log` 接入会话详情页）
- **B6** 全链路文本检索（PG tsvector / ES 派生索引），当前用"定位到轮 + ILIKE"够用

---

## 8. 决策记录

| 日期 | 决策 | 说明 |
|---|---|---|
| 2026-11-18 | D1 三栏布局 | 会话信息/轮次时间轴/轮次明细 |
| 2026-11-18 | D2 DIFF/原始链视图延后 | 排障增强视图列入 Backlog B1/B2 |
| 2026-11-18 | D3 告警预警延后 | 规则引擎/告警中心/通知整体列入 Backlog B3/B4，Dashboard 仅保留监控展示 |
| 2026-11-18 | D4 AI 诊断双入口 | 详情页右上角 + 列表页每行操作列 |
| 2026-11-18 | 拆表方向 | `agentx_trace` 瘦表化 + `agentx_trace_payload` 明细表，不拆消息级明细；user_id 不冗余进 trace 表（经 conversation join） |
| 2026-11-18 | 实时性 | 一期 30s 轮询，不引入 WebSocket |
