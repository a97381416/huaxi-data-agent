agentscope中、我想落地主agent + plan-and-execute + 业务子agent的架构、主agent是否需要编排计划和维护计划的状态？计划生成交给单独的agent的？



搜索到 15 个网页

在 AgentScope 中落地“主 Agent + Plan-and-Execute + 业务子 Agent”架构，**主 Agent 应当维护计划状态，但不一定要自己生成计划；把计划生成委托给独立的 Planner Agent 是更清晰、也更符合职责分离原则的做法。**

AgentScope 本身提供了两套可组合的机制来支撑这个架构：一套是计划管理工具（PlanNotebook 或 Task 工具），另一套是子 Agent 的 spawn 机制。你可以根据任务复杂度和团队偏好，选择让主 Agent 自己规划，或者引入独立的 Planner。

### 主 Agent 是否应该维护计划状态？

**建议由主 Agent 维护，但维护的是“外部化的计划对象”，而不是藏在模型推理里的隐性计划。**

AgentScope 的设计理念是把计划从“模型的思考过程”变成 Harness 和用户都能读取、修改、验证的控制对象。具体落地时，主 Agent 通过工具调用来读写计划状态：

- **PlanNotebook 路径**：`PlanNotebook` 提供 `create_plan`、`revise_current_plan`、`update_subtask_state`、`finish_subtask` 等工具。主 Agent 在推理循环中调用这些工具，计划状态会随 Agent 状态一起持久化，跨调用可恢复。
- **Task 工具路径**：AgentScope 也内置了 `TaskCreate`、`TaskList`、`TaskGet`、`TaskUpdate` 四个工具，任务清单存在 `agent.state.tasks_context` 中，同样可序列化保存。

由主 Agent 维护计划状态的好处是：计划状态与主 Agent 的执行上下文天然绑定，子 Agent 的调用结果可以直接反馈到计划项的完成/失败判定上，不需要额外的状态同步层。

### 计划生成是否应该交给单独的 Agent？

**可以，而且对于复杂业务场景推荐这样做。** AgentScope 支持两种模式：

**模式一：主 Agent 自己规划（轻量、简单）**

主 Agent 同时持有计划工具和子 Agent 的 `agent_spawn` 能力。它在 ReAct 循环中先调用 `create_plan` 拆解任务，然后逐步调用子 Agent 执行，期间用 `update_subtask_state` 更新进度。AgentScope 的 `PlanNotebook` 默认就是这种用法——模型自己创建和管理计划。适合任务结构相对标准、规划逻辑不复杂的场景。

**模式二：独立 Planner Agent 生成计划，主 Agent 执行与维护（推荐用于复杂业务）**

AgentScope 的架构本身支持将“探索、计划和执行分开”。你可以构建一个专门的 Planner 子 Agent（或独立的 Agent 实例），它只拥有只读工具和计划工具，负责输出结构化的计划；主 Agent 拿到计划后，负责调度业务子 Agent 执行，并在执行过程中维护计划状态（标记完成、触发重规划等）。

这种分离在 AgentScope 中的落地方式是：

- Planner 可以作为一个普通的 Agent（甚至可以是主 Agent 通过 `agent_spawn` 调用的一个子 Agent），其输出是一份结构化的计划。
- 主 Agent 用 `PlanNotebook` 或 `Task` 工具把这份计划“导入”或“预置”为当前执行计划。AgentScope 明确支持“用其他渠道（另一个智能体、工作流引擎）生成的现成计划喂给智能体”的预置用法。
- 之后主 Agent 持有计划状态，按计划 spawn 业务子 Agent，并根据子 Agent 返回结果更新计划项状态。

独立 Planner 的额外好处是：Planner 可以运行在只读模式下（AgentScope 的 Plan Mode 正是这种设计），避免在规划阶段就产生写操作或副作用；同时 Planner 的 prompt 可以专注于“拆解质量”，而不被执行细节干扰。

### 落地时的两个关键约束

**第一，子 Agent 的 `description` 就是路由表。** 主 Agent 完全靠子 Agent 声明中的 `description` 来判断“该不该调这个子 Agent、什么时候调”。业务子 Agent 的描述必须写得让主 Agent 能准确路由。

**第二，计划需要允许修订，且要有阶段门禁。** AgentScope 的实践建议是：每次重规划都应说明触发事实并保留已完成项，不能通过改写目标掩盖失败。如果业务涉及写操作或高风险动作，可以利用 Plan Mode 的 HITL（Human-in-the-loop）确认机制，让计划在获批后才进入执行阶段。

### 推荐架构形态

text

