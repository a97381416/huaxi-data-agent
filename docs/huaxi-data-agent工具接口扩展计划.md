# huaxi-data-agent 工具接口扩展计划

> 从「只读问数 Agent」到「申报人工作台 Agent」：为申报人员扩展业务动作（写）工具。
>
> 版本 v1.2 ｜ 起草 2026-11-09 ｜ M-E1 交互设计定稿 2026-11-09 ｜ PatchMerger 规则定稿（D5，含全流程场景模拟）2026-11-09 ｜ 状态：**M-E1 设计定稿，M-E0 待开工**
>
> 读能力走既有 `executeSql` 六步安全管线（不动）；写能力走**主后端 REST 转发 + 功能权限校验 + 确认令牌**三件套（新建）。

---

## 1. 背景与依据

### 1.1 Trace 证据（session_id = 1881362234509099009）

申报人员1（shenbao1，数据范围=本人）问「测试预警1105这个项目有多少合同金额」，跑了 **29 轮 ReAct**：

- 探索了 doc_project / doc_warning / doc_ledger / doc_file_entry / 债权三表 / hi_task_variable 等 10+ 张表；中途 4 次列名猜错、1 次 JOIN 扩张被拦——申报人的表结构复杂，预制业务工具比现场拼 SQL 稳得多。
- 结论：可见范围内仅 1 个项目「南三高速公路项目」，合同金额全空；5 类合同文件已上传但金额未录。Agent 末尾主动提出 *"帮您看一下这 5 份合同文件是否需要在债权核算统计表中补录合同金额"*——**查询的终点自然指向填报，但 agent 只能读**。
- 库中实况（2026-11-09 查证）：shenbao1 名下**挂着 3 条待重提任务**（`ru_task` task_def_key=submitter，status=running）：
  - `DOC-…-100001-002`：整批驳回，14 条文件全部 audit_status=3
  - `DOC-…-100001-001`：部分驳回（status=4），1 条被驳
  - `DOC-…-400006-002`：部分驳回（status=4）
  - 全系统 91 条 running 任务在流转——需求是现在时的。

### 1.2 申报员业务需求地图（代码 + 角色权限 + 数据三方印证）

主链路在 `zrkj-aigov-huaxi/src/yudao-module-doc`，审批流为自研 taskcenter（非 Flowable）：

| 日常动作 | 权限点（申报人员角色已授予） | 主后端入口 |
|---|---|---|
| 创建/编辑/删除项目、入库 | `doc:project:create / delete / edit / db` | `DocProjectController`（草稿0→进行中1→完结2，仅草稿可删） |
| 资料填报：子阶段(48)→文件节点(149)→文件类别(154) 建台账挂文件 | `doc:project:file:fill` | `DocLedgerController`：create / update / submit-for-review / resubmit / stage-draft / get-draft |
| 驳回后重新提交 | 任务中心菜单（待办/已办/任务详情） | `DocLedgerServiceImpl.resubmit`（status 3/4→1，推进原流程实例） |
| 台账子表填报（投资完成/费用/产值/推进…15 个） | `doc:project-ledger:*`、`doc:investment-*` 等 | 项目档案 1:N 子表 Controller |
| 预警处理（签收/说明/关闭） | `doc:warning-center:sign / explain / close` | `WarningCenterController` |
| 工作台（我的项目/备案率/进度） | 菜单可见 | `DocWorkbenchController.my-projects` |

**核心洞察**：申报员的日常是「**查状态 → 补/改资料 → 提交 → 被驳回 → 再改再提**」的闭环；data-agent 目前只有前半段。trace 显示申报人的查询本身也大多服务于写（"我还有哪些没传""哪条被驳了"）。

### 1.3 现状能力与差距

| 维度 | 现状 | 差距 |
|---|---|---|
| 读 | `executeSql` 六步管线（白名单→数据权限注入→EXPLAIN→只读连接→脱敏→审计） | 无 |
| 写 | **零写工具**；连接账号 pgvector 有库级写权限（应用层防、库没防） | 全部 |
| 功能权限 | 只有数据权限（data_scope 五档），无按钮权限模型 | 新增 |
| 确认机制 | 无（只读不需要） | 新增 |

