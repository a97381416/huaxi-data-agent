# huaxi-data-agent 华西智能问数

> 华西项管平台的智能问数 Agent：自然语言查"资料备案/预警/审批/项目档案"，带行级数据权限。
> 本项目是 **my-data-agent 的独立拷贝改造版（B 方案），独立演进**——上游 `D:\work\llmetor-manage\my-data-agent` 保持 MySQL 原样继续作为学习主线，两侧通过 `UPSTREAM.md` 手工同步修复。

## 来源基线

| 项 | 值 |
|---|---|
| 拷贝自 | my-data-agent（main 分支，M9 交付后，commit e9ad4fe） |
| 拷贝日期 | 2026-11-07 |
| 拷贝范围 | backend/（mini-agentx-core + data-agent-server + skills）+ frontend/ |
| 需求与口径文档 | my-data-agent 仓 `docs/`：《华西data-agent引入方案.md》《华西问数需求清单.md》（70 题）《华西问数术语表.md》《华西agent改造执行方案.md》《PostgreSQL迁移改造清单.md》 |

## 三大改造（相对上游）

1. **数据库 MySQL → PostgreSQL**：连华西 PG 库（同库同 schema：业务 65 张白名单 + agentx_*×7 平台表）；M-Schema 自省/建表 DDL/分页方言/EXPLAIN 预检全链 PG 化。
2. **权限体系去 Sa-Token，全面对齐华西 yudao**：登录页为 agent 自有（一期不从华西跳转），但校验 `system_users`（BCrypt）、token 按 yudao 协议签发进 `system_oauth2_access_token`、请求校验复刻 TokenAuthenticationFilter；数据权限 = user→role→dataScope 五档 × doc_project 三载体（deptId/memberUserIds/member 表）行级注入。**将来从华西跳转 SSO 零开发**（token 同表）。
3. **业务术语/技能适配华西**：glossary.yml 换华西 42 条定稿版（备案率官方公式/双状态/产值三表/万元换算等）；data-analysis SKILL.md 补 PG 方言 + 五层穿透下钻工作流；excludeTables 只放行 65 张白名单。

## 里程碑计划

### M-H1 移植建仓 + PG 适配 ✅ 完成（2026-11-06，全量单测绿）
- [x] 拷贝 backend/frontend，目录就绪
- [x] 依赖与连接：mysql-connector → postgresql 42.7.4；application 连接串 PG 化
- [x] 六处内建 DDL 改 PG 方言（含 UPSERT ON DUPLICATE KEY→ON CONFLICT、AUTO_INCREMENT→IDENTITY 两个清单外隐藏点）
- [x] M-Schema 自省：catalog/schema 语义反转（默认 schema=public），值探针反引号→双引号，示例值类型补 PG 形式
- [x] MybatisPlus DbType.POSTGRE_SQL；EXPLAIN 预检重构（FORMAT JSON + SQLState 57014 + Seq Scan 语义）
- [x] H2 单测 MODE=PostgreSQL，全量单测绿
- [x] sql/agentx_pg.sql（七张平台表 PG 建表脚本）

### M-H2 权限体系华西化 ✅ 完成（2026-11-06）
- [x] 删 Sa-Token 依赖/SaTokenConfig/StpInterfaceImpl/auth 旧包/sys 包（redis 由显式依赖补位——原经 sa-token-redis-jackson 传递）
- [x] AuthController(/auth/login) + HuaxiAuthService：system_users + BCrypt → 签发 oauth2 token 入 system_oauth2_access_token（yudao 协议，32 位 UUID）
- [x] HuaxiAuthFilter：Bearer token → 查表校验（30s 缓存）→ HuaxiAuthContext（ThreadLocal）；白名单对齐原契约
- [x] DataScopeResolver 重写：华西三表直查，yudao 五档映射（1/2/3/4/5），CUSTOM 解析 data_scope_dept_ids；DataScope 枚举 +CUSTOM
- [x] YudaoDataScopeRule：三组表分派（doc_project/41 张 project_id 表/预警 2 张）+ member 表特派（防自引用子查询栈溢出）；PermissionRule 接口加 tableName 参数
- [x] DeptTreeCache 数据源改 system_dept；UserContextBuilder 重写（华西用户上下文，去 user_profile 段）
- [ ] 前端登录页对接（M-H4 部署联调时做）
- [x] 期间修复：自引用子查询栈溢出（member 表特派简单等值）、lambda 重载歧义、pom 重复声明

### M-H3 业务适配 ✅ 完成（2026-11-06）
- [x] glossary.yml 替换为华西 42 条（deploy/huaxi 定稿版），GlossaryToolTest 断言同步华西术语
- [x] data-analysis SKILL.md：PG 方言段（TO_CHAR/string_agg/ILIKE/双引号）+ 五层穿透式下钻工作流
- [x] M-Schema **白名单模式**（include-tables 65 张，新增能力优于 60+ 项黑名单）；exclude 兜底 agentx_/QRTZ/infra/bpm
- [x] SystemPrompt/工具描述复查（原本无 MySQL 硬编码）
- [x] 全量 277 测试绿（内核 100 + server 177）

### M-H4 部署联调
- [ ] 华西库执行 agentx_pg.sql；agent 连接串指向华西库（currentSchema=public）
- [ ] Redis 共用 + key 前缀隔离（huaxi-data-agent:）
- [ ] nginx 同域：/agent/ → 前端、/agent-api/ → 后端；华西菜单挂外链入口（后置 SSO 时启用）
- [ ] 评测端点生产收紧（judge/diagnise 网关屏蔽）

### M-H5 验收
- [ ] 冒烟 10 题（管理视角：盘面/穿透/预警）
- [ ] 权限双角色验证（全部档 vs 仅本人档，同题不同行级结果）
- [ ] 70 题清单抽 30 题（简20/中7/难3）出评测集 + gold_sql，M8 链路固化基线
- [ ] 陷阱题 E 组 6 题全过（术语生效证据）

## 部署架构（终态）

```
浏览器（同一域名）
  华西前端 ──菜单外链(后置)──> /agent/ 华西 agent 前端（自有登录页）
        └─ localStorage['ACCESS_TOKEN']（同 key，SSO 铺路）
nginx：/ → 华西前端   /admin-api/ → 华西后端(yudao)
       /agent/ → agent 前端   /agent-api/ → agent 后端(独立进程)
同一个 PG 库：华西业务表 + system_* + agentx_*×7
同一个 Redis：agent key 全部加前缀 huaxi-data-agent:
```

## 与上游的同步

- 修内核 bug 时优先回上游（my-data-agent）修，再 cherry-pick 过来（patch 方式，两仓 git 无亲缘）
- 永久冲突区（cherry-pick 必人工审）：Sa-Token/auth/sys 包已删区、PG 方言差异区（六处 DDL/自省/EXPLAIN）
- 基线 commit：e9ad4fe（见 UPSTREAM.md）
