# 审计留痕、权限追责与同类放行安全规范

> **定位**：本文档是 HITL 一/二/三期共用执行的**安全与合规红线规范**，明确回答两个生产级核心问题：
> 1. **追责留痕**：每个用户的审批动作（批准/拒绝/自动放行/核对）如何 100% 留痕、防篡改、还原现场？
> 2. **同类放行**：什么是“本会话同类放行”，怎么精准判定“同类”，以及如何防止授权失控？
>
> **关联文档**：
> - 一期：[一期-HITL底座与授权型闭环方案.md](./一期-HITL底座与授权型闭环方案.md)
> - 二期：[二期-HITL快路径原地唤醒与串行队列方案.md](./二期-HITL快路径原地唤醒与串行队列方案.md)
> - 三期：[三期-HITL多类型扩展(收集型_核对型_计划确认型)方案.md](./三期-HITL多类型扩展(收集型_核对型_计划确认型)方案.md)

---

## 1. 全链路审计留痕与追责机制

### 1.1 三段式不可篡改证据链
追责时最怕两类扯皮：**“当时我看到的不是这个内容”** 和 **“不是我点的”**。系统通过 **事前快照 + 事中签字 + 事后执行** 三段式留痕把证据链焊死：

```text
[用户原始提问 & AI推理]       [决策单快照 & 真人签字]         [主平台真实执行]
     agentx_trace      ◀────▶     agentx_decision     ◀────▶   agentx_action_log
   (conversation_id,             (decision_id,                  (decision_id,
    session_id)                   payload_hash,                  external_req/resp,
                                  action_summary,                success)
                                  answered_by/at)
```

### 1.2 核心留痕字段说明（谁批的、何时批的、批了什么）

| 追责维度 | 落库字段（`agentx_decision`） | 防抵赖与还原机制 |
| :--- | :--- | :--- |
| **谁批的（审批人）** | `answered_by`（用户 ID）<br/>`answered_by_name`（当时姓名）<br/>`owner_user_id`（会话发起人）<br/>`client_ip`（操作端 IP） | **绝对不信任前端传的 userId**；后端强制从网关鉴权 `HuaxiAuthContext` 提取真人身份；冗余存姓名防止日后改名/离职查不到；**严禁 `role=agent` 或 LLM 调用审批接口**。 |
| **何时批的（时间线）** | `created_at`（弹窗生成时间）<br/>`answered_at`（点击签字精确时间戳）<br/>`consumed_at`（系统真正放行执行时间） | 完整还原“何时弹窗 → 人审阅了多久 → 何时签字 → 何时真正执行外部写操作”。 |
| **批了什么（现场还原）** | `action_summary`（**前端卡片展示的人话原文**）<br/>`payload_json`（完整结构化入参）<br/>`payload_hash`（SHA-256 防篡改指纹） | `action_summary` 固化**用户当时肉眼看到的内容**；执行前系统重新计算参数哈希与 `payload_hash` 比对，确保“批准的”和“执行的”一字不差。 |
| **怎么批的 & 理由** | `status`（终态锁定不可改）<br/>`decision_option`（如 `ALLOW_ONCE`/`ALLOW_SESSION`/`REJECTED`）<br/>`user_words`（拒绝理由/审批备注）<br/>`grant_id`（若为自动放行，指向原授权单） | 哪怕是**拒绝**或**自动放行**，也永久留痕。历史会话回放时，前端以只读留痕条展示当时的签字记录。 |

### 1.3 数据库红线
- `agentx_decision` 表**严禁物理删除**；进入终态（`CONSUMED` / `REJECTED` / `EXPIRED` / `SUPERSEDED`）后代码层锁定不可再更新。
- 写操作审计表 `agentx_action_log` 新增 `decision_id` 字段关联决策单，形成从业务单号反查审批人和 AI 推理链的完整闭环。

---

## 2. “本会话同类放行”的判定与安全护栏

### 2.1 第一关：工具风险分级（不是所有工具都允许“同类放行”）

| 风险级别 | 典型工具示例 | 是否允许“本会话同类放行” | 前端按钮呈现 |
| :--- | :--- | :--- | :--- |
| **L3 不可逆 / 强合规审批类** | 终审通过（`ApproveTaskTool`）、驳回（`RejectTaskTool`）、删除/不可逆资金操作 | **禁止**（`allowSessionGrant = false`） | 仅展示【拒绝】【仅本次允许】，强制逐笔签字 |
| **L2 中高风险批量操作类** | 暂存草稿（`StageDraftTool`）、填报条目（`FillFileEntryTool`）、同项目批量提审 | **允许**（`allowSessionGrant = true`） | 展示【拒绝】【仅本次允许】【本会话同类放行】 |
| **L1 只读 / 收集型** | 查表、查台账、`AskUserQuestionTool` | 不涉及权限授予 | 无授权按钮 |