---

## 2. 总体方案

### 2.1 核心架构决策：读走 executeSql，写走 REST 转发（方案 A）

| 方案 | 说明 | 结论 |
|---|---|---|
| **A. 主后端 REST 转发** | agent 进程内以**当前用户 token** 调 `/admin-api/doc/*`、`/admin-api/task-center/*` | ✅ 采用：主项目校验/按钮权限/状态机守卫/操作日志**零重复实现**，永不漂移；M-H2 已对齐 yudao oauth2 token 协议（同表同校验），天然带凭证 |
| B. 复刻 Service 直写库 | agent 内重写业务逻辑 | ❌ 状态机复杂（submitter 任务校验、instance 推进、audit_status 守卫式更新），漂移风险高 |
| C. agent 自拼写 SQL | INSERT/UPDATE 交给 LLM | ❌ 灾难，不考虑 |

```
用户 ──> agent 前端 ──> data-agent-server
                        ├─ 读：executeSql（SqlSafetyGuard + DataScopeRewriter，不动）
                        └─ 写：XxxActionTool ──> ActionGateway ──> RestActionClient ──> 主后端 /admin-api/*
                                                   ├─ FunctionPermissionChecker（system_role_menu 校验按钮权限）
                                                   ├─ ConfirmTokenService（高危动作两段式确认）
                                                   └─ ActionAuditLogger（审计）
```

**转发要求**：`Authorization: Bearer {当前用户token}` + `tenant-id: {用户租户}`（取自 token/用户表）；写请求**不重试**（幂等性未知）；`CommonResult.code≠0` 时结构化透出主后端 msg 给 LLM。

### 2.2 工具装配与发现

沿用既有三层装配（`DataAgentToolsConfig`）：业务动作工具全部进 `dataDeferredToolCandidates`（延迟层，经 `tool_search` 按需发现），新增技能 `declarant-workflow`（申报工作台 SOP）。新增工具 = `@Component` + `@Tool` 方法 + 注册进延迟层，**不改内核**。

### 2.3 安全三红线

1. **双权限模型**：数据权限（已有，executeSql 注入 + REST 侧 yudao 自带，双保险）+ **功能权限**（新增：写工具执行前查 `system_role_menu` 是否授予该用户所需 permission，如 `doc:project:file:fill`；30s 缓存，对齐 HuaxiAuthFilter 口径）。
2. **两段式确认（确认令牌协议，详见附录 B）**：
   - **读** / **写·低危**（草稿可撤：建草稿项目、暂存条目、驳回后暂存）：直接执行；
   - **写·高危**（状态跃迁/推进审批流/删除：提交审核、重提、删项目、入库、关闭预警、submit=true）：首轮只返回「执行计划 + 确认令牌」不落库；用户明确同意后二调带令牌才执行。令牌绑定 userId+action+payload 哈希、5 分钟 TTL、一次性，防 LLM 幻觉确认与参数偷换。
3. **审计双写**：写操作记入 agentx_trace（已有）+ 主后端操作日志（REST 天然产生）；ActionAuditLogger 另记结构化动作日志（谁/何时/哪个工具/哪个业务对象/结果）。

---

## 3. 工具清单设计

> 类型：R=读 ｜ W1=写·低危（直接执行）｜ W2=写·高危（确认令牌）。主后端接口均已实地核准。

### T1 任务处理组（P1，最高频——shenbao1 现有 3 条真实待办）