```
用户请求
    ↓
主 Agent（持有计划状态 + agent_spawn 能力）
    ├── 调用 Planner 子 Agent（只读/规划模式）→ 获得结构化计划
    ├── 将计划导入 PlanNotebook / Task 状态
    ├── 按计划 spawn 业务子 Agent（同步或异步）
    ├── 根据子 Agent 结果更新计划状态
    └── 必要时触发重规划（再次调用 Planner 或自己 revise）
```



如果你的业务子 Agent 种类多、任务拆解逻辑复杂且需要审计规划质量，引入独立 Planner 是值得的；如果任务相对标准、迭代速度优先，让主 Agent 用 `PlanNotebook` 自己边规划边执行也能跑通，后续再拆分 Planner 并不困难。



你看看这篇文章、它不是主agent，它是一个子agent，但是这个子agent还有任务编排的能力。还支持调用其他业务agent

你观察得没错，这篇文章描述的确实是一个**“带编排能力的业务子 Agent”**，而不是我们上一轮讨论的那种“主 Agent + 独立 Planner + 业务子 Agent”的三层结构。它的形态是：**ItineraryPlanAgent 本身是一个被主流程调用的子 Agent，但它内部又完整地实现了 Plan-and-Execute 循环，并且还能再委托 `itinerary_review_agent` 这个业务子 Agent。** 这对应 AgentScope 里一种很自然的嵌套模式，值得把它和上一轮的架构放在一起对照。

### 这篇文章的 Agent 在架构上处于什么位置

从时序看，ItineraryPlanAgent 是被上游调用的（它接收“规划上海到杭州出差”这个任务），所以它对外是一个子 Agent；但它内部持有 PlanNotebook、自己 `create_plan`、自己推进子任务、自己 `revise_current_plan`，并且在子任务 5 里调用 `itinerary_review_agent`。也就是说：

- **对外**：它是业务子 Agent，由上层主 Agent 通过 `agent_spawn` 或工具调用拉起；
- **对内**：它是这个子领域内的“主 Agent”，拥有计划状态、任务编排和再委托能力。

这正好说明 AgentScope 的 PlanNotebook 和子 Agent 机制是**可嵌套、可组合**的：一个 Agent 是否“主”或“子”取决于它在调用链中的位置，而不取决于它是否持有计划能力。上一轮我说的“主 Agent 维护计划状态”，在嵌套场景下应理解为**每一层编排者维护自己那一层的计划状态**——外层主 Agent 管跨领域的大计划，ItineraryPlanAgent 管行程规划这个领域内的小计划。

### 它为什么这样设计是合理的

这篇文章的场景是“行程规划”这个边界清晰、步骤多、有回退修复需求的领域任务。把它做成一个自带 Plan-and-Execute 的子 Agent，有几个实际好处：

**第一，计划粒度与领域边界对齐。** 行程规划内部的 7 个子任务（查差旅单、召回偏好、搜交通酒店、打分组合、审核、修复、输出）彼此依赖紧密，而且修复循环（审核不通过 → `revise_current_plan` → 重跑 plan_itinerary → 再审核，最多 2 轮）是领域内的局部回退。如果把这些细节全部摊到外层主 Agent 的计划里，外层计划会被大量领域细节淹没，主 Agent 的上下文和计划可读性都会变差。封装成子 Agent 后，外层只需知道“调 ItineraryPlanAgent 拿行程方案”，领域内的计划状态由它自己维护。

**第二，PlanAwareThinkingHook 这类优化只在领域内才有意义。** 它用 `planNotebook.getCurrentPlan() == null` 来判断“还在规划阶段”，从而在规划时开 thinking、执行时关 thinking、总结时再开。这个判断依赖“计划创建”这个明确的分界点，放在领域子 Agent 内部非常自然；如果放到外层主 Agent，主 Agent 自己也有计划，这个 hook 的语义就会混乱。

**第三，审核子 Agent 的再委托是领域内的职责。** `itinerary_review_agent` 做五维校验，是行程方案质量的专门检查者，由 ItineraryPlanAgent 在子任务 5 里拉起、根据审核报告决定是否 `revise_current_plan`，这条“生成—审核—修复”闭环完全在领域内闭合，不需要外层主 Agent 介入。

### 与上一轮“主 Agent + 独立 Planner”架构的关系

两者并不矛盾，而是**不同层级的同一种模式**。可以这样理解整体形态：

text

