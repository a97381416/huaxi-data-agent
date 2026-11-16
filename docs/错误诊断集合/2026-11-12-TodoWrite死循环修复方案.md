# TodoWrite 死循环事故修复方案

- **日期**：2026-11-12
- **状态**：评审通过，已实施（2026-11-12；验证：mini-agentx-core 120/120 测试通过，data-agent-server 编译通过）
- **事故会话**：conversation `dodo_conv_f20d3687-0709-4d9f-839a-2f3ae2d8d668` / session `1881638491263074559`
- **关联文档**：《Agent 追踪观测台与排查方法论沉淀方案》（同目录）

---

## 一、事故概要

用户在 15:58 发起"重新帮我提交'人事管理'的资料"（从中断快照恢复的会话），Agent 连续运行 78 轮（round 2–79）、约 7 分钟，其中 **75 轮只会调用 TodoWrite、4 轮 context_reload、0 次业务工具调用**，累计消耗约 270 万 prompt token，最终由用户手动打断（`agentx_pause_state` 记录 16:05:39 USER_INTERRUPT）才停止。系统的 `maxRounds=100` 兜底未触发。

## 二、根因链（已查实，含证据与代码定位）

一句话：**模型"知道"该调 listMyDrafts，但本次请求的工具清单里没有它，也永远不会再去发现它——结构性陷阱 + 弱模型复读**。

### 2.1 证据链

| # | 证据 | 出处 |
|---|---|---|
| 1 | round 2 的 think 写明"我需要先 listMyDrafts 看现状"，实际发射的却是 TodoWrite；round 59 的 think 写明"我陷入了 TodoWrite 的重复循环……先调 listMyDrafts"，同一轮发射的仍是 TodoWrite；round 3 起 think 全空，纯复读 | `agentx_trace.think` / `output_data` |
| 2 | 78 轮调用分布：TodoWrite×75、context_reload×4、业务工具×0、tool_search×0、Skill×0 | `agentx_trace.output_data` 逐轮解析 |
| 3 | 会话恢复时历史（182 条消息）中已有 8 次 tool_search、3 次 listMyDrafts、11 次 fillFileEntry 的成功记录 | round 2 `input_data` |
| 4 | 每轮 TodoWrite 均无条件返回"任务列表已成功更新。请使用任务列表跟踪你的进度，并继续执行当前的任务" | `input_data` 中 tool 结果 |
| 5 | 终态为用户手动打断，非系统兜底 | `agentx_pause_state`（round 80, LLM_STREAMING） |

### 2.2 结构性缺陷（代码定位）

1. **延迟工具发现态按请求隔离，但对话历史跨请求保留**——矛盾源头
   - `AgentLoopExecutor.java:197`：每个请求新建空的 `deferredSession`（"发现的工具请求内跨轮累积、请求间隔离"）；
   - resume 时消息链从快照完整恢复，历史里大量延迟工具的调用记录让模型认定"已发现、可直接调"；
   - 而系统提示词（`system-prompt.md:43`）"已发现的工具不要重复搜索"、`ToolSearchTool.java:36`"无需重复发现"两边都在阻止模型再调 tool_search → 78 轮零 tool_search，24 个业务工具全程不可见。
2. **常驻工具只有 2 个**（`DataAgentToolsConfig.java:35-40`：lookupGlossary + TodoWrite）。模型想调的工具不在下发清单，MiniMax 侧工具调用受清单约束发不出来（78 轮从未出现"不存在名为 listMyDrafts 的工具"错误，证明从未发射成功），唯一能"表达推进"的就剩 TodoWrite。
3. **TodoWrite 是无失败信号的永动机**（`TodoWriteTool.java:48`）：无状态、永远成功、永远回"请继续执行当前的任务"——持续正反馈推动弱模型（MiniMax-M3.1-Flash）复读；上下文堆到 50+ 对"TodoWrite→继续"后发生模式坍缩，reasoning 与 tool-call 发射脱钩（证据 1）。
4. **内核无任何循环检测**：`ToolCallExecutor` / `AgentLoopExecutor` 对"同名工具连续调用 75 次"毫无感知，唯一护栏 `maxRounds=100`（`application.yml:63`）。
5. **放大器**：
   - 输出纪律（`system-prompt.md:76-79`）禁止中间轮正文 → 模型没有"停下来问用户"的出口；
   - 上下文压缩只压历史区、保护最近尾巴（`ContextCompactor` L1/L2/L4）→ 循环模式本身被完整保留并自我强化；模型 4 次 context_reload 翻旧档依旧无工具可调，回到循环。

