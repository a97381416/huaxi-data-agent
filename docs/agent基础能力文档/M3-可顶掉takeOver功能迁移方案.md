# “可顶掉 (takeOver)”功能迁移方案（从 0 到 1 移植指南）

> 目标读者：要把本项目的“回答进行中发新消息 → 自动顶掉旧回答”能力移植到**另一个 Agent** 的开发者。
> 参考实现：`backend/data-agent-server` 的 `AgentController.takeOverActiveStream()`（编排层）+ `backend/mini-agentx-core` 的 `AgentTaskManager`/`AgentLoopExecutor`（内核，零改动复用）。
> 阅读前提：目标 Agent 已流式输出、有会话维度（conversationId）。
> 姊妹篇：《刷新不丢功能迁移方案.md》提供地基一（执行/连接解耦 + 进行中流注册表），《打断恢复功能迁移方案.md》提供地基二（interrupt 可恢复打断）。**本文是架在这两块地基之上的编排层功能——新增代码极少，但对时序的要求是三篇里最严格的。**

---

## 1. 问题与思路（一分钟版）

**现象**：Agent 跑一个复杂任务要几十秒。用户等不及、改了主意，或者发现问错了——直接在输入框敲下新消息。此时旧回答还在流式输出中，新消息怎么处置？

**三种产品选择**：

| 方案 | 交互 | 代价 |
| --- | --- | --- |
| 拒绝 | “请先停止当前回答” | 每次都要先点停止再发送，多一步操作，体验差 |
| 排队 | 新消息挂在旧回答后面 | 用户改主意时旧回答白跑，浪费 token；交互语义混乱 |
| **顶掉（豆包式，本方案）** | 新消息自动打断旧回答并立即开始新回答 | 需要严格的停止时序，否则会出现并发执行/僵尸恢复 |

**顶掉的语义拆解**（四个动作，顺序不可乱）：

```
① 打断旧回答   —— 复用 interrupt：半截输出入链、半截问答落库（历史保留，不丢内容）
② 等旧流真停   —— 轮询 ≤3s：快照落库、PAUSED 发出、注册表摘除都收尾完
③ 丢弃旧断点   —— 删掉①产生的快照：旧回答已被新意图取代，恢复通道作废
④ 全新执行     —— 新消息作为全新 query 走正常入口；停不下来则 409 拒绝保护现场
```

**两个容易混淆的“丢”**：
- **丢的是断点，不是历史**：旧问答的“用户问题 + 半截 AI 回复”照常落库（`persistTerminal("interrupted")`），刷新后历史时间线里完整可见——顶掉只是“不再从这条断点续跑”。
- **丢的是快照，不是订阅**：`interrupt` 本身会产生一份可恢复快照（这是地基二的行为），takeOver 在等停之后把它显式删掉——否则下次发消息时旧任务会从断点“复活”（僵尸恢复）。

**一个反直觉的结论**：takeOver **不需要改内核**。它 100% 复用地基二的 `interrupt()` 路径（快照照存、PAUSED 照发、半截历史照落），只是编排层在事后多删一次快照。本方案在接入层的新增代码 ≈ 一个私有方法 + 两个常量。

---

## 2. 总体时序与六条不变式

```
 前端（回答进行中）          Controller                    内核 AgentLoopExecutor            LiveStreamRegistry
  │ POST /stream(新消息)      │                                │                              │
  │ ───────────────────────► │ isActive(convId) == true        │                              │
  │                          │ ① interrupt(convId,"顶掉") ────►│ (打断线程上同步收尾):           │
  │                          │   [AgentTaskManager]            │  刷半截输出入链 → 补占位        │
  │ ◄─ PAUSED(旧流SSE) ───── │ ◄── sink.tryEmitNext ──────────│  → 快照 save → 半截历史落库     │
  │                          │                                │  → PAUSED → sink complete     │
  │                          │                                │     → unregisterTask          │
  │ (旧流 onClose: 身份守卫收尾)│ ② 轮询 isActive ≤ 3s (100ms×30) │           doFinally: 摘除 ───►│ isActive=false
  │                          │ ③ discardInterruptedState ─────►│ pauseStateStore.delete(旧断点) │
  │                          │ ④ agent.stream(新query) ───────►│ 全新执行（新轮次、新落库）       │
  │                          │ register(新流) ────────────────────────────────────────────────►│ isActive=true
  │ ◄─ 新流 TEXT/TOOL/... ── │                                │                              │
```