```
外层主 Agent（跨领域编排，持有外层计划状态）
    ├── 调用 Planner 子 Agent（可选，生成跨领域计划）
    ├── spawn ItineraryPlanAgent（行程领域子 Agent，内部自带 Plan-and-Execute）
    │       ├── PlanNotebook（领域内计划状态）
    │       ├── 业务工具（差旅单、偏好、政策、搜索、plan_itinerary）
    │       └── spawn itinerary_review_agent（领域内审核子 Agent）
    ├── spawn 其他领域子 Agent（如报销、审批等）
    └── 根据各领域子 Agent 结果更新外层计划
```



上一轮我强调“主 Agent 维护计划状态、计划生成可交给独立 Planner”，说的是**外层跨领域编排**这一层；这篇文章展示的是**领域子 Agent 内部也可以是一个完整的 Plan-and-Execute 编排者**。AgentScope 的 `agent_spawn` 和 PlanNotebook 都支持这种嵌套，所以两种说法在同一个系统里可以同时成立。

### 落地时需要注意的边界

这种嵌套编排虽然自然，但有两个地方要提前定清楚，否则容易职责重叠：

**计划状态的归属要分层明确。** 外层主 Agent 的计划项应该是“调 ItineraryPlanAgent 完成行程规划”这种粒度，而不是把行程内部的 7 步也写进外层计划。ItineraryPlanAgent 内部用 `update_subtask_state` 推进自己的 7 步，这些状态变化通过 `addChangeHook` 推给前端做进度展示，但不应反向污染外层计划状态。

**回退修复的边界要定在领域内。** 文章里审核不通过时，ItineraryPlanAgent 自己 `revise_current_plan` 插入修复步骤，最多 2 轮。这个重试预算应该由领域子 Agent 自己控制；如果 2 轮修复仍失败，它应该向上层返回一个明确的“领域内无法修复”的结果，由外层主 Agent 决定是换方案、降级还是请用户确认，而不是让外层主 Agent 去理解行程内部哪一步坏了。

所以这篇文章的案例可以看作对上一轮架构的一个补充证据：**Plan-and-Execute 的编排能力不必只放在最外层主 Agent 上，它可以下沉到领域子 Agent 内部，形成“外层编排 + 领域内编排”的嵌套结构**，而 AgentScope 的 PlanNotebook + agent_spawn 组合正好支持这种分层。

那针对plan-and-execute的阐述、ItineraryPlanAgent 这个agent其实阐述的更好、而不是我们之前的主agent + planner的方案，是吧。

不完全是“更好”，更准确的说法是：**这篇文章对 Plan-and-Execute 的执行机制阐述得更具体、更有落地细节，但它描述的是另一种架构切分方式，不能直接拿来否定“主 Agent + Planner”的方案。** 两者解决的是不同层面的问题，优劣取决于你站在哪个层面看。

### 这篇文章真正更强的地方：执行机制的颗粒度

它在 Plan-and-Execute 的**执行侧**给出了我们上一轮没有展开的细节，这些是它明显更具体的地方：

- **计划状态机的具体工具面**：`create_plan`、`update_subtask_state`、`finish_subtask`、`revise_current_plan`、`finish_plan`、`view_subtasks`，每个工具的作用和调用时机都对应到了时序里，而不只是说“用 PlanNotebook 维护状态”。
- **规划/执行/总结三阶段的推理深度切换**：PlanAwareThinkingHook 用 `planNotebook.getCurrentPlan() == null` 判断是否还在规划阶段，规划开 thinking、执行关 thinking、总结再开。这是一个很具体的“按阶段动态调整推理开销”的实现，上一轮只提到“执行阶段子任务内仍是 ReAct”，没有落到 thinking budget 这个层面。
- **修订闭环的真实触发路径**：审核子 Agent 返回“方案 2 超预算” → `revise_current_plan` 插入修复步骤 → 剔除违规候选 → 重新打分 → 重新 `plan_itinerary` → 重新审核，最多 2 轮。这把“计划可变”从一句原则变成了可追踪的控制流。
- **计划状态变化的外推**：`addChangeHook` 把计划状态实时推给前端做进度展示，这是“计划显式持久化”带来的用户体验收益的具体兑现。

所以在“Plan-and-Execute 到底怎么跑起来”这个问题上，这篇文章的阐述确实比我们上一轮的讨论更扎实。

### 但它没有否定“主 Agent + Planner”的理由

上一轮“主 Agent + 独立 Planner”讨论的是**编排职责的切分**：谁生成计划、谁维护计划状态、谁调度业务子 Agent。这篇文章的 ItineraryPlanAgent 并没有回答“计划该由谁生成”这个切分问题，它只是**在一个领域子 Agent 内部把 Plan-and-Execute 跑全了**，而且它的计划是 LLM 自己 `create_plan` 出来的，不是外部 Planner 喂进来的。换句话说：

