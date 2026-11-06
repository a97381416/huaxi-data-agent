# UPSTREAM 同步手册

> 记录与上游 my-data-agent 的基线与同步动作。两仓 git 无亲缘，同步一律 patch 方式。

## 拷贝基线

- 上游仓库：`D:\work\llmetor-manage\my-data-agent`
- 基线 commit：`e9ad4fe`（main，M9 交付后）
- 拷贝日期：2026-11-07
- 范围：backend/ + frontend/（排除 target、node_modules、dist、.idea）

## 永久冲突区（上游 patch 进来必人工审）

| 区 | 冲突原因 |
|---|---|
| Sa-Token / auth / sys 包 | 本仓已删除，替换为 HuaxiAuthController / HuaxiTokenFilter / HuaxiIdentityProvider / YudaoDataScopeRule |
| 六处内建 DDL（AgentxSessionStore/TraceManager/JdbcPauseStateStore/SkillManager/McpServerManager/FileManageService） | MySQL 方言已改 PG |
| MschemaIntrospector / ExplainPrecheckService / MybatisPlusConfig | PG 语义差异（schema 参数 / 超时判定 / 分页方言） |
| resources/schema/glossary.yml | 已替换为华西 42 条 |
| auth/ 包 | 已重写：HuaxiAuthContext/HuaxiAuthFilter/HuaxiAuthService(含 getUserInfo)/AuthController；旧 AuthService/Sa-Token 全删 |
| sys/ 包 | 整包删除（华西体系只读直查） |
| PermissionRule 接口 | 两方法均加 tableName 参数；规则=单一 YudaoDataScopeRule；DataScopeResolver/DeptTreeCache 数据源华西化 |
| MschemaIntrospector/MschemaCacheService | 新增 include-tables 白名单模式（第 4 参） |
| pom | sa-token 全删；显式 spring-security-crypto 6.3.4 + spring-boot-starter-data-redis（原传递依赖补位） |
| backend/skills/data-analysis/SKILL.md | 已加 PG 方言 + 穿透工作流 |

## 同步记录

| 日期 | 方向 | 上游 commit | 内容 | 冲突处理 |
|---|---|---|---|---|
| 2026-11-07 | 拷贝 | e9ad4fe | 初始基线 | — |
| 2026-11-06 | 改造 | — | M-H1 PG 适配 + M-H2 权限华西化 + M-H3 业务适配（277 测全绿） | 见上冲突区 |