## 三、修复方案（按优先级）

### Fix A（P0）：内核循环熔断器 LoopGuard

**位置**：`mini-agentx-core`（内核层，对所有 Agent 生效）。

**机制**（复用现有 forceFinal 通道，不新增终止路径）：

| 级别 | 触发条件 | 动作 |
|---|---|---|
| WARN | "纯单工具轮"（该轮全部 call 均为同一工具 X）累计第 **3** 次 | 照常执行，工具结果末尾追加系统提示：`[系统提示] 工具X已连续被调用N次且未产生新进展。请检查：①是否陷入循环；②想用的工具不在清单时先 tool_search 发现；③无法推进时直接基于已有信息作答` |
| ESCALATE | 累计第 **5** 次 | 更强硬提示 + 明示"再重复将强制收口" |
| BREAK | 累计第 **7** 次 | 跳过执行、注入熔断响应（`[系统熔断] 工具X已连续调用N次，本轮不再执行。请直接基于已有信息给出最终结论`），走 `scheduleRound(true)`（`AgentLoopExecutor.java:717-726` 现有 maxRounds 同款路径）逼出最终答案 |

**关键设计点**：
- 计数**按工具名分别累计，不因其他工具穿插而重置**——本次事故中 context_reload 在 round 31/64 穿插后又回到 TodoWrite，严格"连续"计数会被绕过；
- WARN 注入本身是甄别器：正常工作流（如连续 executeSql 的合法分析）被提示后会调整行为，复读型循环不会——因此 BREAK 阈值可以放宽，误杀风险低；
- 熔断发生时 `log.warn` 落日志、工具结果带标记（供观测台统计，见关联文档）。

**评审决策点**：
1. 阈值 3/5/7 是否合适？
2. 是否需要豁免名单（如 `executeSql` 类合法高频工具，yml 可配）？
3. BREAK 行为：forceFinal 逼答案（推荐） vs 直接硬停？

### Fix B（P0）：历史延迟工具自动重登记（消除结构性陷阱）

针对根因 2.2-1（发现态与历史的断裂）。**一句话：请求开始时，扫一遍将要发给模型的历史消息，把里面出现过的延迟工具名重新登记进本请求的 `deferredSession`——让系统账本追上模型认知。**

#### B.1 背景：延迟工具发现的两套"账本"

工具分两层（M7 设计）：

- **常驻工具**：每轮下发，仅 2 个——`lookupGlossary` + `TodoWrite`；
- **延迟工具**：其余 24 个业务工具（listMyDrafts、fillFileEntry、submitLedgerForReview……）默认不下发，模型先调 `tool_search`"发现"，下一轮才可调用（省上下文）。

发现状态记在哪是关键（`AgentLoopExecutor.java:197`）：

```java
this.deferredSession = deferredRegistry != null ? deferredRegistry.newSession() : null;
```

每构造一个 `AgentLoopExecutor`（= 每处理一次用户请求）就 `newSession()`——发现记录装在 Session 对象里，**随请求生、随请求死**。每轮下发清单由它决定（`buildRoundTools()`，`AgentLoopExecutor.java:494`）：

```
本轮可调用 = 常驻工具 + deferredSession 已发现 + tool_search + context_reload
```

而模型"知道哪些工具存在"靠的是**另一本账**：对话历史消息（里面留着当时的 tool_search 结果、工具调用与返回）。两本账本该一致，实际上不一致——这就是陷阱。

#### B.2 陷阱逐帧回放（本次事故）

| 时刻 | 系统的账本（deferredSession） | 模型的认知（来自历史消息） |
|---|---|---|
| 前几轮会话（15:08–15:56） | tool_search 发现了 listMyDrafts 等 → 可调用 → 调用成功 | "我调过 listMyDrafts，它可用" ✅ 记进历史 |
| 本次请求 15:58（resume 恢复） | **newSession()，账本清空**。清单只剩 lookupGlossary、TodoWrite、tool_search、context_reload | 历史从 pause 快照完整恢复，模型看着旧记录依然认为"已发现，可直接调" ❌ |