- 它证明的是“**领域子 Agent 内部可以自带完整的 Plan-and-Execute**”，这是对上一轮“嵌套编排”判断的佐证；
- 它没有证明“独立 Planner 不必要”。在外层跨领域编排里，计划生成和维护是否分离，仍然是一个独立的设计选择，这篇文章没有触及那一层。

而且它自己也隐含了 Planner 分离的价值：它的规划阶段是“开启深度思考”单独完成的，执行阶段才关掉思考快速跑。这本质上就是**把规划当成一个独立阶段来对待**，只是这个阶段发生在同一个 Agent 实例里，而不是拆成另一个 Agent。如果规划逻辑更复杂、需要只读模式或审计规划质量，把这个阶段拆成独立 Planner 仍然是有意义的，只是这篇文章的场景还没复杂到必须拆。

### 更公允的结论

可以这样表述：

- **在“Plan-and-Execute 执行机制怎么落地”这个维度上**，这篇文章的阐述确实更好、更具体，尤其是计划状态机、分阶段 thinking 切换、审核-修复闭环这三块，值得直接吸收进你的设计。
- **在“编排职责怎么切分”这个维度上**，它没有覆盖上一轮的问题，两者不是替代关系。它展示的是“领域子 Agent 内部自带编排”这一种形态，而“主 Agent + 独立 Planner + 业务子 Agent”是外层编排的另一种切分，二者可以在同一个系统里共存：外层用主 Agent 管跨领域计划，行程这个领域用 ItineraryPlanAgent 这种自带 Plan-and-Execute 的子 Agent 承接。
- 如果非要说这篇文章在哪个点上比上一轮的方案更“对”，那就是它说明了**Plan-and-Execute 不必只放在最外层**，把编排能力下沉到领域子 Agent 内部、由它维护自己那一层的计划状态，往往比让外层主 Agent 把领域内部 7 步全写进大计划更清晰。这一点上一轮已经提过，这篇文章用完整时序把它坐实了。

所以与其说“它比主 Agent + Planner 更好”，不如说“它把 Plan-and-Execute 的执行层讲透了，并且示范了编排能力下沉到领域子 Agent 的一种合理形态”；至于计划生成是否要独立成 Planner，那是另一个层面的问题，这篇文章没有给出否定答案。

本回答由 AI 生成，内容仅供参考，请仔细甄别

我还发现一个问题、它的这个plan-and-execute中、把要调用哪个agent写死了。如果我分了很多业务、那岂不是要定制很多个plan-and-execute这样的agent

你发现的这个问题是真实的，而且它恰好点出了“领域子 Agent 自带 Plan-and-Execute”这种形态的**扩展性瓶颈**：计划步骤里硬编码了 `itinerary_review_agent` 这类具体调用，业务一多，就变成“一个领域一套 Plan-and-Execute Agent”，定制成本线性上升。

但这个问题要拆成两层看：**哪些是这篇案例里真正的硬编码，哪些是 Plan-and-Execute 本身不必然要求硬编码的**。拆清楚之后，扩展路径也就清楚了。

### 先确认：硬编码确实存在，但硬编码的层次不同

文章里的“写死”其实分布在几个不同位置，严重程度不一样：

**第一层，子任务描述里写死了要调哪个 Agent。** 时序里“子任务 5: 委托审核子智能体做五维审核 → 调用 `itinerary_review_agent`”。这看起来是硬编码，但 PlanNotebook 的子任务是 LLM 在 `create_plan` 时生成的，子任务内容取决于系统 Prompt 的引导。如果 Prompt 里只写“委托审核子智能体”，而“审核子智能体”是通过子 Agent 的 `description` 让主 Agent 路由到的，那这里其实是**软路由**——LLM 根据 `description` 选 Agent，而不是代码里写死调用谁。文章展示的是执行结果，不一定是代码层面的硬绑定。

**第二层，系统 Prompt 里固化了领域工作流。** 那 7 个子任务的顺序（查差旅单 → 召回偏好 → 查政策 → 搜交通酒店 → 打分组合 → 审核 → 修复）是行程规划这个领域的固定套路，被写进了 System Prompt。这是真正的领域定制点——换一个业务领域（比如报销、审批），这套步骤顺序就不适用了，需要另写一份 Prompt。**这一层是“领域知识”的硬编码，不是“Agent 调用”的硬编码。**