迁移时**不可走样**的六条不变式：

1. **两块地基必须先在**。判定“有没有进行中的回答”依赖进行中流注册表（地基一）；“打断”依赖任务登记 + 可恢复 interrupt（地基二）。地基缺失时，先迁姊妹篇，本文无处安放。
2. **“真停”的判据是注册表摘除，不是 interrupt 返回值**。`interrupt()` 返回 true 只代表“信号已发出”；快照落库、半截历史落库、PAUSED 发出、`sink complete → doFinally 摘除`这条收尾链跨越打断线程与流信号传播，不是原子的。轮询 `isActive` 变 false 才算真停。
3. **先等停，再删快照，顺序不能反**。快照的 `save` 发生在 onInterrupt 收尾链里（打断线程），如果 takeOver 在 save 之前就 delete，随后迟到的 save 会把旧断点重新留下——下次发消息走恢复通道，旧任务复活。等停保证了 save 早已发生，此时的 delete 才删得干净。
4. **停不下来必须 409 拒绝**。轮询超时说明旧执行卡在无法被打断的状态（工具阻塞等），此时执行新请求 = 同会话两个执行并行写一份会话历史。宁可让用户稍后重试，不能破坏互斥。
5. **半截历史必须保留**。onInterrupt 收尾链里的 `persistTerminal("interrupted")` 不能为 takeOver 单独跳过——旧问答的完整性由它保证，前端“被顶掉的半截回答”在刷新后依然可见。
6. **前端一切收尾按身份（identity）守卫**。顶掉期间旧流与新流短暂并存（同一 conversationId 两个流条目），旧流的 PAUSED/onClose 收尾不得误清新流的状态，判据永远是 `liveStreams.get(cid) === entry` 或 `!liveStreams.has(cid)`。

---

## 3. 第 0 步：迁移前置检查

动手前确认目标 Agent 满足以下五点，缺什么补什么：

| # | 检查项 | 不满足时的补救 |
| --- | --- | --- |
| A | 执行**独立于 HTTP 连接**（后台驱动），且有**进行中流注册表**：`isActive(convId)` / `register()` / 流终止摘除 | 先迁《刷新不丢方案》——它的 Step 2 就是这个注册表 |
| B | 有**可恢复打断**：`interrupt(convId, msg)` 能定位并 dispose 运行中任务，收尾产出“半截历史落库 + 快照 + PAUSED” | 先迁《打断恢复方案》——takeOver 的①③两步就是借它的路 |
| C | 事件模型有 **PAUSED 终态**（前端能区分“暂停了”和“真结束”） | 《打断恢复方案》Step 1 |
| D | 每轮问答有稳定 **conversationId**，前后端同键路由 | 前端生成 UUID 随请求携带 |
| E | 前端有**本地流表**（cid → 流条目），能区分“当前流的身份” | 《刷新不丢方案》Step 7（全局流表 + 游标去重） |

A、B 两项是硬前置。**本文不重复展开地基的代码**，聚焦编排层新增物；内核复用部分只讲“它为 takeOver 提供了什么保证”。

---

## 4. Step 1：判定“有进行中的回答”

判定点是 **POST /stream 主入口**：非 recovery 请求进来时，看该会话是否有流在跑。

