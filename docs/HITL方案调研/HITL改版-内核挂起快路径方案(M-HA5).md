# HITL 改版：内核挂起快路径方案（M-HA5）

> **状态**：🟡 **TODO / 待启动**（本文档为改版设计稿，一期 M-HA0~M-HA4 不受本方案影响）
>
> **定位**：本文档承接 [HITL人工决策与审批技术方案](file:///d:/work/zrkj-huaxi/code/huaxi-data-agent/docs/HITL人工决策与审批技术方案.md)，把 HITL 的**快路径执行模型**从「fresh 重开（每批一次审重进一轮 LLM）」改造为 **Agnes Harness 式「内核挂起 + 原地唤醒（审批零 LLM 开销）」**。
>
> **上游参考**：[Agnes Harness 用户确认框与 HITL 机制分析](file:///d:/work/agnes-harness-manage/docs/03-用户确认框与HITL机制分析.md) §3（快慢双路径）、[多智能体中断与恢复机制设计方案](file:///d:/work/agnes-harness-manage/docs/01-多智能体中断与恢复机制设计方案.md)。
>
> **一句话总纲**：**审批是"人在回路"，不是"模型在思考"——人的签字不该再让模型重新想一遍。**

---

## 0. TODO 清单（开工前必须先拍板的事项）

> 下列每一项都会影响架构选型，**未拍板不进入编码**。

- [ ] **T-1 前置依赖**：是否先完成 [04-harness进阶-执行内核拦截器化与插件注册化改造方案](file:///d:/work/zrkj-huaxi/code/huaxi-data-agent/docs/多agent技术调研/单agent演进多agent文档/04-harness进阶-执行内核拦截器化与插件注册化改造方案.md) 的 `RoundInterceptor` 改造？
      → **建议：先做拦截器化**。审批挂起天然是一个 `ToolInterceptor`（"调用前需人工放行"），有拦截器体系后可零改动主循环；否则本方案要硬改 `AgentLoopExecutor` 主流程（1244 行，风险高）。
- [ ] **T-2 在线等待超时时长**：工具线程等待审批的默认上限（建议 **60s**，与前端授权卡 TTL 5 分钟解耦——5 分钟是"单据有效期"，60s 是"本次在线等待"）。
- [ ] **T-3 跨实例唤醒方案**（详见 §5.4 三选一）：
      - 方案 a：**会话粘滞**（负载均衡按 `conversationId` 路由）——最简单，但要求 nginx/网关支持一致性哈希；
      - 方案 b：**DB 状态轮询兜底**（内存 future 唤醒 + 2s 轮询决策单表）——零基础设施要求，延迟 ≤2s，**推荐**；
      - 方案 c：**Redis Pub/Sub 广播**——延迟最低，但引入新中间件依赖与多实例一致性负担。
- [ ] **T-4 前端协议分流**：decide 接口返回 **SSE 流**（一期 fresh 模式）还是 **202 Accepted**（M-HA5 原地唤醒模式）？前端需同时兼容两种（详见 §5.5）。
- [ ] **T-5 快照字段扩展**：`PauseState` 是否新增 `decisionRef`（挂起时的决策单号）？—— 建议新增（否则恢复时无法定位是哪个审批在等）。
- [ ] **T-6 工具改造形态**：工具"等待审批"是**工具内部 await**（本文推荐）还是**内核调用前拦截**？—— 推荐工具内部 await（我们的工具已有 planRound/executeRound 二段式结构，天然契合）。
- [ ] **T-7 灰度策略**：`data-agent.hitl.inline-enabled` 按**工具粒度**还是**会话粒度**灰度？—— 建议按工具粒度（先提审，再审批，最后驳回/重提）。
- [ ] **T-8 监控指标**：审批等待时长分布、唤醒成功率、快照挂起率、resume 成功率——接入现有监控中心（`agentx_monitor_*`）。

---

## 1. 为什么要改（一期 fresh 模式的成本账）

### 1.1 一期（fresh 重开）的实际链路

```text
 第 1 轮（出计划）                    第 2 轮（确认后重开）
┌──────────────────────┐          ┌──────────────────────────────────────┐
│ LLM 推理              │          │ 加载历史链（agentx_session）         │
│ → 调高危工具          │          │ → LLM 推理（重新理解上下文）           │
│ → 工具返回计划文本     │          │ → LLM 决定路由到哪个子 Agent           │
│ → 轮次 COMPLETE       │  用户点   │ → 子 Agent 从历史链找回 ct_ 令牌      │
│ （无挂起协程）         │ ──────→  │ → 工具二轮消费令牌 → 真正执行          │
└──────────────────────┘  [批准]   └──────────────────────────────────────┘
                                        ↑
                                 ① 重进 LLM（token 成本）
                                 ② LLM 可能失真（不调用工具 / 路由错）
                                 ③ 额外 1~3 秒延迟
```

### 1.2 成本随工具数量线性堆积

| 场景 | 审批次数 | 一期额外 LLM 轮次 | 额外延迟 | 风险 |
| :--- | :--- | :--- | :--- | :--- |
| 单次提审（今天） | 1 | 1 | +1~3s | 可接受 |
| 批量审批 10 个任务 | 10 | 10 | +10~30s | **明显劣化** |
| 子 Agent 编排 3 个高危动作 | 3 | 3 | +3~9s | 劣化 |
| 工具数扩张到 20+（规划中） | N | N | **线性堆积** | **不可持续** |

**结论**：一期 fresh 模式是"够用且安全"的过渡态，但**不是终态**。工具变多后，每一次人工签字都要让模型重新"读一遍上下文、想一遍该干什么"——**这笔开销既不产生价值，也让 LLM 成为审批链路上不必要的单点**（它可能失真、可能超时、可能路由错）。Agnes 的答案是让审批结果**绕过 LLM**直接驱动工具。

### 1.3 两种模式的能力对比

| 维度 | 一期 fresh 重开 | M-HA5 内核挂起（目标） |
| :--- | :--- | :--- |
| 审批后到执行 | 1~3s（重进 LLM） | **<100ms**（原地唤醒） |
| LLM 开销 | 每批一次审 = 一轮 LLM | **0**（审批不过 LLM） |
| LLM 失真风险 | 有（靠提示词+哈希兜底） | **无**（不经过模型） |
| 工具编排连续性 | 断（重新规划） | **连续**（同一 Turn 继续） |
| 挂起协程风险 | 无 | 有（需挂起/唤醒/超时三件套管理） |
| 刷新/重启 | 天然免疫 | 需快照兜底（复用现有 pause_state） |
| 实施风险 | — | **高**（动内核主循环） |

**取舍结论**：终态选 M-HA5（性能与正确性双赢），但必须配套超时降级与快照兜底，且**不能在没有回滚开关的情况下上线**。

---

## 2. Agnes 快路径原理回顾（我们要等价实现什么）

### 2.1 Agnes 的双路径

```text
┌─────────────── Agnes 快路径（在线）────────────────────────────┐
│ 工具执行 ──→ requestToolDecision()（决策单落盘）                 │
│          ──→ deliverDecision(): Promise.race([                │
│                 在线投递 await,   ← 用户在线点击，毫秒级返回     │
│                 账本状态 wait ])                                │
│          ──→ 决策落盘 → 唤醒原协程 → 工具从挂起点继续执行        │
│          ──→ 同一 Turn 内继续跑后续工具                         │
└───────────────────────────────────────────────────────────────┘
┌─────────────── Agnes 慢路径（离线/超时）───────────────────────┐
│ 决策单落盘 → 无在线客户端/超时 → 返回 park 事件                   │
│          ──→ 当前 Turn 以 parked 结束（释放内存与连接）          │
│          ──→ 用户后来审批 → continueParked（校验策略指纹）      │
│          ──→ 开 trigger='approval-resume' 的新 Turn 续跑         │
└───────────────────────────────────────────────────────────────┘
```

**核心特征**：审批**发生在工具执行过程当中**，审批通过则**从挂起点原地继续**——上下文零损耗、后续工具编排不断。

### 2.2 为什么 Java/Reactor 侧不能照抄协程

Agnes 是 TypeScript，`Promise` 挂起是**语言原生能力**（挂起不占线程、await 唤醒零成本）。我们是 Java + Reactor（`AgentLoopExecutor` 的工具执行是 `executor.execute(...)` 每工具一线程的**阻塞式** `callback.call()`）。

三种可落地的等价实现：

| 方案 | 机制 | 线程占用 | 跨重启 | 复杂度 | 评价 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **A. 工具线程原地 await**（推荐） | 工具线程 `future.get(timeout)` 阻塞等待，完成后原地继续 | 占 1 线程 ≤60s | 不可（需快照） | 中 | **在线体验等同 agnes**（唤醒后立即继续），超时有兜底 |
| B. 内核调用前拦截 | 工具声明 needsApproval，内核在调用前拦 | 不占（返回等待标记） | 需快照 | 中高 | 工具的"计划构造"逻辑在工具内部，拦截点难放 |
| C. 内核挂起 + 新 Turn 续跑（= 一期 fresh） | 收尾重开 | 不占 | 天然免疫 | 低 | 一期现状，性能差 |

**选定 A**：我们的工具已经是 `planRound`（出计划）/ `executeRound`（真执行）两段式结构，**planRound 里"等审批结果"是天然的位置**——现在 planRound 是"把计划返回给 LLM 让它收尾"，改后是"等审批结果，拿到就原地进 executeRound"，**同一工具调用内完成**。

### 2.3 选定方案 A 的关键前提（红线）

> [!WARNING]
> **await 之前，工具不得产生任何副作用。** 这是 M-HA5 的第一铁律。
>
> 现有 W2 工具的 `planRound` 只做：权限校验 → 状态守卫 → 预检 → 签发令牌 → 构造计划快照 → 落审计（只写 `agentx_action_log`，无业务副作用）→ **返回计划**。这满足"await 前无副作用"✅。
>
> 若将来某个工具在 planRound 里就写了业务数据再 await，则**挂起超时后该副作用无法回滚**，会与 M-HA5 的快照重放语义冲突。**代码评审红线：await 之前只允许读操作与审计写入。**

---

## 3. 目标架构

### 3.1 总体结构

```text
┌─────────────────────────────────────────────────────────────────────┐
│  工具线程（toolPool, 每工具一线程）                                   │
│    planRound:  权限→守卫→预检→issue(决策单)→ 落库                   │
│                 │                                                     │
│                 ▼  ← ① DecisionAwaitRegistry.await(token, timeout)     │
│        ┌──────────────────────────┐                                  │
│        │  等待审批（原地阻塞）       │  ◄── ② DecisionController      │
│        │  ├ 内存 future 被 complete │      完成审批 → future.complete │
│        │  └ 或 2s 轮询决策单表发现已决 │      （跨实例兜底，§5.4）     │
│        └──────────────────────────┘                                  │
│                 │                                                     │
│      ┌──────────┴──────────┐                                          │
│      ▼                     ▼                                          │
│  【命中】approve       【超时/未决】                                   │
│  原地 executeRound     返回 __await_decision__ 标记                    │
│  （真正执行写操作）      ↓                                             │
│  返回正常工具结果        内核截获 → 写 agentx_pause_state              │
│  → LLM 下一轮继续       (Reason=HITL_TOOL_REQUEST)                   │
│                        → 发 PAUSED(hitl) → 轮次收尾（释放线程）        │
│                        ↓                                             │
│                 用户后来审批 → decide()                               │
│                        ↓                                             │
│                 registry miss → 走 resumeStream 恢复路径               │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2 在线快路径时序图（M-HA5 目标形态）

```text
════ M-HA5 在线快路径：审批零 LLM 开销 ════

 用户        前端          DecisionController    Registry      工具线程        主后端        LLM
  │           │                  │                 │             │              │           │
  │ "提交审核" │                  │                 │             │              │           │
  ├──────────→│ POST /agent/stream                  │             │              │           │
  │           │─────────────────────────────────────→│ toolCall   │              │           │
  │           │                  │                 │  planRound  │              │           │
  │           │                  │                 │  权限/守卫   │              │           │
  │           │                  │                 │  issue(ct)  │              │           │
  │           │                  │                 │  落库 pending│              │           │
  │           │                  │                 │  register(future)         │           │
  │           │                  │                 │  await(60s)⏸──────────┐   │           │
  │           │◄══ DECISION 事件（卡片渲染）═════════════════════════════╪═══│           │
  │◄══ 卡片（带按钮）═══│                  │                 │      挂起中 │           │
  │           │                  │                 │             │      │    │           │
  │ [批准]────→│ POST /decision/decide   │                 │             │      │    │           │
  │           │─────────────────→│ CAS→approved    │             │      │    │           │
  │           │                  │──────────────→│ complete()───┼─────→│    │           │
  │           │                  │                 │             │  醒来！│    │           │
  │           │                  │                 │             │  executeRound  │           │
  │           │                  │                 │             │  verifyAndConsume ✓│
  │           │                  │                 │             │──── POST 提审 ────→│
  │           │                  │                 │             │←─── 成功 ──────────│
  │           │                  │                 │             │  返回工具结果      │
  │           │◄══ TOOL_END / TEXT ════════════════════════════════════════════════════│
  │◄══ "✅ 提交审核成功" ═══════════════════════════════════════════════════════════════╤
  │           │                  │                 │             │              │  下一轮继续
  │           │                  │                 │             │              │  （审批环节
  │           │                  │                 │             │              │   从未经过
  │           │                  │                 │             │              │   LLM）
```

**关键差异点**：第 12 行的 `complete()` 唤醒的是**工具线程**，工具在**同一次 tool call 内**继续执行并返回最终结果；LLM 拿到的是"执行成功了"这一普通工具结果，**对中间的人工审批环节完全无感**——这正是 agnes 快路径的语义。

### 3.3 慢路径时序图（超时/离线 → 快照挂起 → 后来恢复）

```text
════ M-HA5 慢路径：60s 超时 → 快照挂起 → 后来恢复 ════

 工具线程                     内核                        DB                 用户/前端
    │                          │                          │                     │
    │ await(60s) 超时          │                          │                     │
    │ 返回 __await_decision__  │                          │                     │
    ├─────────────────────────→│ 识别标记                   │                     │
    │                          │ 构建快照                  │                     │
    │                          │ (pendingToolCalls+decisionRef)                  │
    │                          │─────────────────────────→│ INSERT pause_state  │
    │                          │ 发 PAUSED(hitl) 事件       │                     │
    │◄─────────────────────────┤ 轮次收尾（线程释放）      │                     │
    │                          │                          │                     │
    │                          │                          │   …用户 2 小时后回来… │
    │                          │                          │◄────────────────────┤
    │                          │                          │  卡片（回放重建，仍可点）│
    │                          │                          │                     │
    │                          │                          │◄──── [批准] ────────┤
    │                          │                          │  CAS→approved        │
    │                          │                          │                     │
    │                          │ 恢复：resumeStream       │                     │
    │                          │ ① 查决策单状态（已 approved + 答案）             │
    │                          │ ② 快照重放：pendingToolCalls 本地重放           │
    │                          │ ③ ctx 注入 decisionAnswer（工具读到即 executeRound）│
    │◄──── 重放调用（含已决答案）─┤                          │                     │
    │ executeRound（带答案）    │                          │                     │
    ├──── POST 提审（真正执行）─────────────────────────────────────→ 主后端   │
    │                          │  终态落库 consumed         │                     │
```

**复用清单（关键：M-HA5 不需要新造轮子）**：

| 需要的能力 | 现有基建 | 缺口 |
| :--- | :--- | :--- |
| 挂起快照 | `agentx_pause_state`（`JdbcPauseStateStore`，TTL 7 天） | 需支持 `Reason=HITL_TOOL_REQUEST`（**枚举已预留**）+ 新增 `decisionRef` 字段 |
| 挂起事件 | `AgentStreamEvent.PAUSED`（注释已写"HITL 或用户中断"） | 需透传 `pauseReason=hitr` 并让前端渲染"等待人工审批"态 |
| 恢复执行 | `ReactAgent.resumeStream`（空 query = pending 工具本地真实重放） | 需在恢复时**注入审批答案**到 ctx |
| 中断安全点 | `InterruptContext`（`enterToolExecution` SafePoint） | 复用，无需改动 |
| 会话状态 | `agentx_conversation.status`（running/completed/interrupted） | 复用 `interrupted` 态即可（`resume` 路径已认） |

---

## 4. 核心组件设计

### 4.1 `DecisionAwaitRegistry`（进程内唤醒注册表）

```java
/**
 * HITL 在线唤醒注册表（M-HA5）：token → 等待中的工具线程。
 * 仅在线快路径使用；进程重启后丢失 → 降级为慢路径（快照重放），不影响正确性。
 */
@Component
public class DecisionAwaitRegistry {

    /** 等待中的审批：内存 future + DB 轮询兜底（跨实例） */
    public record PendingDecision(
            String token,
            CompletableFuture<DecisionAnswer> future,
            long createdAtMillis
    ) { }

    private final Map<String, PendingDecision> pending = new ConcurrentHashMap<>();

    /** 工具线程调用：注册等待并阻塞至多 timeoutMillis */
    public DecisionAnswer await(String token, long timeoutMillis) { ... }

    /** DecisionController 调用：命中在线等待者则立即唤醒 */
    public boolean complete(String token, DecisionAnswer answer) { ... }
}
```

**关键设计：`await` 的实现必须是"内存 future + DB 轮询"双通道**（解决跨实例，见 §5.4）：

```java
public DecisionAnswer await(String token, long timeoutMillis) {
    CompletableFuture<DecisionAnswer> f = new CompletableFuture<>();
    pending.put(token, new PendingDecision(token, f, now()));
    try {
        // 分段等待：每段 2s，每段结束查一次决策单表（跨实例/进程重启兜底）
        long deadline = now() + timeoutMillis;
        while (now() < deadline) {
            DecisionAnswer local = f.get(2, TimeUnit.SECONDS);   // 内存唤醒（毫秒级）
            if (local != null) return local;
            DecisionAnswer fromDb = decisionStore.findAnswer(token); // 跨实例兜底（≤2s 延迟）
            if (fromDb != null) return fromDb;
        }
        return DecisionAnswer.undecided();   // 超时 → 走慢路径快照
    } catch (TimeoutException | InterruptedException e) {
        f.cancel(false);
        return DecisionAnswer.undecided();
    } finally {
        pending.remove(token);
    }
}
```

### 4.2 `DecisionAnswer`（审批答案的统一载体）

```java
public record DecisionAnswer(
        DecisionKind kind,      // permission / verify
        String token,
        boolean approved,        // approve=true / reject=false
        String note,             // 拒绝理由 / 核对答复
        Long decidedBy,
        Long decidedAt,
        boolean undecided        // true = 超时未决（触发慢路径）
) {
    public static DecisionAnswer undecided() { ... }
}
```

### 4.3 工具侧改造形态（选定 T-6：工具内部 await）

以 `SubmitLedgerForReviewTool` 为例，改造前后对照：

```java
// ════ 改造前（一期 fresh 模式）════
public String execute(...) {
    if (confirmToken == null) {
        return planRound(...);        // 返回计划文本 → 轮次收尾 → 等用户下轮打字确认
    }
    return executeRound(..., confirmToken);   // 二轮（新一轮 LLM 调用才到这里）
}

// ════ 改造后（M-HA5 原地等待）════
public String execute(...) {
    if (confirmToken == null) {
        // ① planRound：出计划 + 落决策单（**await 之前无副作用**，见 §2.3 红线）
        Plan plan = buildPlanAndIssueToken(...);

        // ② 原地等待人工审批（在线命中 → 立即继续；超时 → 返回未决标记）
        DecisionAnswer ans = decisionAwaiter.await(plan.token(), awaitTimeoutMillis);
        if (ans.undecided()) {
            return AWAIT_DECISION_MARKER;     // "__await_decision__" → 内核走快照慢路径
        }
        if (!ans.approved()) {
            return renderRejected(ans.note()); // 拒绝：工具内直接终结（无需 LLM 参与）
        }
        // ③ 批准：同一 tool call 内原地执行（原 executeRound 逻辑）
        return executeRound(plan, ans);        // 真正写主平台
    }
    return executeRound(...);                  // 一期 fresh 兼容路径（灰度期保留）
}
```

**兼容性设计**：
- 灰度开关 `data-agent.hitl.inline-enabled=true`（按工具粒度）→ 走新路径；关闭 → 走一期 fresh 路径；
- 两条路径**共用同一份 `executeRound` 逻辑**（真正执行的部分绝不分叉），杜绝行为漂移；
- 一期 fresh 路径的 `verifyAndConsume` 放宽（接受 pending/approved）在 M-HA2 后收紧为"仅 approved"；M-HA5 全面切换后自然只允许 approved。

### 4.4 内核侧改造（AgentLoopExecutor）

```java
// 在 finishRound 处理工具结果时新增分支：
private void handleToolResult(ToolExecutionOutcome outcome) {
    if (AWAIT_DECISION_MARKER.equals(outcome.result())) {
        // ① 构建快照（复用现有 buildSnapshot + JdbcPauseStateStore）
        //    reason = PauseState.Reason.HITL_TOOL_REQUEST（枚举已预留！）
        //    pendingToolCalls 保留（恢复时重放）
        //    新增 decisionRef（§0 T-5）→ 关联 agentx_decision.id
        // ② 发 PAUSED 事件（pauseReason = "hitl"）
        // ③ persistTerminal("interrupted")（复用现有中断终态语义）
        // ④ 结束本轮（释放工具线程）
        return;
    }
    ... 原有逻辑
}
```

> [!NOTE]
> **与 04 文档拦截器化的关系**：若先完成 `RoundInterceptor` 改造，本分支可落在一个 `DecisionSuspendInterceptor` 里（"工具返回挂起标记 → 拦截器接管快照与收尾"），**主循环零改动**；否则要直接改主循环（风险更高）。**建议 T-1 先做拦截器化。**

### 4.5 恢复链路：resumeStream 时注入审批答案

```java
// ReactAgent.resumeStream → initMessagesFromResume 之后：
// ① 从快照读 decisionRef
String decisionRef = pauseState.getDecisionRef();
// ② 查决策单当前状态
DecisionAnswer ans = decisionStore.findAnswer(decisionRef);   // approved/rejected/未决
// ③ 若已决 → 注入 ctx（工具重放时读到即走 executeRound，不再等审批）
//    若未决 → 仍需等待：重放时工具内 await 会再次挂起（可接受）
// ④ ctx.put("decisionAnswer", ans)
```

**重放时的参数改写问题**：pendingToolCalls 里存的是**首轮参数**（无 confirmToken），重放时工具会再次走 `confirmToken == null` 分支 → 重新出计划 + 重新 await。这是**可接受的**（幂等：会生成新令牌，旧的 withdrawn），但会产生一条新决策单。更优做法：重放时把决策单答案直接注入 ctx，工具检测到 `decisionAnswer` 已存在则**跳过 await**，直接用原计划快照执行——需在 `planRound` 里保留"复用上次计划快照"的分支（PlanCache 已有快照机制可复用）。

### 4.6 DecisionController 双路径收口

```java
@PostMapping("/agent/decision/decide")
public ResponseEntity<?> decide(@RequestBody DecideRequest req) {
    // ① 鉴权：登录人 == 决策单归属人
    // ② kind 校验：collect → 400（H7）
    // ③ CAS：pending → approved/rejected（双击幂等）
    // ④ 落库答案（decided_by/decided_at/note）

    // ⑤ 分流：
    DecisionAnswer ans = ...;
    if (decisionAwaitRegistry.complete(token, ans)) {
        // ── 在线命中：唤醒工具线程，原 SSE 流继续推事件
        //    返回 202 Accepted（**不是 SSE**），前端保持监听原流
        return ResponseEntity.accepted().body(Map.of("mode", "inline-wakeup"));
    }
    // ── 未命中（离线/已超时/跨实例/进程重启）：走 resume 路径
    //    合成确认消息 → dodoAgent.stream(...) → 返回 SSE 流
    return sseStreamOf(dodoAgent.stream(synthMessage(...)));
}
```

**前端协议分流（T-4）**：

| decide 响应 | 场景 | 前端行为 |
| :--- | :--- | :--- |
| `text/event-stream` | 一期 fresh / M-HA5 慢路径 | **新开流**接续渲染（现有 `openAgentStream` 逻辑） |
| `202 {"mode":"inline-wakeup"}` | M-HA5 在线命中 | **保持监听原流**，不新开流；原流会继续推 TOOL_END/TEXT/COMPLETE |

前端改造点：`api/agent.js` 的 `decideDecision()` 需按 `Content-Type` 分流——SSE 走流解析，JSON 则仅更新卡片状态（等原流推来终态）。

---

## 5. 关键技术专题

### 5.1 线程模型与资源账

| 维度 | 数值/结论 |
| :--- | :--- |
| 占用线程 | 每次在线等待占 1 条 toolPool 线程（`Executors.newCachedThreadPool`） |
| 等待时长 | ≤ T-2 建议值（60s），超时即释放转快照 |
| 并发上限 | **同会话互斥**：`AgentTaskManager` 同 `conversationId` 只允许一个运行任务 → 同会话不会出现多张卡同时挂起 |
| 跨会话并发 | N 个会话 = N 条线程，可接受（每请求建池，`finishSink` 时 shutdown） |
| 泄漏风险 | `await` 的 `finally` 必须 `pending.remove(token)`；`future` 必须 cancel 防泄漏 |

### 5.2 超时降级：为什么超时是"正常路径"而非异常

超时（60s 内用户没点）**不是故障**，而是"用户暂时不在"。降级链：

```text
await 超时 → 工具返回 __await_decision__ → 内核写快照 → 流 PAUSED(hitl) 收尾
    ↓（用户几小时后回来）
卡片（回放重建）→ [批准] → decide() → registry miss → resumeStream 重放 → 执行
```

**关键**：整个过程中**没有任何副作用丢失**——工具 await 前只读不写（§2.3 红线），真正的写操作发生在"拿到批准之后"。

### 5.3 挂起期间的并发与幂等

- 挂起期间，用户可能**反复点**批准 → decide 的 CAS 保证只有第一次生效；
- 挂起期间，用户可能**发新消息**（takeOver）→ 现有 takeOver 逻辑会丢弃中断快照（`discardInterruptedState`），此时决策单应同步置 `withdrawn`（**待补：takeOver 与决策单的联动**）；
- 挂起期间，用户可能**关掉会话** → `SessionService` 级联删除时同步删决策单（M-HA3 已做）。

### 5.4 跨实例唤醒三选一（T-3）

```text
方案 a：会话粘滞（nginx 一致性哈希 by conversationId）
  优点：零代码改动（registry 命中即可）
  缺点：需网关支持；扩缩容/故障转移时可能落到别的实例 → 退化为慢路径（仍正确，只是多一轮）

方案 b：DB 状态轮询兜底（推荐）
  优点：零基础设施要求；工具线程每 2s 查一次决策单表 → 跨实例 2s 内感知
  缺点：轮询有轻微 DB 压力（可只对 pending 决策单轮询，量级很小）
  失效模式：实例 A 的工具线程仍在等，但用户批准发生在实例 B → B 的 CAS 落库 → A 轮询发现 → 继续 ✅

方案 c：Redis Pub/Sub 广播
  优点：延迟最低（毫秒）
  缺点：引入新中间件依赖；需处理"消息丢失→靠 DB 轮询兜底"的组合（实际是 b+c 叠加）
```

**推荐**：**b 为基线，c 作为可选加速**。实例内唤醒走内存 future（毫秒级），跨实例走 2s 轮询。

### 5.5 与前端事件流的衔接

| 阶段 | 事件 | 前端表现 |
| :--- | :--- | :--- |
| 工具挂起中 | `DECISION`（kind=permission） | 卡片出现，按钮可点 |
| 等待超时转快照 | `PAUSED`（pauseReason=hitl） | 卡片保持，消息区显示"⏸ 等待人工审批中" |
| 批准后原地唤醒 | `TOOL_END` → `TEXT` → `COMPLETE` | **原流继续**，卡片翻终态 |
| 慢路径恢复 | 同上（来自新流） | 新流接续渲染 |

**注意**：M-HA5 模式下前端可能出现"**两张流同时活着**"的短暂窗口（decide 走慢路径时新流开、原流已 PAUSED 收尾）——需在协议里明确：原流收到 `PAUSED(hitl)` 后即视为结束，前端可安全关闭。

---

## 6. 分步实施计划（每步独立可验收、可回滚）

| 步骤 | 内容 | 涉及文件 | 验收 | 回滚 |
| :--- | :--- | :--- | :--- | :--- |
| **5.0** | （可选前置）04 文档拦截器化改造 | `AgentLoopExecutor` / 新增 `agent/interceptor/*` | 内核 100 + server 177 全绿 | git revert |
| **5.1** | `DecisionAwaitRegistry` + `DecisionAnswer` + 决策单查询（`findAnswer`） | 新增 `action/decision/*` | 单测：注册/唤醒/超时/清理 | 纯新增，删类即回滚 |
| **5.2** | 工具侧改造（**先只做 `SubmitLedgerForReviewTool` 试水**） | `SubmitLedgerForReviewTool` | 单测：await 命中 → 同 call 内执行；未命中 → 返回标记 | 开关 `inline-enabled=false` |
| **5.3** | 内核挂起分支：`__await_decision__` → `HITL_TOOL_REQUEST` 快照 + `PAUSED(hitl)` | `AgentLoopExecutor` / `PauseState`（加 `decisionRef`） | 单测：挂起后快照落库正确、线程释放 | 开关 + 分支 if 短路 |
| **5.4** | 恢复链路：resumeStream 注入 `decisionAnswer` + 工具跳过 await | `ReactAgent` / `AgentLoopExecutor` / 工具 | 单测：快照恢复后直接执行 | 开关 |
| **5.5** | `DecisionController` 双路径 + 前端协议分流（202 vs SSE） | `DecisionController` / `api/agent.js` / `ChatView.vue` | 浏览器验收：在线唤醒 <100ms 且**LLM 调用次数不增加** | 开关（退回 fresh） |
| **5.6** | 逐工具推广：审批 → 驳回 → 部分审批 → 重提 → 补传 | 各工具类 | 每个工具 T-HA5 用例 | 单工具级开关 |
| **5.7** | 观测期双跑（fresh 与 inline 各跑一半流量，比对结果一致性） | 监控中心 | 一致率 ≥99.9% | 开关切回 |
| **5.8** | 退场 fresh 路径（保留开关兜底一个版本） | 工具类 | 全量回归 | git revert |

### 6.1 灰度开关设计

```yaml
data-agent:
  hitl:
    inline-enabled: false              # 总开关（M-HA5 总闸）
    inline-tools:                      # 工具粒度灰度（按工具名）
      - submitLedgerForReview
      - approveTask
    await-timeout-ms: 60000            # 在线等待上限（T-2）
    poll-interval-ms: 2000             # DB 轮询兜底间隔（T-3 方案 b）
    verify:
      exclude-tools:                   # 幂等写可豁免核对卡
        - stageDraft
```

---

## 7. 验收测试用例

| 序号 | 测试场景 | 操作步骤 | 通过标准 |
| :--- | :--- | :--- | :--- |
| **T-HA5-1** | **在线原地唤醒（零 LLM）** | 触发提审 → 2s 内点[批准] | 工具在**同一次 tool call 内**完成执行；**LLM 调用轮数不增加**（对比 trace 表 `round` 数与 token）；唤醒延迟 <100ms |
| **T-HA5-2** | **唤醒后上下文连续** | 提审批准后，模型继续下一轮 | 模型对"工具已执行"无感知，不重复调用该工具，不重新规划 |
| **T-HA5-3** | **超时转快照** | 触发提审 → 不点 → 等超时 | 工具线程释放；`agentx_pause_state` 落快照（reason=hitr，decisionRef 正确）；前端显示"等待人工审批中"；**无副作用产生** |
| **T-HA5-4** | **离线恢复（跨重启）** | 超时挂起 → **强杀后端重启** → 卡片点[批准] | 决策单在库（approved），resume 重放后工具带答案直接执行成功；**不产生第二条决策单**（T-4 决策Ref 复用） |
| **T-HA5-5** | **拒绝原地终结** | 挂起中点[拒绝]并填理由 | 工具在同 call 内返回拒绝结果，不写主平台；理由进入后续对话上下文 |
| **T-HA5-6** | **双击幂等** | 快速连点[批准] 5 次 | 仅一次执行；其余 409；决策单状态唯一 |
| **T-HA5-7** | **跨实例唤醒** | 实例 A 挂起，在实例 B 点[批准] | ≤2s（轮询间隔）内 A 的工具线程感知并继续执行（若采用方案 b） |
| **T-HA5-8** | **takeOver 联动** | 挂起中用户发新消息 | 快照被丢弃，**决策单同步置 withdrawn**（不留下"永远 pending"的孤儿单） |
| **T-HA5-9** | **双路径一致性** | 同一提审任务分别在 inline 模式与 fresh 模式执行 | 结果、审计记录、决策单终态三者一致（观测期双跑比对） |
| **T-HA5-10** | **回滚可用** | 灰度中把 `inline-enabled` 置 false | 立即退回 fresh 路径，无残留状态污染 |

---

## 8. 风险清单

| 风险 | 等级 | 缓解 |
| :--- | :--- | :--- |
| **await 前产生副作用**（红线） | 🔴 极高 | 代码评审红线 + 单测断言"planRound 无写调用"；工具层提供统一基类约束 |
| 动内核主循环引入回归 | 🔴 高 | 优先做 04 拦截器化；灰度开关 + 双跑观测；每步独立可回滚 |
| 挂起线程泄漏 | 🟠 中 | `finally` 强制清理 registry；超时强制 cancel；监控挂起线程数 |
| 跨实例唤醒延迟（方案 b） | 🟡 低 | ≤2s 可接受；必要时叠加 Redis Pub/Sub |
| 决策单与快照双写不一致 | 🟠 中 | 决策单状态是真相，快照只存引用；恢复时以决策单为准 |
| takeOver/删除会话留孤儿单 | 🟡 低 | 补联动逻辑（T-HA5-8）；定时清扫超期 pending |
| 用户困惑（同一会话两种交互模式） | 🟡 低 | 前端统一卡片形态；decide 返回 mode 字段供前端区分 |

---

## 9. 与其他方案文档的关系

```text
docs/HITL人工决策与审批技术方案.md   ← 主文档（M-HA0~HA4 实施 + 三型 HITL 设计）
        │
        │ 引用（快路径终态）
        ▼
docs/HITL改版-内核挂起快路径方案(M-HA5).md   ← 本文档（TODO，待启动）
        │
        │ 依赖（内核拦截器化是最佳前置）
        ▼
docs/多agent技术调研/单agent演进多agent文档/04-harness进阶-执行内核拦截器化与插件注册化改造方案.md
```

> **推进建议**：M-HA0~HA4 照主文档顺序推进（不依赖本文档）；本方案在**工具数量明显扩张**或**批量审批场景出现**时启动；启动前先把 §0 的 TODO 逐条拍板。