**第三层，审核-修复循环的触发逻辑写死在领域 Agent 里。** “审核不通过 → `revise_current_plan` 插入修复步骤 → 最多 2 轮”这个控制流是 ItineraryPlanAgent 自己的逻辑。换领域后，修复策略可能完全不同。

所以严格说，它硬编码的是**领域工作流和领域修复策略**，而“调哪个 Agent”如果走的是 `description` 路由，其实可以做到软绑定。但即便只是领域工作流硬编码，你的担忧也成立：业务一多，每个领域都要写一套 Prompt + 一套修复逻辑 + 一个 Plan-and-Execute Agent，定制量确实大。

### 为什么这种形态天然倾向“一领域一 Agent”

PlanAwareThinkingHook 的 `isPlanning()` 判断依赖 `planNotebook.getCurrentPlan() == null`，这个语义只有在“这个 Agent 只负责一个领域的计划”时才干净。如果让一个通用 Agent 同时管多个领域的计划，`getCurrentPlan()` 返回的是哪个领域的计划？thinking 该按哪个阶段切？这个 hook 的设计本身就假设了**计划边界 = Agent 边界 = 领域边界**。这是它简洁的来源，也是它扩展性受限的来源。

### 扩展性问题的三条解法，按侵入性从低到高

**解法一：计划生成走软路由，领域工作流不写进代码而写进“领域配置/技能”。**

把每个领域的子任务模板、可用业务 Agent 清单、修复策略做成配置（或 AgentScope 的 skill 加载机制），ItineraryPlanAgent 这类 Agent 只保留通用的 Plan-and-Execute 骨架，`create_plan` 时根据领域标识加载对应的任务模板，`revise_current_plan` 时根据配置里的修复策略决定动作。这样“一领域一 Agent”退化成“一个通用领域编排 Agent + N 份领域配置”。代价是配置本身要维护，且领域间的特殊逻辑仍可能溢出配置。

**解法二：把“领域内编排”上移一层，用外层主 Agent + 通用 Planner 统一管多领域计划。**

这就是上一轮“主 Agent + 独立 Planner”方案的扩展性优势所在：外层主 Agent 持有跨领域大计划，每个领域子 Agent 只做**单步执行**（比如“搜杭州酒店”就是一个纯工具型子 Agent，内部不需要 Plan-and-Execute），领域内的步骤顺序由外层 Planner 生成、外层主 Agent 维护。这样业务子 Agent 是**无状态的执行单元**，新增业务只是新增一个子 Agent + 在 Planner 的可用能力清单里加一行，不需要为每个领域定制一个 Plan-and-Execute Agent。代价是外层计划会变粗、领域内的局部回退（如审核-修复循环）要么上移到外层，要么由外层主 Agent 显式编排。

**解法三：混合——通用骨架下沉，领域特化只在必要时保留。**

外层主 Agent + 通用 Planner 管跨领域计划；对于像行程规划这种“内部有复杂回退闭环”的领域，保留 ItineraryPlanAgent 这种特化 Plan-and-Execute 子 Agent，但把它的领域工作流做成配置；对于简单领域（查政策、查差旅单），直接用无状态工具型子 Agent。判断标准是：**领域内是否有需要局部回退修复的闭环**——有，就值得保留一个特化编排子 Agent；没有，就退化成执行单元。

### 回到你的问题

“分了很多业务，岂不是要定制很多个 Plan-and-Execute Agent”——**如果你的设计是让每个业务领域都自带一套 Plan-and-Execute，那确实会。** 这篇文章的案例之所以能这么写，是因为它只处理行程规划一个领域，且该领域内部有审核-修复闭环，特化是划算的。但把它当成通用模板推广到 N 个业务，就会遇到你说的定制爆炸。

更合理的结论是：**Plan-and-Execute 不应该成为每个业务子 Agent 的标配，而应该是“需要领域内局部编排和回退闭环”的领域才保留的特化能力**；其余业务子 Agent 退化为无状态执行单元，由外层主 Agent + Planner 统一编排。这样既保留了行程规划这种复杂领域的局部闭环优势，又避免了“一业务一编排 Agent”的定制爆炸。这篇文章的价值在于把“特化编排子 Agent”这一形态讲透了，而不是说所有业务都该这么写。

那你说说软路由的那种方式、主agent如何与Plan-and-execute这样的通用骨架来交互、用具体的例子来举例说明。



搜索到 15 个网页

软路由的核心思路是：**主 Agent 不把“调哪个子 Agent”写死在计划步骤里，而是把子 Agent 包装成可被 LLM 自主选择的工具，或者通过 `description` 让 LLM 在运行时决定路由。** Plan-and-Execute 骨架只负责“计划结构和状态推进”，具体每一步落到哪个执行单元，由骨架在每一步执行时根据可用工具的语义动态选择。