| 工具 | 类型 | 权限点 | 对应 REST 接口 | 说明 |
|---|---|---|---|---|
| `listMyTasks(status: todo\|done)` | R | 登录即可 | `GET /task-center/task/todo-page`、`done-page` | 我的待办/已办（申报人只收 submitter 类任务）；返回任务 id、批次号、项目、子阶段、状态 |
| `getTaskDetail(taskId)` | R | 登录即可 | `GET /task-center/task/list-by-instance` + `GET /doc/ledger/get` + `get-stage-draft` | 聚合：驳回意见（pm_opinion）+ 被驳文件逐条清单 + 可重提范围（已通过行锁定不可改） |
| `stageDraft(taskId, patches)` | W1 | `doc:project:file:fill` | `PUT /doc/ledger/stage-draft` | 驳回后暂存（不推进流程）；入参为**增量 patch**，工具层 PatchMerger 合并（见 3.5） |
| `resubmitLedger(taskId, patches, confirmToken?)` | **W2** | `doc:project:file:fill` | `PUT /doc/ledger/resubmit` | 驳回重新提交：status 3/4→1 并推进原流程实例；入参为**增量 patch**，PatchMerger 拉现状→合并→组装全量 `LedgerResubmitReqVO`；执行前校验任务归属（current_instance_id、running、submitter、assignee=本人——主后端守卫 `validateSubmitterTask`） |

### T2 资料填报组（P2，`doc:project:file:fill`）

| 工具 | 类型 | 对应 REST 接口 | 说明 |
|---|---|---|---|
| `listFillNodes(projectNameOrId, phaseCode?)` | R | `GET /doc/ledger/resolve-phase` + `get-nodes` + `get-draft` | 某子阶段下：文件节点 + 预设类别（154 个）+ **已填/草稿/审批状态**——一次回答"还差什么资料"；预制口径，杜绝 29 轮探索 |
| `getMyLedgerDraft(phaseId)` | R | `GET /doc/ledger/get-draft` + `get-stage-draft` | 我的草稿（同项目同阶段草稿唯一） |
| `fillFileEntry(project, phase, node, category, fileUrl?, notApplicable?, summary?)` | W1 | `POST /doc/ledger/create` / `PUT /doc/ledger/update` | 建台账草稿/追加条目；`not_applicable` 四态（-1待上传/0已取得/1空缺/2不涉及/3其他）语义化填报 |
| `submitLedgerForReview(ledgerId)` | **W2** | `POST /doc/ledger/submit-for-review` | 发起 doc_collect 审批流（申报方→PM 一审定稿；特殊文件加协同审核） |

### T3 项目管理组（P3a，`doc:project:*`）

| 工具 | 类型 | 对应 REST 接口 | 说明 |
|---|---|---|---|
| `createProject(name, code, template?, submit=false)` | W1（submit=true 时 W2） | `POST /doc/project/create` | 默认建**草稿**，LLM 幻觉的最坏结果是多一张草稿；code 全局唯一校验（主后端） |
| `updateProject(id, fields)` | W1（转正式时 W2） | `PUT /doc/project/update` | 草稿可改；已入库不可编辑（主后端守卫） |
| `deleteProject(id)` | **W2** | `DELETE /doc/project/delete` | 仅草稿可删，级联清 5 张子表（主后端守卫） |
| `archiveProject(id)` | **W2** | `POST /doc/project/archive` | 入库：status 1→2 + archived=1，不可逆 |

### T4 预警处理组（P3b，`doc:warning-center:*`）

| 工具 | 类型 | 对应 REST 接口 | 说明 |
|---|---|---|---|
| `listMyWarnings(status?)` | R | `GET /doc/warning-center/page` | 我作为责任人的预警（预警按 responsible_user_id 行级过滤，已有规则） |
| `signWarning(id)` / `explainWarning(id, text)` | W1 | `PUT /doc/warning-center/sign`、`/explain` | 签收 / 预警说明 |
| `closeWarning(id)` | **W2** | `PUT /doc/warning-center/close` | 关闭预警 |

> 台账子表 15 组（投资完成/费用/产值/推进…）结构同构，作为**二期候选**不进本计划首批；`declarant-workflow` skill 成熟后按同一模式复制。

### 3.5 M-E1 交互设计定稿（v1.1 拍板，2026-11-09）

