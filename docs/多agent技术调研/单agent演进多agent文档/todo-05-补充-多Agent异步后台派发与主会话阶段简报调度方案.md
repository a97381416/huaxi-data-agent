# todo-06 · 多 Agent 异步后台派发与主会话阶段简报调度方案（路线 B）

> 状态：`TODO`（待独立迭代实施）  
> 前置依赖：`done-02`（主路由与垂类子 Agent）、`M-SA1~M-SA5`（同步子 Agent 底座）、`todo-05`（多 Agent 前端流式界面展示改造，前端 UI 已按异步交错模型预留渲染能力）

---

## 1. 背景与目标

### 1.1 现状局限（同步阻塞模式）
当前 `mini-agentx-core` 中 `SubAgentTool.call()` 采用**同轮同步等待**机制：
- 主 Agent 在某一轮通过 `ToolCallExecutor.executeAsync()` 并发派发 1~N 个子 Agent（如 `call_data_analysis` + `call_reviewer_workflow`）；
- `SubAgentTool.call()` 内部使用 `CountDownLatch.await(timeoutMs)` 阻塞当前工具线程，直到子 Agent 运行结束；
- `AgentLoopExecutor` 必须等待本轮**全部工具（含所有子 Agent）全部返回**后，才把结果回灌给主 LLM 进入下一轮思考。

**痛点**：当子 Agent 需要执行多轮复杂 SQL / 报表 / 审阅（耗时 30s~180s）时，主 Agent 在等待期间完全静默，无法先行开展轻量核对或向用户输出「阶段性简报」，用户只能干等。

### 1.2 目标体验（对应 `todo-05` 场景 10）
实现主 Agent 与子 Agent 的**异步后台协作闭环**：
1. **非阻塞派发**：主 Agent 派发子 Agent 后立即拿回 `taskId`（而非阻塞等 180s），继续进入下一轮；
2. **等待期快扫与阶段简报**：主 Agent 在子任务后台运行期间，可同步执行轻量工具（如查上下文文件、查目录、核对规则），先向用户输出结构化的「阶段性简报」（说明已确认事实 + 哪些子专员仍在后台跑）；
3. **完成唤醒与交错汇报**：某个子 Agent 跑完后，结果自动注入主循环事件队列；主 Agent 收到通知后先做阶段归档（可选），待全部后台子 Agent 完成后自动汇总产出最终答复；
4. **父子级联终止**：用户点击停止 / 超时 / 新消息顶掉时，所有仍在后台运行的子 Agent 必须 100% 级联终止，严禁残留孤儿线程。

---

## 2. 核心架构设计

### 2.1 派发模型升级：从同步工具到“异步任务托管”

在 `mini-agentx-core` 中新增 **后台子任务托管器 `SubAgentTaskHub`**（按 `rootConvId` 维度管理），与现有 `SubAgentTool` 兼容共存：

```text
主 Agent (AgentLoopExecutor)
   │
   ├─[1] 调用 call_data_analysis(..., async=true)  ──► SubAgentTaskHub.spawn()
   │       立即返回 {"taskId":"st_01", "status":"RUNNING", "agentCode":"data-analysis"}
   │       后台线程池独立运行 subAgent.stream(...)，实时向父 sink 透传 (agentCode, parentToolCallId)
   │
   ├─[2] 主 Agent 同一会话继续下一轮：
   │       调用轻量工具（如查文件/查规则）→ 输出 TEXT（阶段性简报）
   │       调用 wait_sub_agents(taskIds=["st_01","st_02"]) 或主循环检测仍有活跃后台任务自动进入 WAITING_SUBAGENTS 态
   │
   ├─[3] 后台子任务陆续完成：
   │       SubAgentTaskHub 触发回调 → 发射 SUB_AGENT_DONE 事件到父 sink
   │       → 唤醒主 AgentLoopExecutor，将子结果作为系统/工具消息回灌主上下文
   │
   └─[4] 全部后台子任务收敛 → 主 Agent 产出最终汇总答案 → COMPLETE（全流唯一终态）
```

### 2.2 关键组件改动清单