```java
// Controller.stream() 内，recovery 分支之后、快照/全新执行路由之前：
@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> stream(@RequestBody ChatRequest request) {
    // ...① recovery 断线续传分支（见《刷新不丢方案》）...

    // ② 执行中来了新消息（对齐豆包交互）：打断旧回答、丢弃断点，随后走全新/恢复执行
    if (liveStreams.isActive(conversationId)) {
        takeOverActiveStream(conversationId);
    }
    // ...③ 快照恢复路由 / ④ 全新执行（见 Step 5）...
}
```

为什么判据用**注册表** `isActive` 而不是内核的 `hasRunningTask`：

| 维度 | `liveStreams.isActive`（注册表） | `taskManager.hasRunningTask`（内核） |
| --- | --- | --- |
| 生命周期 | 从 `register()` 到流终止 doFinally 摘除 | 从执行器 `run()` 到终态注销 |
| 覆盖的窗口 | 前端视角：这条 SSE 流还活着 | 内核视角：这个任务还在跑 |
| 注册表摘除瞬间 | 流终止信号传播后（略晚） | `finishSink()` 里 tryEmitComplete 后（略早） |

两者在“流刚结束”的毫秒级窗口内可能不一致，但**都收敛于 false**，且 takeOver 对不一致是安全的（interrupt 返回 false / isActive 轮询兜底，见 Step 2/3）。选注册表的原因是对齐**前端视角**：前端正是因为“界面上这条回答还在流”才允许发送，后端判定口径应与之一致。

注册表摘除的时机与方式（地基一提供，这里确认摘除语义即可）：

```java
// InMemoryLiveStreamRegistry.register()——摘除必须按 key+value，防误删顶掉后的新流
ConnectableFlux<ServerSentEvent<AgentStreamEvent>> replay = source
        .index((i, evt) -> ServerSentEvent.builder(evt).id(String.valueOf(i + 1)).build())
        .doFinally(signal -> {
            LiveStream entry = self.get();
            if (entry != null) {
                // 按 key+value 摘除：同一会话紧接着开下一轮时，不会误删新注册的流
                live.remove(conversationId, entry);
            }
        })
        .replay();
```

---

## 5. Step 2：打断旧回答（零改动复用 interrupt）

takeOver 的“打断”就是普通的用户中断，**唯一的区别是附言文案**——它会进快照的 `interruptMessage` 字段，留痕排查用：

```java
boolean interrupted = agent.interrupt(convId, "新消息顶掉旧回答");
```

`interrupt()` 内部（地基二已提供，列出只为明确它给了 takeOver 哪些保证）：

```java
public boolean interrupt(String conversationId, String message) {
    TaskInfo info = tasks.remove(conversationId);        // remove 而非 get：并发第二次返回 false
    if (info == null) return false;                      // ★ 无运行任务（边界窗口，见下）
    if (info.disposable != null && !info.disposable.isDisposed()) {
        info.disposable.dispose();                       // 切断 LLM 流
    }
    info.interruptHandler.accept(message);               // 在打断线程上同步执行收尾
    return true;
}
```

收尾链在**打断线程**上同步跑完（`AgentLoopExecutor.onInterrupt`），它保证了 takeOver 后续两步（等停、删快照）操作的是“已经整理好的现场”：

```
onInterrupt(interruptMessage):
  刷半截输出入链（LLM 流式被打断 → 已生成的字不丢）
  未回灌 toolCall 补占位响应（协议配对）
  → stateStore.save(snapshot)          ★ 快照落库（takeOver 第③步要删的就是它）
  → persistTerminal("interrupted")     ★ 半截问答落库（历史保留，不变式5）
  → emit(PAUSED) → resultRef=paused
  → finishSink(): sink.tryEmitComplete + taskManager.unregisterTask
```

**interrupt 返回 false 的边界窗口**也安全：注册表还活着但任务刚注销（流刚正常结束、摘除信号还在传播）时，`interrupt` 返回 false——不打断任何东西，Step 3 的轮询会等注册表摘除（毫秒级），Step 4 的 delete 删不到快照（正常终态早已删过），流程照常落到 Step 5 全新执行。**takeOver 编排不需要对返回值做分支处理。**