| # | 议题 | 决策 |
|---|---|---|
| D1 | 接口复用方式 | **主后端零改动**；复用现成接口，agent 侧工具层做 BFF（聚合/裁剪/增量合并）。升级为主后端专用接口的触发器：批量重提需求 / 聚合性能瓶颈 / 出现第三消费方 |
| D2 | 高危确认交互 | **计划卡片 + [确认执行/取消] 按钮；按钮点击 = 前端注入用户确认消息，经 LLM 携令牌执行（单路径，全程 trace 可回放）**；手打"确认"等效。不做前端直调 REST 的双路径 |
| D3 | 数据修改交互 | 三段式：markdown 状态表格（看）→ 自然语言 patch（说）→ diff 确认卡（核）。**LLM 只产出增量 patch，PatchMerger 工具层拉现状合并成全量 VO**；字段白名单收口 |
| D4 | 传文件交互 | **引导式**（agent 渲染上传槽位卡片）；业务文件**直传主平台** `POST /admin-api/infra/file/upload`（不走 agent MinIO）；上传完成由前端注入结构化消息，LLM 绑定 URL 进 patch |

#### D1 接口架构：BFF 在工具层，主后端零改动

```
              ┌────────────────────────────────────────────────┐
              │        data-agent-server（改动全在这层）           │
  LLM 调工具 → │  listMyTasks    ─── 转发+裁剪 ────────────────┐  │
              │  getTaskDetail  ─── 聚合(3接口拼1视图) ─────┐   │  │
              │  stageDraft     ─── delta合并→组装全量VO ─┐ │   │  │
              │  resubmitLedger ─── delta合并→组装全量VO┐│ │   │  │
              │      ┌─ PatchMerger ◄───────────────────┘│ │   │  │
              │      │  先 GET 现状 fileEntries，把 LLM 的 │ │   │  │
              │      │  增量 patch 合并成全量 VO            │ │   │  │
              └──────│──────────│────────────────│────────│─│───┘
                     ▼          ▼                ▼        ▼
   ┌───────────────────────────────────────────────────────────┐
   │ 主后端（零改动，状态机/权限守卫权威）                          │
   │  GET /task-center/task/todo-page      GET /doc/ledger/get │
   │  GET /doc/ledger/get-stage-draft      PUT /doc/ledger/stage-draft
   │  PUT /doc/ledger/resubmit（全量 LedgerResubmitReqVO）       │
   └───────────────────────────────────────────────────────────┘
```

> 关键适配：`resubmit` 是表单式**全量**提交（14 条 × 25 字段），LLM 拼全量必错；让 LLM 说"把类别=项目建议书的 notApplicable 改成 2"，工具层负责合并——表单接口适配成对话工具的关键一跳。

#### D2 高危确认：卡片按钮 → 注入消息 → 经 LLM 单路径

```
 Agent 输出
 │ ⚠️ 重新提交确认 [#100001-002]
 │   类别=项目建议书: 取得状态 待上传→不涉及
 │   类别=可行性研究: 摘要 (空)→初稿已过会
 │   影响：推进流程实例 #2114965912…
 │   [✅ 确认执行]  [取消]          ◄── W2 计划卡片
 │
 ├── 用户点按钮（或手打"确认"，等效）
 ▼
 前端注入用户消息: "确认执行 ct_8f3a"   ◄── 非直调 REST！
 ▼
 LLM → resubmitLedger(taskId, patches, confirmToken="ct_8f3a")
 ▼
 服务端: 令牌校验(一次性/未过期/payload哈希匹配) → 权限校验 → REST → 审计
 ▼
 Agent: ✅ 已重提，台账 驳回→审批中，流程回到项目经理节点
```

- W1（stageDraft 暂存）**不出卡片**，直接执行后报告；W2（resubmit）必出卡片。
- 令牌绑定 payload 哈希：用户确认后 LLM 改参数 → 令牌失效 → 重新出卡。

#### D3 数据修改：增量 patch 三段式