再补一刀：系统提示词（`system-prompt.md:43`）"已发现的工具不要重复搜索"、`ToolSearchTool` 说明"无需重复发现"——模型守规矩，所以 78 轮里**一次 tool_search 都没调**。死锁成型：

```
模型想调 listMyDrafts（认知里有）
   → 但它不在本轮下发清单里，MiniMax 侧根本发不出这个调用
   → 唯一能"表达推进"的常驻工具只剩 TodoWrite
   → TodoWrite 永远返回成功 + "请继续执行当前的任务"
   → 复读 75 轮
```

> 影响面不只"中断恢复"：**同一 conversation 的普通多轮追问一样中招**——新请求 `initMessages()` 加载历史，deferredSession 同样为空，历史里只要出现过业务工具调用，模型就会跳过 tool_search 直接想调。resume 只是最易复现场景。

#### B.3 改动内容（两小块）

**① `DeferredToolRegistry.Session` 新增 `restore`**（目前 discoveredNames 只有 `search()` 一条写入路径）：

```java
/** 恢复登记：把历史消息中出现过的工具名并入本请求的发现集（仅登记注册表中仍存在的名字） */
public synchronized void restore(Collection<String> names) {
    for (String name : names) {
        if (DeferredToolRegistry.this.get(name) != null) {  // 已下线/改名的工具静默跳过
            discoveredNames.add(name);
        }
    }
}
```

**② `AgentLoopExecutor` 两个初始化入口各加一段扫描**（`initMessages()` 与 `initMessagesFromResume()` 末尾调用）——**有限名单 × 全文本匹配为主，结构化字段为补充**（为什么必须含文本匹配，见 B.4）：

```java
private void restoreDiscoveredToolsFromHistory() {
    if (deferredRegistry == null) return;
    List<String> candidates = deferredRegistry.names();   // 有限名单（当前 24 个延迟工具）
    Set<String> hit = new LinkedHashSet<>();
    for (Message m : messages) {                          // 此时 messages = 历史 + 本轮 user 消息
        // ① 文本匹配：模型可见文本（正文/工具结果数据）——覆盖 L1 压缩标记、
        //    L4 摘要提及、context_reload 取回的原文（这三者均无结构化字段，见 B.4）
        String text = visibleTextOf(m);
        if (text != null && !text.isEmpty()) {
            for (String name : candidates) {
                if (text.contains(name)) hit.add(name);
            }
        }
        // ② 结构化字段补充（未压缩区域最精确）：assistant toolCalls + tool 响应名
        if (m instanceof AssistantMessage am && am.getToolCalls() != null) {
            am.getToolCalls().forEach(tc -> hit.add(tc.name()));
        } else if (m instanceof ToolResponseMessage trm) {
            trm.getResponses().forEach(r -> hit.add(r.name()));
        }
    }
    // 常驻工具和内核工具天然在清单里，restore 只对延迟注册表里的名字生效
    deferredSession.restore(hit);
    if (!hit.isEmpty()) log.info("从历史恢复延迟工具发现态: {} 个 {}", hit.size(), hit);
}
```

**③ 兜底文案**：resume 澄清语模板（`AgentLoopExecutor.java:279`）追加"工具发现状态已重置，历史中用过的延迟工具已自动恢复可用"——万一 restore 漏了什么，模型也知道该 tool_search。

**效果**：本事故会话若打了此补丁，resume 后第一轮可用清单即含 listMyDrafts / fillFileEntry / submitLedgerForReview 等（从历史恢复）——round 2 的"我需要先 listMyDrafts"真的能调出去，循环在第一轮就断了。

#### B.4 压缩场景的完整性（评审补充）：为什么扫描必须包含文本匹配

**恢复不变量**：恢复的依据是"**模型当前还看得见什么**"，而不是"历史上发生过什么"——名字在模型可见内容里出现过才恢复，否则不恢复。

历史被压缩后，工具记录存在三种形态：