---

## 6. Step 3：等旧流真正停（轮询 ≤3s + 409）

这是编排层的核心。**为什么必须等**：`interrupt()` 返回 ≠ 旧流已停。快照落库、历史落库在打断线程同步完成，但 **PAUSED 事件传播 → sink complete → 注册表 doFinally 摘除**要经流信号异步收敛。不等就删快照/起执行，会踩不变式 2/3。

```java
/** takeOver 等待旧流停止的轮询上限：30 × 100ms = 3s（docs/03 M3 口径） */
private static final int TAKEOVER_POLL_TIMES = 30;
private static final long TAKEOVER_POLL_INTERVAL_MS = 100;

/**
 * 新消息顶掉进行中的回答：打断旧执行，等旧流真正停下后删除打断产生的快照
 * （断点已被新请求取代，半截回答保留在历史时间线里），随后走全新/恢复执行。
 * 极端情况旧执行没在超时内停下，返回 409 拒绝，防止同会话并行执行。
 */
private void takeOverActiveStream(String convId) {
    boolean interrupted = agent.interrupt(convId, "新消息顶掉旧回答");   // ① 打断（会产快照）
    // ② 等待旧流真正停止（快照落库、注册表摘除），最多 3 秒
    for (int i = 0; i < TAKEOVER_POLL_TIMES && liveStreams.isActive(convId); i++) {
        try {
            Thread.sleep(TAKEOVER_POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
        }
    }
    if (liveStreams.isActive(convId)) {
        // ④停不下来：拒绝新请求（新消息不入库，前端展示错误，用户稍后重试）
        throw new ResponseStatusException(HttpStatus.CONFLICT, "当前会话回答尚未停止，请稍后重试");
    }
    agent.discardInterruptedState(convId);               // ③ 删旧断点（不变式3：必须在等停之后）
    log.info("[data-agent] 新消息顶掉进行中的回答: convId={}, interrupted={}", convId, interrupted);
}
```

三个设计取舍：

1. **轮询而不是事件等待**（CountDownLatch/订阅 PAUSED）：轮询实现 15 行、无回调地狱、3s 封顶；代价是阻塞当前请求线程至多 3s——正常情况下旧流在几百毫秒内就停了，3s 是极端兜底。若目标服务跑在 WebFlux event-loop 上，`Thread.sleep` 不可接受，改成在 `boundedElastic` 调度器上 `Flux.interval` 轮询或事件等待，逻辑不变。
2. **409 的语义**：不是失败，是“保护现场拒绝服务”。此时旧执行还在（可能工具阻塞），新消息**尚未产生任何落库**，前端展示错误后用户重试即可；绝不降级为“强行并行执行”。
3. **409 必须以真实 HTTP 状态码透出**。SSE 端点上的同步异常默认会被全局兜底处理器吞成 HTTP 200 + code:500，前端按状态码分支的逻辑会整体失效。必须单独透传：

```java
// GlobalExceptionHandler——没有这段，前端拿到的 409 会变成 200
@ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
public ResponseEntity<R<Void>> handleResponseStatus(org.springframework.web.server.ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode())
            .body(R.fail(e.getStatusCode().value(), e.getReason()));
}
```

---

## 7. Step 4：丢弃断点快照（防僵尸恢复）

```java
/** 丢弃中断快照（takeOver 顶掉旧断点时用） */
public boolean discardInterruptedState(String conversationId) {
    return pauseStateStore.delete(conversationId);       // true=确实删了一份
}
```

**为什么必须在等停之后**（不变式3，最容易被“优化”掉的时序）：快照 `save` 在 onInterrupt 收尾链里、位于 PAUSED 发出之前；注册表摘除是收尾链末端之后的流信号传播。如果 delete 抢在 save 前执行：