下面用一个具体的跨业务场景来演示。

### 场景设定

假设你有三个业务子 Agent，各自独立、无状态、只做单步执行：

| 子 Agent 标识        | `description`（路由依据）                | 能力     |
| :------------------- | :--------------------------------------- | :------- |
| `travel_order_agent` | “查询已审批差旅单，获取出差约束”         | 查差旅单 |
| `preference_agent`   | “召回用户历史偏好，如酒店星级、交通偏好” | 查偏好   |
| `policy_agent`       | “查询差旅政策与报销标准”                 | 查政策   |

主 Agent 持有一个通用的 Plan-and-Execute 骨架，它**没有**把“调 travel_order_agent”写进计划模板。计划模板只描述**任务意图**：“获取出差约束条件”“召回用户偏好”“查询报销标准”。每一步执行时，骨架从可用子 Agent 的工具池中根据 `description` 语义选择匹配的那个来调用。

### 主 Agent 与骨架的交互流程

**第一步：主 Agent 接收请求，Planner 生成“领域无关”的计划**

用户说：“帮我规划下周去杭州出差，先搞清楚我的差旅单和偏好。”

主 Agent 的 Planner 组件（可以是一个轻量规划调用，也可以由主 Agent 自身在 thinking 阶段完成）生成计划：

json

```
{
  "plan_name": "杭州出差规划前置信息收集",
  "subtasks": [
    {"intent": "获取本次出差的约束条件（时间、目的地、审批状态）"},
    {"intent": "召回用户的历史出行偏好"},
    {"intent": "查询差旅政策中与杭州相关的报销标准"}
  ]
}
```



注意：子任务里**没有出现任何 Agent 名称**，只有“意图”。这是软路由的关键——计划本身是可复用的，换一套业务子 Agent 也能跑。

**第二步：骨架逐项执行，每一步动态路由**

骨架持有 PlanNotebook 和工具池。执行子任务 1 时：

- 骨架读取当前子任务意图：“获取本次出差的约束条件”。
- 骨架查看工具池中可用的子 Agent 工具，每个工具的描述是：
  - `travel_order_agent`: “查询已审批差旅单，获取出差约束”
  - `preference_agent`: “召回用户历史偏好”
  - `policy_agent`: “查询差旅政策”
- LLM 根据语义匹配，选择调用 `travel_order_agent(task="查询用户下周一至周三杭州出差的已审批差旅单")`。
- 拿到结果后，骨架调用 `finish_subtask(1)`。

执行子任务 2 时，意图是“召回用户偏好”，LLM 匹配到 `preference_agent`，调用它，完成。

执行子任务 3 时，意图是“查询报销标准”，匹配到 `policy_agent`，完成。

**第三步：Plan-and-Execute 骨架的状态维护**

整个过程中，骨架只做三件事，与具体调了哪个子 Agent 无关：

1. 维护 `current_plan` 的子任务状态（TODO → IN_PROGRESS → DONE）；
2. 在每一步把“当前子任务意图 + 可用子 Agent 工具列表”交给 LLM 做路由决策；
3. 根据路由结果调用对应工具，把返回摘要写回计划状态。

如果某个子任务失败了（比如 `travel_order_agent` 返回“未找到已审批差旅单”），骨架可以 `revise_current_plan` 插入一个“提示用户先提交差旅申请”的修复步骤，而不是硬编码某个 Agent 的修复逻辑。

### 这和“写死”版本的本质区别

对比文章里 ItineraryPlanAgent 的写法：它的计划模板里直接写了“调用 `itinerary_review_agent` 做五维审核”。这意味着换一个业务领域，这份计划模板和对应的骨架就得重写。

软路由版本的计划模板是**意图级**的，骨架是**通用**的。新增一个业务子 Agent，只需要：

1. 实现这个子 Agent，写好它的 `description`；
2. 把它注册到工具池；
3. 不需要修改 Plan-and-Execute 骨架的代码，也不需要修改计划模板。

骨架在运行时看到新的工具描述，就能在语义匹配时把它纳入候选。

### AgentScope 中支撑这个模式的现成机制

AgentScope 提供了两个直接可用的能力来落地这种软路由：

**隐式 Routing（工具调用式）**：把子 Agent 包装成工具函数，路由 Agent 根据用户查询决定调用哪个工具。这正是“子 Agent 即工具”的模式——主 Agent 不硬编码调谁，而是让 LLM 在工具池里选。