```
 ①看  Agent 渲染 markdown 状态表格（getTaskDetail，已通过行🔒隐藏）
      │ # │ 类别 │ 文件 │ 状态 │ PM意见 │ 可改字段提示
 ②说  用户自然语言: "项目建议书改成不涉及，可行性研究补摘要：初稿已过会"
      LLM 只产出: patches=[{category,field,value}...]
 ③核  PatchMerger: GET 现状 → 白名单校验 → 已通过行拒绝 → 合并全量
      → 渲染 D2 确认卡 → [确认执行] → resubmit
```

**字段白名单（M-E1）**：

| 允许 LLM 改 | 拒绝（服务端/流程/策略字段） |
|---|---|
| notApplicable（四态）、summary、description、fileUrl+fileName（换文件）、version、provider、providerTime、docPeriod | auditStatus、uploader/uploadTime、pmOpinion（PM 专属）、visibleRoleIds、isConfidential（涉密策略，提示走主平台）、sort/source |

> 拒绝时返回人话提示（"涉密标识请在主平台操作"），防 LLM 反复重试。

#### D4 传文件：引导式上传槽位，直传主平台

```
 Agent（发现条目缺/被驳文件）→ 渲染上传槽位卡片
 ┌────────────────────────────────────────┐
 │ 📎 可行性研究 —— 当前: doc-er.png (被驳)  │
 │   [ 点击或拖拽上传新文件  pdf/jpg/xlsx ]  │
 │   或回复: "不涉及" / "空缺，原因xxx"       │
 └────────────────────────────────────────┘
   │ 用户拖入 kexing-v2.pdf
   ▼
 浏览器直传主平台（不经 agent 存储）
   POST /admin-api/infra/file/upload  (Bearer 用户token)
   ← { url }
   ▼
 前端注入结构化消息: [文件已上传] kexing-v2.pdf → {url}（待绑定：可行性研究）
   ▼
 Agent: 将替换旧文件 → 渲染 diff 卡 → [确认执行] → resubmit
```

- **必须直传主平台**：`doc_file_entry.file_url` 落库转相对路径、读取预签名还原（FileUrlBuilder）；agent MinIO 的 URL 主平台剥不出相对路径，入库后预览全断。
- **不传文件也是出路**：notApplicable 四态（待上传/已取得/空缺/不涉及/其他）覆盖"办不下来/不涉及"场景，一句话改状态即闭环，比传文件更高频。
- 附件多值（attachmentUrl 逗号对齐下标）M-E1 不做，主文件单值先行。

#### D5 PatchMerger 定位与合并规则（v1.1 定稿）

**定位双轨**：`category` 必填做主定位（对话自然、失败响亮）；`node`/`entryId`（字符串）可选做消歧限定词。排除行号定位（位置引用会漂移→静默错行）与纯 entryId 定位（19 位雪花 ID 转写不可靠 + JS Number 精度雷 + 对话中不可见）。原则：**宁可响亮地歧义（追问），绝不安静地错行**。

```
patch {category, node?, entryId?, field, value}
   │
   ▼ L1 精确/归一化匹配 ──0──> L2 包含匹配(ILIKE，仅"唯一命中"才接受)
   │                              │
 命中≥2 ──有 node/entryId──> 收窄重算 ──仍≥2──> ❌歧义协议
   │无限定词 → 歧义协议          0 → ❌零命中协议
 命中1 ──> 三道闸 ──> diff卡
   G1 锁定闸: audit_status=2 已通过行 → 拒绝（人话提示）
   G2 冲突闸: 同 entryId+field 重复 patch → 拒绝整批
   G3 白名单闸: field ∉ 白名单 → 拒绝（人话提示走主平台）
```