```
错误时序：interrupt → [delete（此刻快照还没save，删了个寂寞）] → ... → onInterrupt 迟到 save → 僵尸快照落库
后果：下次发消息 hasInterruptedState=true → 走恢复通道 → 旧任务复活，和用户刚发的新问题抢会话
```

等停（isActive=false）保证了 save 早已完成，此时 delete 是对“既成事实”的清理，必然删得干净。

**为什么必须删**：这份快照是①的 interrupt 自己产生的，它的语义（“用户中断后可能回来续跑”）已被新请求否定——用户的新消息就是对旧任务的最终裁决。不删，恢复通道会在下次发消息时错误触发（僵尸恢复，《打断恢复方案》坑 8 的 takeOver 特例）。

**覆盖写场景顺带说明**：如果旧流本身是一次恢复执行（快照在恢复期间仍在，终态才删），takeOver 打断它时 onInterrupt 会 save **新快照覆盖旧快照**——然后照常被③删掉。不存在“新旧快照叠加”的问题，快照键就是 conversationId，一票一位。

---

## 8. Step 5：接回主路由（takeOver 与恢复通道的交汇）

takeOver 编排完成后，代码**落回 stream() 的既有路由**，无需任何特殊分支：

```java
Flux<AgentStreamEvent> source;
// ③ 存在中断快照 → 恢复执行（本次 query 作为 resumeQuery，空 = 纯恢复）
if (agent.hasInterruptedState(conversationId)) {
    source = agent.resumeStream(conversationId, userId, request.query(), ...);
} else {
    // ④ 全新执行（query 必填）
    source = agent.stream(request.query(), conversationId, userId, ...);
}
// 注册并后台驱动：返回给前端的和重连拿到的是同一个可重放流
Flux<ServerSentEvent<AgentStreamEvent>> registered = liveStreams.register(conversationId, userId, source);
return registered.map(this::toSse);
```

takeOver 路径的事实链：③的 delete 已把①产生的快照删掉 → `hasInterruptedState=false` → **必然走④全新执行**，新消息成为新轮次的 query。③保留在这里不是给 takeOver 用的，而是给“打断停（用户主动 /agent/stop）→ 回来发消息”的恢复场景用的——两条路径在同一个入口自然交汇：

| 场景 | 旧流在跑？ | 有快照？ | 走向 |
| --- | --- | --- | --- |
| takeOver（执行中发新消息） | 是 → 顶掉并删断点 | 否（刚删） | ④全新执行 |
| 停止后回来发消息 | 否 | 是 | ③恢复执行 |
| 普通新消息 | 否 | 否 | ④全新执行 |
| 顶掉期间残留旧快照的极端窗口（interrupt=false 且此前有未消费快照） | 即将停 | 是 | ③恢复执行（无害：新消息作为 resumeQuery 交给模型，语义仍是“用户最新消息优先”） |

**同会话互斥的最后防线**（内核已有，takeOver 是它最大的受益者）：万一前端防抖失效、两个请求都通过了 isActive 检查，内核 `registerTask` 的 `putIfAbsent` 会让第二个执行器直接抛 `IllegalStateException`，由全局异常处理器转错误响应——绝不允许两个执行并行写同一份会话历史：

```java
public void registerTask(String conversationId, Sinks.Many<?> sink, InterruptHandler interruptHandler) {
    TaskInfo existing = tasks.putIfAbsent(conversationId, new TaskInfo(sink, interruptHandler));
    if (existing != null) {
        throw new IllegalStateException("会话已有进行中的执行: " + conversationId);
    }
}
```

---

## 9. Step 6：前端配合（四个守卫点）

前端对 takeOver **没有“顶掉按钮”也没有专门请求**——就是普通的发送。需要的是四处守卫，保证旧流收尾不污染新流：

### 9.1 发送防抖窗口（唯一的前置拦截）