| 情况 | 结构化字段（toolCalls / tool 响应名） | 模型还能看见工具名吗 | 恢复策略 |
|---|---|---|---|
| ① 未压缩 / L2 大消息替换 | 保留（L2 对 tool 消息是"**原地内容替换**"，name 字段不动，`ContextCompactor.java:36`） | 能 | 结构化扫描即可覆盖 |
| ② **L1 工具段压缩** | 整段移除 | **能**——L1 替换模板刻意保留"**工具名 + args 前 100 字 + 结果预览 + uuid**"（`ContextCompactor.java:170`），名字以纯文本存活 | 仅结构扫描会**漏掉**，必须文本匹配 |
| ③ L4 摘要完全未提及该工具 | 没了 | 不能 | 不恢复（模型也不知道它，无陷阱） |

另有两条旁路同样让工具名以纯文本存活，结构化扫描均覆盖不到：

- **L1 压缩标记**：一行摘要里带着工具名 + offload uuid——模型看得见、结构扫描扫不到；
- **context_reload 取回**：模型把 offload 原文取回来（本事故 round 31/33/64 共 4 次），返回的原文（上限 32K 字符）里全是旧工具调用记录——这些内容是 context_reload 的**文本结果**，不是结构化消息。

因此 B.3 的扫描设计为"**有限名单（注册表全部工具名）× 每条消息可见文本 contains 匹配 + 结构化字段补充**"：不解析 L1 模板格式（无格式耦合）、无解析歧义，且严格满足恢复不变量。方向性风险安全——**过度恢复无害**（工具只是进入下发清单，用不用仍受提示词约束，与当初被合法发现时状态相同），**漏恢复才危险**（即本次事故）。

#### B.5 常见疑问

| 疑问 | 回答 |
|---|---|
| 为什么不改提示词，让模型自己重新 tool_search？ | 提示词在和模型感知打架——它看着历史里活生生的成功调用记录，弱模型守不住"那不算"的指令；即使信了还得凭空猜关键词、多烧几轮。Fix B 让现实顺从认知，一步到位 |
| 恢复历史里所有用过的工具，会破坏"工具组隔离"吗？ | 隔离规则（"只用本技能可用工具表内的工具"）本来就靠提示词约束而非下发清单强制，恢复前后约束机制不变；恢复后的清单与这些工具当初被合法发现时状态相同，无新增越权面 |
| 名单 contains 匹配会不会误恢复（如用户消息里恰好提到某工具名）？ | 恰好说明模型也"看得见"该名字、也可能想调它——恢复反而与认知对齐；误恢复只影响下发清单大小，不改变提示词层的使用约束 |
| 成本/风险？ | 扫描为一次内存遍历（24 个名字 × 消息数，可忽略）；每轮多下发几个工具 schema（几百 token）远小于循环一轮的 3.4 万 prompt token；已下线/改名工具被 `get(name) != null` 过滤，不报错 |

### Fix C（P1，可选）：TodoWrite 返回话术中性化

`TodoWriteTool.java:48` 去掉"请继续执行当前的任务"的催促，改为："任务列表已更新。仅当某项状态真正变化时才需再次调用。"（一行改动，与系统提示词"todoWrite 纪律"呼应。）

### Fix D（P2，一行配置）：max-rounds 调低

`application.yml:63` `max-rounds: 100 → 40`。本次事故烧 270 万 token 才被用户肉眼发现，100 轮过于宽松。

### Fix E（P1）：context_reload 空回包——工具类消息渲染丢失（评审补充新增）

**现象**：本次事故 4 次 context_reload 的实际回包（trace 实测）：

| 轮次 | uuid | 回包 | 长度 |
|---|---|---|---|
| 31 | `b979244c`（25 条历史调用） | ✅ 有内容——但是一段 L1 压缩摘要文本，开头仍指向另一个 uuid（摘要套摘要） | 6646 字符 |
| 31 | `cad987c5`（72 条历史调用） | ❌ 144 个换行符，全空白 | 144 |
| 33 | `cad987c5`（重试同一 uuid） | ❌ 仍 144 个换行 | 144 |
| 64 | `08eca1f2` | ❌ 1 个换行符 | 1 |

模型 round 31 的 think 明确要"取回中断前的提审尝试结果"，结果 3 次空白、1 次拿到指向别处的摘要索引，一次都没取到真正想要的原文。

**根因**：`ContextReloadTool.java:60-63` 拼接归档消息时只读 `m.getText()`，而归档内容恰恰以工具交互为主，这类消息 getText() 天生为空：