- **歧义协议**：返回候选清单（node+category+文件），agent 追问；**"不改了"是合法出口**。
- **零命中协议**：返回当前可用类别清单（含处理状态），LLM 自纠，不打扰用户。
- **批量意图先展开再 patch**：禁 selector；LLM 先查现状渲染明清单，用户确认后展开为逐条 patch。
- **攒批语义（关键）**：resubmit 一次推进流程、任务即办结 → patch 攒到用户明确说"提交"才执行（总确认卡一次性出 diff）；攒批期可 stageDraft（W1）落库保险。patch 清单存于对话上下文，长对话可暂存兜底。
- **令牌哈希绑定解析结果**（entryId+field+value 列表）而非 patch 原文：执行前重拉现状重解析，竞态/参数偷换 → 哈希不匹配 → 令牌作废重出卡。
- **全量组装**：未修改行原样回传；流程字段（auditStatus/uploader/pmOpinion）全程不碰，由主后端按自身规则处理。
- **entryId 一律字符串**：工具 schema、前端、注入消息全链路字符串，防 19 位 ID 精度丢失。

---

## 4. 里程碑计划

> 编号 M-E*（Extension），独立于 README 中 M-H*（移植主线，M-H4/M-H5 未完）。每期独立可交付、可验收，"一步一步来"。

### M-E0 写通道地基（先修路，无业务工具）⬜

**目标**：ActionGateway 三件套就绪，端到端打通"agent→主后端 REST"。

- [ ] `RestActionClient`：RestClient 封装；透传 `Authorization: Bearer` + `tenant-id`；配置 `data-agent.action.base-url`（本地联调 `http://127.0.0.1:9031/admin-api`，部署走 nginx 同域）；写不重试、读可重试；`CommonResult` 错误结构化映射给 LLM
- [ ] `HuaxiAuthContext` 扩展携带 tenantId（取自 system_users / token 表）
- [ ] `FunctionPermissionChecker`：按当前用户角色查 `system_role_menu`（type=3 按钮权限），30s 缓存；无权限时返回话术（对齐"数据披露红线"风格：不泄露权限机制细节）
- [ ] `ConfirmTokenService`：TTL 5min、一次性、绑定 userId+action+payload 哈希（附录 B）
- [ ] `ActionAuditLogger`：结构化动作日志（userId/工具/业务对象/确认令牌id/结果），与 agentx_trace round 关联
- [ ] 系统提示词补「写操作红线」段：高危必须先出计划再确认；未获用户明确同意严禁携带令牌执行；确认令牌不得由 agent 自行构造
- [ ] 单测：权限缓存、令牌生命周期（过期/复用/参数篡改各 case）、错误映射
- [ ] （需协调，不阻塞）为 executeSql 换**PG 只读账号**——当前 pgvector 库级可写，应用层只读单薄；192.168.2.61 为共享远程库，找 DBA 建号
- [ ] 验收：demo 工具 `myPermissions`（查本人功能权限清单）端到端跑通 REST 通道 + 权限校验

**交付物**：action 包（client/permission/confirm/audit）+ 单测 + 配置项 + 提示词红线段。

### M-E1 任务处理组（P1，一个读组 + 两个写）⬜

**目标**：申报人最高频闭环「被驳了什么 → 改 → 重提」全对话完成。

- [ ] `listMyTasks` / `getTaskDetail`（读，聚合驳回意见+被驳清单）
- [ ] `stageDraft`（W1）/ `resubmitLedger`（W2，确认令牌）
- [ ] **PatchMerger**（增量合并核心，规则见 §3.5 D5）：定位双轨（category 主定位 + node/entryId 消歧）→ 匹配阶梯（精确/归一化→包含唯一命中）→ 歧义/零命中双协议 → 三道闸（锁定/冲突/白名单）→ 攒批后组装全量 `LedgerResubmitReqVO`；单测用例直接采用 D5 场景模拟的 9 个场景（单条/模糊/零命中/歧义放弃/批量展开/锁定闸/竞态哈希失效）
- [ ] **前端：确认卡组件**——渲染 W2 计划卡（diff 表 + 影响面 + [确认执行/取消]）；按钮点击=注入用户消息「确认执行 ct_xxx」，**经 LLM 单路径**执行（§3.5 D2），与手打"确认"等效
- [ ] **前端：上传槽位卡片（引导式）**——entry 缺/被驳文件时渲染槽位；业务文件**直传主平台** `POST /admin-api/infra/file/upload`（复用现有附件控件加 target 开关，不走 agent MinIO）；上传完成注入结构化消息 `[文件已上传] {name} → {url}（待绑定：{category}）`（§3.5 D4）
- [ ] 技能 `backend/skills/declarant-workflow/SKILL.md` v1：SOP=先查任务→展示被驳明细与修改计划→用户确认→执行→回报结果；明确"改哪条、改成什么、影响哪个流程实例"的展示格式
- [ ] 工具描述与参数 schema（防 LLM 传错参数名）
- [ ] 单测（MockRestServiceServer：含 PatchMerger 白名单/锁定行/合并 case；确认令牌生命周期）+ 联调冒烟