```js
function send() {
  // ...
  // 回答进行中也允许发送（后端自动顶掉）——只拦这一个极短窗口：
  // 首个事件都没到说明后端可能还没 register，此时连发会双双通过 isActive 检查
  if (liveStreams.has(cid) && liveStreams.get(cid).lastSeq === 0) {
    return
  }
  // ...照常 POST /agent/stream，新消息 query 直接发出去
}
```

输入框 Tooltip 同步告知用户这是产品设计而非 bug：`回答进行中发送新消息将自动顶掉旧回答`。

### 9.2 PAUSED → 中断态标注（旧回答的半截内容定格）

```js
case 'PAUSED': {
  // 本轮被终止（点停止/被新消息顶掉）：running 工具置为中断态
  entry.paused = true
  applyPausedState(cid, entry)   // msg.interrupted=true；running 工具 → interrupted + 说明文案
  break
}
```

### 9.3 身份守卫（不变式6，最容易踩的前端坑）

顶掉期间同一 cid 先后存在两个流条目，旧流的收尾事件（PAUSED/onClose）可能在新流注册**之后**才到，所有清理必须带身份判断：

```js
/** 流结束：清理注册表，恢复界面状态（identity 守卫防误清 takeOver 新流） */
function finishStream(cid, entry) {
  if (liveStreams.get(cid) === entry) liveStreams.delete(cid)   // ★ 只删自己
  entry.aiMsg.streaming = false
  const s = sessions[cid]
  if (s && !liveStreams.has(cid)) s.streaming = false           // ★ 有新流在跑就不置空闲
}

function markConversationIdle(cid) {
  // takeOver 场景：旧流被打断但新流仍在跑（liveStreams 还有该会话）时不能误标空闲
  const item = conversations.value.find((c) => c.conversationId === cid)
  if (item && !liveStreams.has(cid)) item.streaming = false     // ★ 同上
  const s = sessions[cid]
  if (s && !liveStreams.has(cid)) s.streaming = false
}

// applyPausedState 里同理：只有旧条目还是“当前流”时才把会话标成 interrupted
const s = sessions[cid]
if (s && liveStreams.get(cid) === entry) s.interrupted = true   // ★ 被顶掉的旧流不标
```

没有这三处守卫的典型症状：旧流 PAUSED 一到，界面把新流误标成空闲/中断，用户以为新回答死了，实际新流正在跑。

### 9.4 409 分支（服务端拒绝顶掉时）

```js
onError(err) {
  // 409 = takeOver 等待超时 / 400 等：直接展示错误
  if (err.status === 409 || err.status === 400 || err.status === 401 || err.status === 403) {
    entry.aiMsg.timeline.push({ type: 'error', message: err.message || '请求被拒绝' })
    entry.aiMsg.streaming = false
    finishStream(cid, entry)
    return    // ★ 不走自动重连（这不是网络问题，重连也是 409）
  }
  // ...断线重连逻辑（404=流已结束拉历史）
}
```

---

## 10. 测试清单

### 单元/集成测试（内核已有 interrupt 测试地基，takeOver 编排补以下用例）

| 用例 | 断言要点 |
| --- | --- |
| 执行中发新消息 → 顶掉成功 | 旧流收到 PAUSED；新流正常产出；`has(conversationId)=false`（断点已删） |
| 半截历史保留 | 旧轮次以 `interrupted` 状态落库；历史查询可见旧问题+半截回答；新问答独立成轮 |
| 快照删除时序 | （构造慢收尾）delete 不早于注册表摘除；无僵尸快照残留 |
| 顶掉打断失败 → 409 | 轮询耗尽抛 409；**无新执行**启动；旧执行仍存活；无半截新消息落库 |
| interrupt=false 边界窗口 | 注册表 active 但任务已注销 → 顶掉流程不报错，照常全新执行 |
| 覆盖写场景 | 恢复执行期间发新消息 → 旧快照被覆盖后删除；新执行从全新轮次开始 |
| 同会话并发兜底 | 绕过 isActive 检查并发两请求 → 第二个 `registerTask` 抛 IllegalStateException |
| 连续快速顶掉 | 三条消息快速连发 → 只有最后一条的流活着，前两条都停在 interrupted |