**Subagents 的 `description` 路由**：中心编排智能体通过 `Task` 工具（或 `agent_spawn`）委托工作，每个子 Agent 的 `description` 就是编排智能体（及模型）判断“何时委托给该子 Agent”的依据。描述写清楚，路由就准确；新增子 Agent 只需新增一份声明，不需要改编排代码。

**PlanNotebook 的手动计划规范**：AgentScope 的 `PlanNotebook` 支持开发者手动调用 `create_plan` 预置计划。这意味着你可以让 Planner 生成意图级计划后，把这份计划“注入”给骨架，骨架只负责执行和路由，不需要自己从零规划。

### 一个需要注意的边界

软路由的准确性依赖 `description` 的质量。如果两个子 Agent 的描述语义重叠（比如两个都写“查询相关信息”），LLM 路由会不稳定。实际落地时，每个子 Agent 的 `description` 应该写成“什么时候该用我、什么时候不该用我”的明确指引，而不是泛泛的能力罗列。AgentScope 的文档也强调，`description` 是“智能体选择是否委派的关键依据”。

另外，软路由并不意味着完全放弃确定性。对于关键路径上的、不允许路由错误的步骤，你仍然可以在计划模板里给某个子任务绑定一个**首选工具提示**，但保留 LLM 在首选不可用时选择备选的能力。这比完全写死灵活，比完全放开可控。

不对、我想的还是这样的架构。主agent(负责路由、总结) + plan-and-execute(计划、执行、维持计划的状态) + 多个业务agent。



搜索到 15 个网页

明白了，你坚持的架构是**三层各司其职**：主 Agent 只做路由和总结，Plan-and-Execute 骨架独立承担规划、执行和状态维护，业务 Agent 保持无状态执行单元。这个切分是成立的，而且比“把 Plan-and-Execute 塞进业务子 Agent”更符合你对扩展性的要求。

下面按这个架构，用具体例子说明三者如何交互。

### 架构定义

| 角色                      | 职责                                                         | **不做什么**                               |
| :------------------------ | :----------------------------------------------------------- | :----------------------------------------- |
| **主 Agent**              | 理解用户意图、将请求路由到正确的 Plan-and-Execute 实例、汇总最终结果 | 不规划具体步骤、不维护子任务状态           |
| **Plan-and-Execute 骨架** | 生成意图级计划、推进子任务状态、在每一步软路由选择业务 Agent、处理回退修订 | 不面向用户、不决定“该不该做这件事”         |
| **业务 Agent**            | 执行单一明确任务（查差旅单、搜航班、查政策），返回结构化结果 | 无状态、不感知计划、不被硬编码到计划模板中 |

AgentScope 的 PlanNotebook 本身就支持“开发者预置计划”和“Agent 管理计划”两种模式，你的架构对应的是：主 Agent 把任务交给骨架后，骨架用 PlanNotebook 管理计划，业务 Agent 通过工具注册暴露给骨架做软路由。

### 具体例子：出差行程规划

假设有三个业务 Agent 已注册到 Plan-and-Execute 骨架的工具池：

| 工具名               | `description`（路由依据）                          | 实际执行者     |
| :------------------- | :------------------------------------------------- | :------------- |
| `query_travel_order` | “查询已审批差旅单，获取出差时间、目的地、审批状态” | 差旅单 Agent   |
| `search_transport`   | “搜索指定城市间指定日期的航班或高铁候选”           | 交通搜索 Agent |
| `query_policy`       | “查询差旅政策中关于住宿、交通舱位、报销上限的标准” | 政策 Agent     |

**第一步：主 Agent 路由**

用户说：“帮我规划下周五到周日去杭州出差。”

主 Agent 的 ReAct 循环判断：这是一个需要多步规划的任务，不属于简单单步查询。它调用路由决策，选择启动 **行程规划 Plan-and-Execute 骨架**，把原始请求作为参数传入。主 Agent 的职责到此暂停，等待骨架返回结果。

**第二步：Plan-and-Execute 骨架生成计划**

骨架收到请求后，在自己的 PlanNotebook 中调用 `create_plan`，生成**意图级**计划（不出现任何业务 Agent 名称）：

json

```
{
  "subtasks": [
    {"intent": "获取本次出差的已审批约束信息"},
    {"intent": "查询差旅政策中杭州相关的报销与舱位标准"},
    {"intent": "搜索去程交通候选（周五，上海→杭州）"},
    {"intent": "搜索返程交通候选（周日，杭州→上海）"},
    {"intent": "汇总信息，输出行程方案摘要"}
  ]
}
```