**验收**（真实数据，shenbao1 的 3 条待办）：
1. 「我有哪些被驳回的任务？」→ 3 条，含批次号/子阶段/被驳条数；
2. 「100001-002 里哪条被驳、什么意见？」→ 14 条清单 + pm_opinion；
3. 演练：对一条待办暂存修改 →（确认后）重提 → 返回 status=1 审批中 + 任务完成；
4. 交互验收：W2 卡片按钮点击后消息链完整可回放（按钮→注入消息→LLM 携令牌→执行→回报）；上传槽位传文件落库后**主平台文件预览可用**（相对路径/预签名正确）；
5. 陷阱验收：改已通过行被拒；白名单外字段（如 pmOpinion）被拒且提示人话；确认后偷改参数令牌失效。
   *重提会真实推进审批流，验收在演练项目上做，或与 PM 约定即时驳回回来。*

### M-E2 资料填报组（P2）⬜

**目标**：「还差什么 → 补报 → 提交审核」全对话完成。

- [ ] `listFillNodes`（读，节点+类别+填报状态合并视图）/ `getMyLedgerDraft`
- [ ] `fillFileEntry`（W1，含 not_applicable 四态语义）/ `submitLedgerForReview`（W2）
- [ ] declarant-workflow skill 扩充填报分支（含"文件从哪来"话术：支持已有 URL 挂账与"不涉及/空缺"语义填报；**本地上传组件不在本计划范围**，记录为前端后续项）
- [ ] 验收（南三项目 200003 合同签订节点）：「还差什么资料」→ 缺口清单 → 补一条"其他合同=不涉及"（草稿）→ 确认后提交审核 → PM 侧收到 doc_collect 任务

### M-E3 项目管理组（P3a）⬜

- [ ] `createProject`（默认草稿）/ `updateProject` / `deleteProject`（W2）/ `archiveProject`（W2）
- [ ] 语义分级落地：submit 参数 true 时强制走确认令牌
- [ ] 验收：对话建草稿项目（含模板选择）→ 改名 → 删除 全链路；入库操作在演练项目上验证确认拦截（不输"确认"不执行）

### M-E4 预警处理组 + 评测收尾（P3b）⬜

- [ ] `listMyWarnings` / `signWarning` / `explainWarning` / `closeWarning`（W2）
- [ ] 汇总评测集：每工具 3~5 题自然语言用例 + **陷阱题**（越权工具调用被拒、未确认先执行被拒、参数偷换令牌失效、他人项目操作不可见）——固化进 agent-eval 评测链路
- [ ] 只读账号改造落地（若 M-E0 未完成）
- [ ] 文档收尾：README 更新工具清单、本计划标记各期状态

### 里程碑依赖与排期

```
M-E0（地基）──> M-E1（任务）──> M-E2（填报）──> M-E4（预警+评测）
                    └──────────> M-E3（项目）──┘
```

M-E1 优先于 M-E2/M-E3：待办数据现成、闭环最短、写通道只动一个高危工具，风险最小。

---

## 5. 风险与对策