### 手工验收场景（按用户故事走一遍）

1. **执行中发新消息**：旧回答立即定格为“已中断”，新回答马上开始流式输出；刷新页面，历史里旧问答（半截）和新问答都在、顺序正确。
2. **顶掉后直接发消息**：不触发恢复通道（快照已删），每次都是全新回答。
3. **工具执行中顶掉**：旧回答的 running 工具显示“已中断”，新回答正常；无工具副作用翻倍（工具未重放）。
4. **毫秒级双击发送**：第二条被前端防抖拦下（或被后端互斥拒绝），不出现两条并行流。
5. **顶掉一个恢复执行的会话**：先停止→恢复续跑中→发新消息，顶掉正常、无旧断点复活。
6. **（若可构造）执行卡死时发新消息**：3 秒后前端收到 409 错误提示；界面回到可操作状态，旧执行仍在（或由超时机制收割）。

---

## 11. 常见坑与适配点

### 11.1 踩坑清单（每条都有明确对症）

| # | 坑 | 对策 |
| --- | --- | --- |
| 1 | **先删快照再等停**：onInterrupt 的 save 迟到，僵尸快照落库，下次发消息旧任务复活 | 不变式3：`delete` 必须在 `isActive=false` 之后（Step 4） |
| 2 | **判据用 interrupt 返回值**：返回 true 就当“已停”，立即删快照/起执行，与收尾链赛跑 | 不变式2：轮询 `isActive` 直到 false（Step 3） |
| 3 | **没有轮询上限**：旧执行卡死时新请求线程无限等待 | 3s 封顶 + 409 拒绝（Step 3） |
| 4 | **409 被吞成 200+code:500**：前端按状态码的分支全部失效，用户看到莫名的“系统错误” | `ResponseStatusException` 单独透传真实状态码（Step 3.3） |
| 5 | **前端无身份守卫**：旧流 PAUSED/onClose 把新流误标空闲/中断/删除，新回答 UI 假死 | 三处守卫：`get(cid)===entry` / `!has(cid)`（Step 9.3） |
| 6 | **双击连发竞态**：首事件未到时连发两条，双双通过 isActive → 并行执行 | 前端 `lastSeq===0` 防抖 + 后端 `putIfAbsent` 互斥兜底（Step 5/9.1） |
| 7 | **takeOver 后误走恢复通道**：漏删快照或删早了，新消息变成 resumeQuery 续在旧任务后面 | 快照删除时序（Step 4）+ 上线路径②后 `hasInterruptedState` 必为 false 的自检日志 |
| 8 | **WebFlux 上 Thread.sleep**：阻塞 event-loop，整个服务吞吐崩塌 | 迁移到 `boundedElastic` 上的响应式轮询/事件等待（Step 3.1） |
| 9 | **多节点部署**：注册表是单机内存 Map，A 节点看不到 B 节点的流，isActive 判不准 → 顶掉失效甚至并行执行 | Redis 版注册表（本项目 M9 规划）或会话粘性路由；快照存储同理换共享存储 |
| 10 | **顶掉文案进快照但无人看**：排查时不知道哪次快照是被顶掉产生的 | interruptMessage 固定传“新消息顶掉旧回答”，排查日志 grep 它 |

### 11.2 框架适配点（目标 Agent ≠ 本项目技术栈时）