### 2.2 第二关：如何精准判定“同类”？—— 作用域指纹 `scopeKey`
“同类”**绝对不是按工具名一刀切**，而是由工具按业务边界生成结构化 `scopeKey`：

```text
scopeKey = {toolName} : {actionType} : {businessBoundary}
```

#### 典型业务判定示例
1. **批量更新草稿（`StageDraftTool`）**：
   - 授权生成：`scopeKey = "stageDraft:UPDATE:project=P-001"`
   - **命中同类（自动放行）**：同一会话内，AI 继续修改 **项目 P-001** 的其他草稿字段。
   - **不命中（必须重新弹窗）**：AI 试图修改 **项目 P-002** 的草稿（`project` 边界变化）。
2. **批量提审（`SubmitLedgerForReviewTool`）**：
   - 授权生成：`scopeKey = "submitLedger:SUBMIT:project=P-001:reviewer=张主任"`
   - **命中同类（自动放行）**：同项目、同一审核人的其他台账提审。
   - **不命中（必须重新弹窗）**：换了项目，或者审核人换成了李主任。
3. **大表导出（`ExportSqlTool`）**：
   - 授权生成：`scopeKey = "exportSql:EXPORT:table=t_budget_2026"`
   - **命中同类（自动放行）**：同会话内按不同筛选条件再次导出 `t_budget_2026`。
   - **不命中（必须重新弹窗）**：试图导出另一张敏感表 `t_salary`。

> **前端透明化要求**：卡片上【本会话同类放行】按钮旁/悬浮提示必须用大白话写明放行范围（例如：`仅限本会话内「项目 P-001」的草稿更新`），杜绝用户盲点。

### 2.3 第三关：自动放行的 4 道安全护栏
1. **严格会话隔离**：`agentx_decision_grant` 强绑定 `user_id + conversation_id`，新开对话窗口自动失效，绝不跨会话污染。
2. **自动放行照样留痕**：命中 `scopeKey` 自动放行时，后端依然插入一条 `agentx_decision` 记录，标记 `status = AUTO_APPROVED_BY_GRANT` 并写入 `grant_id`（追溯到当初用户亲手签字的那条授权单）。
3. **前端明示 + 一键随时收回（Revoke）**：每次自动放行，前端聊天流输出轻量提示条：
   `⚡ 已依据您于 14:02 授予的会话权限自动放行「更新项目 P-001 草稿」 [撤销本会话授权]`
   用户点击【撤销】后，立刻将该 `grant` 标为 `REVOKED`，下一次操作马上恢复逐笔弹窗。
4. **单会话防失控配额**：每条会话级授权默认设置**最大自动放行次数上限（20 次）**与**硬超时（2 小时）**，防止 AI 死循环时连续写入业务库。

---

## 3. 安全并发防线汇总

| 攻击/异常场景 | 防护机制 |
| :--- | :--- |
| **双击 / 多标签页并发点击** | 请求必带幂等 `commandId` + SQL CAS 乐观锁 `WHERE decision_id=? AND status='PENDING' AND revision=?` |
| **越权替他人审批** | 校验 `HuaxiAuthContext.getUserId() == decision.owner_user_id`，不匹配直接返回 `403` |
| **LLM 伪造审批 / 偷改参数** | `decide` 接口不注册为工具；执行前重算 `payloadHash` 与落库指纹强比对，不一致抛出 `PAYLOAD_MISMATCH` 拒绝执行 |
| **等待期间业务数据漂移** | 执行前重跑业务前置状态守卫（如确认台账仍处于草稿态），已变更则标记 `DECISION_STALE` 作废并通知 AI 重查 |

---

## 4. 核心表结构 DDL 参考（PostgreSQL）