- **工具调用轮 AssistantMessage**：content 按输出纪律必须为空（`system-prompt.md:76-79`），真实内容在 `getToolCalls()`；
- **ToolResponseMessage**：结果数据在 `getResponses()[].responseData()`，getText() 不承载。

于是"归档加载成功、渲染全部丢光"，每条消息只剩 `append('\n')`。数字严格印证：`cad987c5` = 72 次工具调用 = 72 assistant + 72 tool 响应 = **144 条消息 → 144 个换行**，与回包长度一致；`08eca1f2` 是 L2 归档的单条大消息（很可能正是模型最需要的那条大 listMyDrafts 结果）→ **1 个换行**；`b979244c` 之所以有内容，是因为它归档的是带文本的 L1 摘要消息（二代压缩产物），返回的自然还是摘要而非原文。

**危害**：空白 ≠ 报错（success=1、无"未找到"文案），模型无法区分"归档为空"与"工具坏了"——round 33 原样重试同一 uuid 即典型 retry-on-blank。静默失败比直接报错更浪费轮次，并把模型进一步推回 TodoWrite 循环。

**修复**：

1. **渲染按消息类型展开**（核心）：AssistantMessage 的 toolCalls → `调用 name(args)`、正文/reasoning 非空则拼；ToolResponseMessage → `[toolName] responseData`；其余消息照旧 getText()。渲染器落在 mini-agentx-core（新增如 `MessageTextRenderer` 工具类）；`data-agent-server` 的 `TraceQueryService.segments()`（`TraceQueryService.java:286-311`，四段式渲染的正确范本）后续可选改造为复用该渲染器（依赖方向：server → kernel，不能反向）；
2. **空内容防御**：渲染结果若仍为空白，返回明确文案（如"归档找到 N 条消息但渲染后为空内容"），杜绝"成功 + 空白"这种最迷惑的失败形态。

## 四、改动文件清单

| 文件 | 改动 |
|---|---|
| `backend/mini-agentx-core/src/main/java/com/llmetor/miniagentx/agent/LoopGuard.java` | **新增**：判定逻辑（纯函数，便于单测） |
| `backend/mini-agentx-core/src/main/java/com/llmetor/miniagentx/agent/AgentLoopExecutor.java` | `finishRound` 接入 WARN/BREAK 分流；`initMessages`/`initMessagesFromResume` 接入重登记 |
| `backend/mini-agentx-core/src/main/java/com/llmetor/miniagentx/agent/ReactAgent.java` | Builder 新增 `loopGuard(...)` 配置透传（构造参数已 19 个，打包为小 config 对象传入） |
| `backend/mini-agentx-core/src/main/java/com/llmetor/miniagentx/tools/search/DeferredToolRegistry.java` | `Session.restore()` |
| `backend/data-agent-server/src/main/java/com/llmetor/dataagent/dataagent/agent/DodoAgent.java` | 读 yml loop-guard 配置传入 builder |
| `backend/data-agent-server/src/main/resources/application.yml` | 新增 `data-agent.agent.loop-guard` 配置段；`max-rounds` 调整 |
| `backend/data-agent-server/src/main/java/com/llmetor/dataagent/dataagent/tools/TodoWriteTool.java` | 返回文案（Fix C） |
| `backend/mini-agentx-core/src/main/java/com/llmetor/miniagentx/context/ContextReloadTool.java` | 取回渲染按消息类型展开 + 空内容防御（Fix E） |
| `backend/mini-agentx-core/.../utils/MessageTextRenderer.java`（或同等位置） | **新增**：按消息类型渲染工具类消息的共享渲染器（Fix E，TraceQueryService 后续可选复用） |

**配置段示例**：

```yaml
data-agent:
  agent:
    max-rounds: 40
    loop-guard:
      enabled: true
      warn-after: 3        # 纯单工具轮累计 N 次注入警告
      escalate-after: 5    # N 次强警告
      break-after: 7       # N 次熔断进 forceFinal
      ignore-tools: []     # 豁免名单（如 executeSql）
```

## 五、测试计划