计划中的每一步只描述“要达成什么”，不指定“由谁执行”。这是软路由的前提。

**第三步：骨架逐项执行，每一步动态路由到业务 Agent**

执行子任务 1（意图：获取出差约束）：

- 骨架将当前子任务意图和可用工具列表交给 LLM 做路由决策。
- LLM 看到工具池中有 `query_travel_order`、`search_transport`、`query_policy`，根据语义匹配，选择调用 `query_travel_order(task="查询用户下周五至周日杭州出差的已审批差旅单")`。
- 差旅单 Agent 执行，返回结构化结果（出差编号、日期、目的地、审批状态）。
- 骨架调用 `finish_subtask(1)`，将结果摘要写入计划状态。

执行子任务 2（意图：查询政策）：

- 骨架再次路由，LLM 匹配到 `query_policy`，调用它，拿到杭州住宿上限和交通舱位标准。
- `finish_subtask(2)`。

执行子任务 3 和 4（意图：搜索去程/返程交通）：

- 骨架分别路由到 `search_transport`，传入不同的日期和方向参数。
- 每次调用后更新子任务状态。

执行子任务 5（意图：汇总输出）：

- 这一步不需要调用业务 Agent，骨架自身根据前四步的结果做汇总推理，生成行程方案草稿。
- `finish_plan()`。

**第四步：骨架返回结果给主 Agent，主 Agent 总结**

骨架将 PlanNotebook 的最终计划状态和汇总结果返回给主 Agent。主 Agent 接收后，做两件事：

1. 将骨架的输出整理成面向用户的自然语言回复（或 Markdown 方案）。
2. 如果骨架返回了“部分步骤失败”（比如未找到已审批差旅单），主 Agent 决定是向用户说明情况、请用户补充信息，还是降级处理。

主 Agent 全程**没有**参与“先查差旅单还是先查政策”这类步骤决策，也没有维护任何子任务状态。它只做了路由和最终包装。

### 这个架构与“软路由”的关系

你上一轮提到的软路由担忧，在这个架构里得到了解决：**Plan-and-Execute 骨架是通用的，计划是意图级的，路由发生在骨架执行每一步时，根据业务 Agent 的 `description` 动态匹配。** AgentScope 的隐式 Routing 模式正是把下游 Agent 包装成工具，由路由 Agent 根据查询决定调用哪个工具。你的骨架扮演的就是这个“路由 Agent”的角色，只不过它路由的粒度是计划中的每一个子任务意图。

新增一个业务 Agent（比如“餐厅推荐 Agent”）时，只需要：

1. 实现该 Agent 并注册到骨架的工具池；
2. 写好它的 `description`；
3. 不需要修改骨架代码，不需要修改计划模板，也不需要修改主 Agent。

骨架在运行时看到新的工具描述，就能在“意图：推荐杭州出差期间的用餐选项”这类子任务出现时把它纳入候选。

### 与文章里 ItineraryPlanAgent 的关键区别

|                         | 文章里的 ItineraryPlanAgent                            | 你坚持的架构                      |
| :---------------------- | :----------------------------------------------------- | :-------------------------------- |
| 计划模板                | 写死了 7 个领域步骤，包含“调用 itinerary_review_agent” | 意图级，不出现具体 Agent 名称     |
| Plan-and-Execute 的位置 | 在业务子 Agent 内部                                    | 作为独立层，与业务 Agent 平级     |
| 新增业务                | 需要新写一个自带 Plan-and-Execute 的领域 Agent         | 只需注册一个新业务 Agent 到工具池 |
| 主 Agent 职责           | 不明确（文章未展示主 Agent）                           | 明确：路由 + 总结                 |

### 落地时的一个必要约束

主 Agent 需要知道“把任务路由给哪个 Plan-and-Execute 骨架”。如果你的业务有多个骨架（行程规划骨架、报销骨架、审批骨架），主 Agent 的路由依据应该是**每个骨架的 `description`**，而不是把骨架内部的业务逻辑硬编码到主 Agent 中。AgentScope 的 Subagent 声明机制支持通过 `description` 让编排者判断何时委托，主 Agent 可以同样用 `description` 来区分“这个请求该交给行程骨架还是报销骨架”。

总结：你的三层架构是合理的，Plan-and-Execute 骨架作为独立的编排层，持有 PlanNotebook 和工具池，每步执行时软路由到业务 Agent；主 Agent 只做入口路由和出口总结；业务 Agent 保持无状态可复用。这样既保留了 Plan-and-Execute 的计划状态管理能力，又避免了“一业务一套 Plan-and-Execute”的定制爆炸。