```sql
-- 1. HITL 决策单主表（唯一真相，严禁物理删除）
CREATE TABLE IF NOT EXISTS agentx_decision (
  decision_id        varchar(64)  NOT NULL,
  conversation_id    varchar(100) NOT NULL,
  session_id         varchar(100) NOT NULL,
  tool_call_id       varchar(100) DEFAULT NULL,
  agent_code         varchar(64)  DEFAULT NULL,
  owner_user_id      bigint       NOT NULL,
  type               varchar(32)  NOT NULL, -- PERMISSION / QUESTION / VERIFY_OUTCOME / PLAN_CONFIRM
  status             varchar(32)  NOT NULL, -- PENDING / APPROVED / REJECTED / ANSWERED / CONSUMED / AUTO_APPROVED_BY_GRANT / EXPIRED / SUPERSEDED
  revision           int          NOT NULL DEFAULT 1,
  tool_name          varchar(100) NOT NULL,
  scope_key          varchar(255) DEFAULT NULL,
  action_summary     text         NOT NULL, -- 前端卡片展示原文（追责快照）
  payload_json       text         NOT NULL, -- 完整结构化参数快照
  payload_hash       varchar(128) NOT NULL, -- SHA-256 防篡改指纹
  options_json       text         DEFAULT NULL,
  decision_option    varchar(64)  DEFAULT NULL, -- ALLOW_ONCE / ALLOW_SESSION / REJECTED / ...
  answer_json        text         DEFAULT NULL, -- 收集型/核对型结构化答案
  user_words         text         DEFAULT NULL, -- 拒绝理由 / 审批备注 / 打回意见
  grant_id           varchar(64)  DEFAULT NULL, -- 自动放行时关联的授权记录 ID
  answered_by        bigint       DEFAULT NULL,
  answered_by_name   varchar(100) DEFAULT NULL,
  client_ip          varchar(64)  DEFAULT NULL,
  command_id         varchar(64)  DEFAULT NULL, -- 客户端幂等键
  expires_at         timestamp    NOT NULL,
  created_at         timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  answered_at        timestamp    DEFAULT NULL,
  consumed_at        timestamp    DEFAULT NULL,
  PRIMARY KEY (decision_id)
);
CREATE INDEX IF NOT EXISTS idx_decision_conv_status ON agentx_decision (conversation_id, status);
CREATE INDEX IF NOT EXISTS idx_decision_owner_status ON agentx_decision (owner_user_id, status, expires_at);
CREATE UNIQUE INDEX IF NOT EXISTS uk_decision_cmd ON agentx_decision (decision_id, command_id) WHERE command_id IS NOT NULL;

-- 2. 会话级同类授权表
CREATE TABLE IF NOT EXISTS agentx_decision_grant (
  grant_id           varchar(64)  NOT NULL,
  user_id            bigint       NOT NULL,
  conversation_id    varchar(100) NOT NULL,
  source_decision_id varchar(64)  NOT NULL, -- 最初亲手签字授权的决策单 ID
  tool_name          varchar(100) NOT NULL,
  scope_key          varchar(255) NOT NULL, -- 精准业务作用域指纹
  scope_desc         varchar(500) DEFAULT NULL, -- 人话范围描述
  status             varchar(20)  NOT NULL DEFAULT 'ACTIVE', -- ACTIVE / REVOKED / EXPIRED
  max_uses           int          NOT NULL DEFAULT 20,
  used_count         int          NOT NULL DEFAULT 0,
  expires_at         timestamp    NOT NULL,
  created_at         timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  revoked_at         timestamp    DEFAULT NULL,
  PRIMARY KEY (grant_id)
);
CREATE INDEX IF NOT EXISTS idx_grant_lookup ON agentx_decision_grant (user_id, conversation_id, scope_key, status);

-- 3. 现有动作审计表扩展关联字段（增量 ALTER）
ALTER TABLE agentx_action_log ADD COLUMN IF NOT EXISTS decision_id varchar(64) DEFAULT NULL;
CREATE INDEX IF NOT EXISTS idx_action_log_decision ON agentx_action_log (decision_id);
```

---

## 5. 安全与合规专项验收清单

- [ ] **TC-S1 追责字段完整**：批准、拒绝、自动放行 3 种路径完成后，核查 `agentx_decision` 的 `answered_by`、`answered_by_name`、`answered_at`、`action_summary`、`payload_hash`、`user_words` 均准确落库。
- [ ] **TC-S2 L3 禁止同类放行**：触发 `ApproveTaskTool` / `RejectTaskTool` 时，前端不出现【本会话同类放行】按钮；后端直接传 `ALLOW_SESSION` 也被拒绝。
- [ ] **TC-S3 ScopeKey 精准边界**：批准“项目 P-001 草稿更新”的同类放行后，同会话再改 P-001 自动放行并留痕；改 P-002 时必须重新弹窗拦截。
- [ ] **TC-S4 跨会话不污染**：新开会话再改 P-001，确认必须重新弹窗审批。
- [ ] **TC-S5 一键撤销生效**：点击自动放行提示条上的【撤销本会话授权】，下一次改 P-001 立刻恢复弹窗。
- [ ] **TC-S6 配额上限拦截**：单会话自动放行达到 `max_uses`（如 20 次）后，第 21 次自动恢复弹窗确认。