| # | 风险 | 对策 |
|---|---|---|
| 1 | LLM 幻觉确认（自问自答执行高危动作） | 确认令牌由服务端签发、绑定 payload 哈希、一次性；提示词红线；评测陷阱题守底线 |
| 2 | resubmit 不幂等，重复推进流程 | 令牌一次性 + 主后端 `validateSubmitterTask` 状态守卫（running/归属校验）双保险 |
| 3 | 长会话 token 过期 → REST 401 | 两侧同表同协议同时效，401 结构化透出并引导重新登录 |
| 4 | 主后端未启动/网络不通 | 结构化降级话术（"主系统暂不可达，稍后再试或前往平台操作"），读能力不受影响 |
| 5 | 文件上传短板（文本 agent 无本地文件） | P2 先支持 URL 挂账 + not_applicable 语义；上传组件列为前端后续项（out of scope） |
| 6 | 权限缓存与主后端改权不同步 | 30s 短缓存兜底；高危动作二次校验在主后端（REST 侧永远权威） |
| 7 | pgvector 账号库级可写被绕过（提示注入） | SqlSafetyGuard AST 白名单仍在前排；只读账号为纵深加固项 |
| 8 | 台账子表 15 组同构工具膨胀 | 本计划只做 4 组核心；其余等 declarant-workflow skill 模式成熟后复制 |

---

## 6. 与 M-H4（部署联调）的关系

- 写通道 `base-url` 与 M-H4 的 nginx 同域规划天然一致（`/admin-api` 已在规划内）；本地联调直连 `127.0.0.1:9031`，不依赖 M-H4 完成。
- M-E1 起联调需主后端本地起服（`huaxi-local-dev` skill：先 install 再 spring-boot:run）。

---

## 附录 A：REST 接口速查（均已实地核准）

| 域 | 接口 |
|---|---|
| 任务 | `GET /task-center/task/todo-page` ｜ `done-page` ｜ `statistics` ｜ `list-by-instance` |
| 台账 | `GET /doc/ledger/resolve-phase?phaseCode=` ｜ `get-nodes?phaseId=` ｜ `get?id=` ｜ `get-draft?phaseId=` ｜ `get-stage-draft?ledgerId=` ／ `POST create` ｜ `submit-for-review` ／ `PUT update` ｜ `submit?id=` ｜ `resubmit` ｜ `stage-draft` ／ `DELETE delete?id=` |
| 项目 | `POST /doc/project/create` ｜ `PUT update` ｜ `DELETE delete?id=` ｜ `POST archive` |
| 预警 | `GET /doc/warning-center/page` ｜ `get?id=` ／ `PUT sign` ｜ `explain` ｜ `close` |

（前缀 `/admin-api`；鉴权 Bearer token + tenant-id 头。）

## 附录 B：确认令牌协议（草案 v1）

```
1. LLM 调高危工具（无 confirmToken 或 confirmed≠true）
   └─ 服务端生成 plan：
      { action, businessObject 摘要, 变更 diff（改哪条/改成什么/影响）,
        confirmToken: "ct_xxx", expiresIn: 300 }
      —— 不产生任何写副作用
2. LLM 把 plan 渲染成计划卡片（diff + 影响面 + [确认执行/取消] 按钮）
3. 用户同意（两条等效通道，均经 LLM 单路径，v1.1 定稿）：
   a. 点 [确认执行] → 前端注入用户消息 "确认执行 ct_xxx"
   b. 手打 "确认" / "执行"
   → LLM 二调同一工具，带 confirmToken
   └─ 服务端校验：存在 && 未用 && 未过期 && userId 匹配 && payload 哈希匹配
      → 消费令牌（一次性）→ FunctionPermissionChecker → REST 执行 → 审计
4. 任一不符 → 拒绝并重新生成 plan（新令牌）
存储：进程内 Caffeine 即可（单实例）；多实例部署时迁 Redis（key 前缀 huaxi-data-agent:）
注：[取消] 按钮 = 注入 "取消 ct_xxx" → 服务端即时作废该令牌（防过期窗口内被复用）
```