| 模块 | 文件 / 类 | 改动说明 |
| --- | --- | --- |
| `mini-agentx-core` | `SubAgentTaskHub`（新增） | 管理每个 `rootConvId` 下的异步子任务表（`taskId -> Future / Disposable / status / result / elapsedMs`），提供 `spawn()`、`awaitAny()`、`awaitAll()`、`cancelAll(rootConvId)` |
| `mini-agentx-core` | `SubAgentTool` | 增加 `asyncMode` 配置（默认 `false` 保持向后兼容）；`asyncMode=true` 时立刻提交 `SubAgentTaskHub` 并返回结构化 `RUNNING` JSON，不阻塞 `CountDownLatch` |
| `mini-agentx-core` | `AgentLoopExecutor` | 1. 终态守卫：主 LLM 本轮无 toolCalls 时，若 `SubAgentTaskHub.hasRunning(rootConvId)` 为真，**不发射 `COMPLETE`**，而是进入挂起等待态（向前端发射 `WAITING_SUBAGENTS` 状态事件）；<br>2. 唤醒重入：任意/全部子任务完成时，将结果拼装为系统补充消息注入 `workingMessages`，再次 `scheduleRound(false)`；<br>3. 级联中断：`interrupt()` / `stopStream()` 时同步调用 `SubAgentTaskHub.cancelAll(rootConvId)` |
| `mini-agentx-core` | `AgentStreamEvent` | 新增事件类型：`SUB_AGENT_SPAWNED`、`SUB_AGENT_COMPLETED`、`WAITING_SUBAGENTS`（均携带 `agentCode`、`parentToolCallId`、`durationMs`） |
| `data-agent-server` | `DodoAgent` / `SubAgentRegistry` | 通过配置项 `data-agent.subagent.async-dispatch: false`（默认关闭，灰度开启）控制是否开启异步派发，并在主路由 System Prompt 中补充“长耗时子任务派发后可先做轻量核对并给用户阶段简报”的引导策略 |
| `data-agent-server` | `SessionService` | 历史回放对齐：主消息中若在子任务运行期间夹杂了阶段简报文本与普通工具，`buildTimeline()` 按真实时间戳顺序保序还原，与前端 `ChatView.vue` 已预留的异步交错渲染无缝衔接 |

---

## 3. 边界与风控设计

1. **终态唯一性铁律**：无论有多少个异步子 Agent、哪个先完哪个后完，父 SSE 流在整个提问周期内**严格只发射 1 次 `COMPLETE` 或 `PAUSED`**。
2. **防无限等待 / 死锁**：
   - 每个异步子任务仍受 `timeoutMs`（默认 180s）硬超时保护；超时后 `SubAgentTaskHub` 自动 `dispose` 并注入超时错误结果，触发主循环唤醒收尾；
   - 主循环增加整体最大等待兜底（如 240s），防止异常场景下 SSE 连接永久悬挂。
3. **同 `subConvId` 互斥**：同一会话内若主 Agent 异步连发两次同一个垂类专员（例如连发两次 `data-analysis`），第二个任务进入该 `subConvId` 的队列排队执行，严禁并发写同一份 `working_messages`。
4. **中断与断点恢复**：
   - 用户点击停止按钮 → `SubAgentTaskHub.cancelAll()` 立即级联暂停所有运行中子任务，并落子快照；
   - 若用户发新消息顶掉旧流（`takeOver`）→ 清理旧 `SubAgentTaskHub` 中所有未完成任务后再开启新流。
5. **前端零改动承接**：
   - 路线 A（`todo-05` 前端 UI 改造）已经按异步交错模型设计了主会话渲染逻辑：当 timeline 中出现“运行中的子 Agent 卡片 + 主 Agent 后续工具/阶段简报文本”时，卡片保持后台运行态、底部自动展示“等待后台专员返回（已完/总数）”提示条，等子 Agent `TOOL_END` / `SUB_AGENT_COMPLETED` 到达后自动切换为完成态并接续最终答案。

---

## 4. 分阶段实施与测试计划

| 阶段 | 任务 | 验收标准 |
| --- | --- | --- |
| **B-1** | `SubAgentTaskHub` + `SubAgentTool` 异步模式开关 | 单测覆盖：并发派发 2 个异步子任务立即返回、同 `subConvId` 串行排队、单子超时自动唤醒、父停全子必停 |
| **B-2** | `AgentLoopExecutor` 挂起等待与结果唤醒回灌 | 集成测试：主 Agent 派发慢子任务后先输出阶段简报，流不提前 `COMPLETE`；子任务完成后主 Agent 自动产出最终汇总且 `COMPLETE` 仅发射 1 次 |
| **B-3** | 主路由 Prompt 调优 + 灰度开关 + 历史回放保序 | `/test/mSA` 新增场景 10 真实端到端自动化用例；刷新页面后历史回放与实时流式展示 100% 一致 |