| 本项目依赖 | 适配方向 |
| --- | --- |
| 注册表 `isActive`（ConcurrentHashMap + doFinally 摘除） | 任意“进行中任务登记表”：能按 conversationId 判断存活、流终止时可靠摘除即可；摘除必须带身份比较（防误删顶掉后的新流） |
| `interrupt()`（dispose + 打断线程回调） | 目标框架的取消机制（线程 interrupt 标志 / Future.cancel / abort 控制器）；硬要求：**取消后有一条可靠的收尾路径**完成“半截入链 + 快照 + 终态事件” |
| `Thread.sleep` 轮询 | 阻塞式服务可照抄；响应式服务换 interval/事件等待 |
| `ResponseStatusException` → 409 | 任意 Web 框架的“以真实状态码拒绝”等价物；关键是**状态码必须原样到达前端** |
| 前端 Vue 响应式流表 | 任意框架的“流条目注册表”，核心是**条目身份比较**能力（对象引用即可） |

### 11.3 与姊妹篇的边界（recovery / resume / takeOver 三路对照）

三条路径共用一个 `/stream` 入口，迁移时最容易把它们的功能边界搞混：

| 维度 | recovery（断线续传） | resume（恢复执行） | takeOver（顶掉） |
| --- | --- | --- | --- |
| 触发方式 | 前端显式带 `recovery=true` | 同会话发消息且存在快照 | 同会话发消息且流在跑 |
| 解决的问题 | 连接断了，执行没断 | 任务停了，现场存了 | 用户改主意了，旧回答作废 |
| 新起执行？ | 否（接回进行中的流） | 是（从快照续跑） | 是（全新执行） |
| LLM 是否重调 | 否 | 空 query=否（本地重放） / 非空=是 | 是（全新任务） |
| 现场载体 | 注册表 replay 缓存（内存事件） | `agentx_pause_state` 快照（DB） | 产生快照后**立即丢弃**，现场只剩历史落库 |
| 半截/旧内容去向 | 不存在半截 | 占位剥离→重放/跳过 | 保留在历史时间线（interrupted 状态） |
| 失败语义 | 无流 404 / 非属主 403 | 无快照走全新执行 | 停不下来 409 拒绝 |

一句话记忆：**recovery 管“连接的生死”，resume 管“任务的生死”，takeOver 管“意图的生死”——顶掉 = 以 interrupt 的方式杀死旧任务的现场、以全新执行的方式开始新任务**。

---

## 12. 参考实现索引（本文代码 ↔ 本项目源码对照）

| 步骤 | 源文件 | 关键位置 |
| --- | --- | --- |
| 判定 + 四步编排 | `data-agent-server/.../controller/AgentController.java` | 常量 L50-51、stream() ②分支 L86-89、`takeOverActiveStream()` L150-166 |
| 409 真实状态码透出 | `data-agent-server/.../common/GlobalExceptionHandler.java` | `handleResponseStatus()` L109-114 |
| 注册表摘除（key+value） | `data-agent-server/.../stream/InMemoryLiveStreamRegistry.java` | `register()` doFinally L41-48、`isActive()` L68-70 |
| interrupt 定位与切断 | `mini-agentx-core/.../agent/AgentTaskManager.java` | `interrupt()` L80-93、`registerTask()` putIfAbsent L48-53 |
| 打断收尾链（快照 save / 半截历史落库 / PAUSED） | `mini-agentx-core/.../agent/AgentLoopExecutor.java` | `run()` 注册 L162-181、`onInterrupt()` L639-681（save L669-671、persistTerminal L675、finishSink L678）、`buildSnapshot()` L706-715 |
| 门面薄封装 | `mini-agentx-core/.../agent/ReactAgent.java` | `interrupt()` L135-137、`discardInterruptedState()` L156-158 |
| 业务接入层 | `data-agent-server/.../agent/DodoAgent.java` | `interrupt()` L122-124、`discardInterruptedState()` L136-138（共享单例注入模式） |
| 前端防抖窗口 | `frontend/src/views/ChatView.vue` | `send()` L786-789、Tooltip L267 |
| 前端 PAUSED / 身份守卫 / 409 | 同上 | PAUSED L577-582、`applyPausedState()` L596-610、409 分支 L624-630、`finishStream()` L668-675、`markConversationIdle()` L677-683 |