1. **LoopGuard 单测**：阈值触发、穿插不重置、豁免名单、disabled 透传；
2. **熔断集成测试**：用 `AgentLoopExecutor.chatModelStream` 可覆写钩子（`AgentLoopExecutor.java:461`，测试注入 scripted 模型响应）模拟连续 TodoWrite，断言 WARN 注入文本、BREAK 后走 forceFinal 终态；
3. **重登记单测**：构造含延迟工具调用的历史，断言 resume/续问后 roundTools 包含该工具；另补压缩场景用例——构造含 L1 压缩标记文本（"工具名 + uuid 取回指引"）与 context_reload 文本结果的历史，断言仅靠文本匹配也能恢复；构造完全不提及某工具的 L4 摘要历史，断言该工具不被恢复（恢复不变量）；
4. **回归**：正常多轮会话（tool_search → 业务工具 → 完成）不受影响；
5. **context_reload 渲染单测**（Fix E）：归档一段纯工具交互（assistant(toolCalls) + ToolResponseMessage），断言取回结果包含工具名与结果数据；归档渲染后为空时断言返回明确文案而非空白（复演 `cad987c5` 144 换行用例）。

## 六、评审决策点汇总（2026-11-12 评审通过）

| # | 决策项 | 评审结论 |
|---|---|---|
| 1 | LoopGuard 阈值 | ✅ WARN 3 / ESCALATE 5 / BREAK 7 |
| 2 | 豁免名单 | ✅ **executeSql** 直接进白名单（yml `ignore-tools`，后续可调） |
| 3 | BREAK 行为 | ✅ forceFinal 逼答案 |
| 4 | max-rounds | ✅ **50**（评审调整，原建议 40） |
| 5 | Fix C 是否做 | ✅ 做 |
| 6 | Fix E 是否纳入本批 | ✅ 纳入 |

## 七、实施记录（2026-11-12 完成）

**新增文件**：
- `mini-agentx-core/.../agent/LoopGuardConfig.java`——熔断配置 record（defaults=3/5/7 / disabled）
- `mini-agentx-core/.../agent/LoopGuard.java`——熔断器：纯单工具轮按名累计、穿插不重置、豁免名单、WARN/ESCALATE/BREAK 文案
- `mini-agentx-core/.../utils/MessageTextRenderer.java`——消息按类型展开渲染（思考/正文/工具调用/工具结果），供 Fix E 取回渲染与 Fix B 历史扫描共用
- 测试：`LoopGuardTest`、`LoopGuardForceFinalIntegrationTest`（7 轮 echo→BREAK→forceFinal 全链路）、`RestoreScanTest`、`MessageTextRendererTest`

**修改文件**：
- `AgentLoopExecutor.java`：M7 构造器 +`LoopGuardConfig` 参；finishRound 接熔断评估（BREAK→注入熔断响应+forceFinal；WARN/ESCALATE→工具结果追加提示）；`initMessages`/`initMessagesFromResume` 末尾接 `restoreDiscoveredToolsFromHistory()`（有限名单 × `MessageTextRenderer` 全文本匹配，含 L1 标记/思考/结构化字段）；resume 澄清语加"延迟工具已自动恢复可用"
- `ReactAgent.java`：Builder `.loopGuard(config)`（默认 defaults 开启）
- `DeferredToolRegistry.java`：`Session.restore(names)`（过滤注册表存在性、幂等、与 search 互通）
- `ContextReloadTool.java`：取回渲染改走 `MessageTextRenderer`，渲染后全空白返回明确文案（Fix E）
- `TodoWriteTool.java`：返回文案中性化（Fix C）
- `DodoAgent.java`：读 yml loop-guard 五项配置传入 builder（默认 enabled=true，ignore-tools 默认 executeSql）
- `application.yml`：`max-rounds: 100→50`；新增 `loop-guard` 配置段（enabled/warn-after 3/escalate-after 5/break-after 7/ignore-tools executeSql）
- 测试扩展：`DeferredToolRegistryTest`（restore 3 用例）、`ContextReloadToolTest`（工具交互归档渲染回归 + 空白防御 2 用例）

**验证**：`mvn -pl mini-agentx-core test` → 120/120 通过；`mvn -pl data-agent-server -am test-compile` → 编译通过（server 测试依赖内网环境未跑）。

**遗留跟进**：
- 观测台（Phase 1/2）见关联文档，未实施
- 熔断/恢复的线上观测：`[loop-guard]` / `[tool_search] 从历史恢复` 日志关键字可直接 grep 排查
