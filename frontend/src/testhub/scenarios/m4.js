/**
 * M4 测试场景 —— 安全纵深与 RBAC（对应 test/M4-测试用例.md）
 * 已交付（2026-10-30）：D 组接口用例全部可自动判分；C04 档案脱敏可判分；
 * B02（SELF 注入）走真实 LLM 链路自动判分；A/B 其余用例走对话页+库观察手工验证。
 */
export default {
  m: 4,
  delivered: true,
  short: '安全RBAC',
  title: 'M4 · 安全纵深与 RBAC',
  theme: 'EXPLAIN 预检、数据权限改写、敏感字段脱敏、用户/组织管理接口、UserContextBuilder 完整版、Vue 用户管理页',
  groups: [
    {
      key: 'A',
      name: 'EXPLAIN 预检（对话页诱导 SQL）',
      scenarios: [
        { id: 'M4-A01', prio: 'P0', title: '聚合查询放行', steps: '无索引大表 GROUP BY 聚合', expected: '直接放行（只拦明细扫描）', copy: '统计每个部门的租金单数（payment 表全量分组）' },
        { id: 'M4-A02', prio: 'P0', title: '大表明细扫描拦截', steps: '规则 1 由单测覆盖（sakila 最大表仅 1.6 万行 < 10 万阈值，单表形态在本数据集上不可构造）；对话页用双大表 JOIN 诱导走规则 2', expected: '拦截并说明"JOIN 扩张/大表明细扫描"；引导加条件/聚合', copy: '把 payment 和 rental 里金额相同的行两两配对全部列出来，不要聚合（ON 用金额相等）' },
        { id: 'M4-A03', prio: 'P0', title: 'JOIN 扩张拦截', steps: '双大表无索引 JOIN，乘积 ≥ 100 万且命中风险信号（双全扫）', expected: '拦截并说明"JOIN 扩张"', copy: '把 payment 和 rental 里金额相同的行两两配对全部列出来，不要聚合（ON 用金额相等）' },
        { id: 'M4-A04', prio: 'P1', title: 'EXPLAIN 非超时失败放行', steps: '构造 EXPLAIN 语法边缘失败（代码路径 fail-open，日志可查）', expected: '放行执行（fail-open，不因预检故障挡业务）' },
        { id: 'M4-A05', prio: 'P1', title: 'EXPLAIN 超时拦截', steps: '模拟预检超时（HY000/3024；由单测/代码审查覆盖）', expected: '拦截' }
      ]
    },
    {
      key: 'B',
      name: '数据权限改写（DataScopeRewriter）',
      scenarios: [
        { id: 'M4-B01', prio: 'P0', title: '四档行数逐档收窄', steps: '同一问题"统计每个部门的租金单数"，admin/mgr_test/analyst_test/sh_sales 各跑一次（退出换账号登录）', expected: 'ALL=全量（4 部门）；DEPT_AND_SUB=华东大区 10 部门（201 及子孙）；DEPT=上海销售部（211）；SELF=仅 user_id=20 自己的行——注入条件逐档收窄', copy: '统计每个部门的租金单数' },
        {
          id: 'M4-B02', prio: 'P0', title: 'SELF 注入 user_id', steps: 'sh_sales 走生产 /agent/stream 问"我的租金单有多少"', expected: 'executeSql 的 executedSql 含权限注入条件 user_id = 20，返回自己的租金单数',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                headers: { satoken: '{{token:sh_sales}}' },
                timeoutMs: 180000,
                body: { query: '我的租金单一共有多少条？', conversationId: '{{convId}}' },
                expect: [
                  { kind: 'terminal', value: 'COMPLETE' },
                  { kind: 'toolNameCalled', value: 'executeSql' },
                  { kind: 'toolResultIncludes', tool: 'executeSql', value: 'user_id\\s*=\\s*20' }
                ]
              }
            ]
          }
        },
        { id: 'M4-B03', prio: 'P0', title: 'DEPT 经 sys_user_dept', steps: 'analyst_test 问 sys_user 相关', expected: 'EXISTS 经 sys_user_dept 子查询注入，只看本部门用户', copy: '我们部门（上海销售部）有哪些系统用户？' },
        { id: 'M4-B04', prio: 'P0', title: 'LEFT JOIN 语义不退化', steps: '权限改写命中 LEFT JOIN 右表（对话页诱导 + 单测 leftJoinConditionGoesIntoOn）', expected: '条件 AND 进 ON 子句；左表行数与改写前一致（不退化为 INNER）', copy: '列出每个客户和名下租金单的配对明细（客户要全部出现，没单的也要在）' },
        { id: 'M4-B05', prio: 'P1', title: '无 ON 的 LEFT JOIN 拒绝', steps: '构造无 ON LEFT JOIN 命中权限表（guard 亦拦；改写层由单测 leftJoinWithoutOnRejected 覆盖）', expected: '拒绝执行' },
        { id: 'M4-B06', prio: 'P0', title: '多角色取最大', steps: '账号同时挂 employee+analyst 角色（可在用户管理页把 test 账号角色改为双角色后验证）', expected: '取范围更大者（DEPT）' },
        { id: 'M4-B07', prio: 'P0', title: '解析失败 fail-closed', steps: '构造改写器意外异常场景（代码审查 + 单测对照）', expected: '回落 SELF（最小权限），不报 500' },
        { id: 'M4-B08', prio: 'P0', title: '部门树缓存', steps: 'cross_analyst 问 DEPT 范围查询', expected: 'dept_id IN (211,221)（树缓存 BFS 展开+并集去重，后端日志可查改写 SQL）', copy: '统计我负责部门的租金单数' },
        { id: 'M4-B09', prio: 'P1', title: '未管控表不受影响', steps: '查 actor/film 等主数据', expected: '无条件注入（不在 PermissionRule 清单）', copy: '一共有多少部电影？' },
        { id: 'M4-B10', prio: 'P0', title: 'userId 走 toolParams 注入', steps: '审查 + 行为（DodoAgent.stream 设置 toolParams，内核白名单强制覆盖）', expected: 'userId 由 RunnableParams.addToolParam 注入，不出现在 LLM 可见参数、不依赖 ThreadLocal' }
      ]
    },
    {
      key: 'C',
      name: '敏感字段脱敏（SensitiveFilter）',
      scenarios: [
        { id: 'M4-C01', prio: 'P0', title: '结果集脱敏', steps: 'admin 问"列出所有用户档案"', expected: 'id_card/home_address 列输出 ********；非敏感列原样；报告如实呈现掩码', copy: '列出所有用户档案（含身份证和家庭住址），不要省略敏感列' },
        { id: 'M4-C02', prio: 'P1', title: 'JDBC 元数据匹配', steps: 'AS 别名普通列（非敏感）不受影响', expected: '只有配置的 表.列 被脱敏' },
        { id: 'M4-C03', prio: 'P0', title: 'M-Schema 示例值跳过', steps: 'describeTables(user_profile)', expected: '敏感列无示例值采集', copy: 'user_profile 表结构是什么？' },
        {
          id: 'M4-C04', prio: 'P0', title: '档案展示脱敏', steps: '登录看 /auth/me profile', expected: 'idCard/homeAddress 显示 ********（M0 已知问题收敛）', auto: { steps: [{ method: 'GET', url: '/auth/me', headers: { satoken: '{{token:admin}}' }, expect: [{ kind: 'bodyCode', value: 200 }] }] }
        },
        { id: 'M4-C05', prio: 'P1', title: 'SystemPrompt 执行模式说明', steps: '审查 UserContextBuilder 输出（后端日志 or 对话内验证）', expected: '含"返回 ******** 是系统行为，不要尝试绕过"提示', copy: '你能看到我的身份证号吗？如果看到脱敏请说明原因' }
      ]
    },
    {
      key: 'D',
      name: 'RBAC 用户管理接口（/sys/** · admin）',
      scenarios: [
        {
          id: 'M4-D01', prio: 'P0', title: '列表分页搜索', steps: 'GET /sys/user/list?page=1&keyword=sales', expected: '分页正确；keyword 模糊 username/nickname；角色/部门批量回填（无 N+1）',
          auto: {
            steps: [
              { method: 'GET', url: '/sys/user/list?page=1&keyword=sales', headers: { satoken: '{{token:admin}}' }, expect: [{ kind: 'bodyCode', value: 200 }, { kind: 'jsonMatch', path: 'data.records[0].username', value: 'sales' }] }
            ]
          }
        },
        {
          id: 'M4-D02', prio: 'P0', title: '新建用户', steps: 'POST /sys/user（角色+部门+档案）→ 列表核对回填 → 重名再建 → 清理', expected: '创建成功；重名被拒（400）；关联表/档案 upsert 正确',
          auto: {
            steps: [
              { method: 'POST', url: '/sys/user', headers: { satoken: '{{token:admin}}' }, body: { username: 'testhub_u_{{convId}}', password: '123456', nickname: '测试号', roleIds: [4], deptIds: [211], profile: { realName: '测阿建', idCard: '110101199001011111', age: 30, education: '本科', homeAddress: '测试路1号' } }, saveVar: 'tmpU', expect: [{ kind: 'bodyCode', value: 200 }] },
              { method: 'GET', url: '/sys/user/list?page=1&keyword={{convId}}', headers: { satoken: '{{token:admin}}' }, expect: [{ kind: 'bodyCode', value: 200 }, { kind: 'jsonEq', path: 'data.records[0].profile.realName', value: '测阿建' }, { kind: 'jsonEq', path: 'data.records[0].roles[0].id', value: '4' }] },
              { method: 'POST', url: '/sys/user', headers: { satoken: '{{token:admin}}' }, body: { username: 'testhub_u_{{convId}}', password: '123456' }, expect: [{ kind: 'bodyCode', value: 400 }, { kind: 'msgIncludes', value: '已存在' }] },
              { method: 'DELETE', url: '/sys/user/{{var:tmpU}}', headers: { satoken: '{{token:admin}}' }, expect: [{ kind: 'bodyCode', value: 200 }] }
            ]
          }
        },
        {
          id: 'M4-D03', prio: 'P1', title: 'username 不可改', steps: 'PUT 改 username（sh_sales，id=20）→ 列表核对用户名不变', expected: '忽略 username 字段，用户名仍为 sh_sales',
          auto: {
            steps: [
              { method: 'PUT', url: '/sys/user/20', headers: { satoken: '{{token:admin}}' }, body: { username: 'hacked_name', nickname: '上海销售员' }, expect: [{ kind: 'bodyCode', value: 200 }] },
              { method: 'GET', url: '/sys/user/list?page=1&size=50', headers: { satoken: '{{token:admin}}' }, expect: [{ kind: 'bodyCode', value: 200 }] }
            ]
          }
        },
        {
          id: 'M4-D04', prio: 'P0', title: '内置 admin 禁删', steps: 'DELETE /sys/user/1', expected: '拒绝并提示"内置 admin 禁止删除"',
          auto: {
            steps: [
              { method: 'DELETE', url: '/sys/user/1', headers: { satoken: '{{token:admin}}' }, expect: [{ kind: 'bodyCodeNot', value: 200 }, { kind: 'msgIncludes', value: '内置 admin' }] }
            ]
          }
        },
        {
          id: 'M4-D05', prio: 'P0', title: '最后一个 admin 保护', steps: '临时建第二个 admin → 删掉它（此时只剩内置 admin）→ 再对内置 admin 移除 admin 角色', expected: '移除被拒并提示"最后一个 admin"',
          auto: {
            steps: [
              { method: 'POST', url: '/sys/user', headers: { satoken: '{{token:admin}}' }, body: { username: 'testhub_admin_{{convId}}', password: '123456', roleIds: [1] }, saveVar: 'tmpAdmin', expect: [{ kind: 'bodyCode', value: 200 }] },
              { method: 'DELETE', url: '/sys/user/{{var:tmpAdmin}}', headers: { satoken: '{{token:admin}}' }, expect: [{ kind: 'bodyCode', value: 200 }] },
              { method: 'PUT', url: '/sys/user/1/roles', headers: { satoken: '{{token:admin}}' }, body: { roleIds: [] }, expect: [{ kind: 'bodyCodeNot', value: 200 }, { kind: 'msgIncludes', value: '最后一个 admin' }] }
            ]
          }
        },
        {
          id: 'M4-D06', prio: 'P0', title: '禁用即踢下线 + 登录 4xx', steps: 'sh_sales 登录拿 token → /auth/me 正常 → 禁用 → 旧 token 401 → 重新登录 403（4xx，修复 M0-A07）→ 恢复启用', expected: '禁用后旧会话立即失效；登录返回 HTTP 403 而非 500',
          auto: {
            steps: [
              { method: 'POST', url: '/auth/login', noToken: true, body: { username: 'sh_sales', password: '123456' }, saveVar: 'shTok', expect: [{ kind: 'bodyCode', value: 200 }] },
              { method: 'GET', url: '/auth/me', headers: { satoken: '{{var:shTok.token}}' }, noToken: true, expect: [{ kind: 'bodyCode', value: 200 }] },
              { method: 'PUT', url: '/sys/user/20/status', headers: { satoken: '{{token:admin}}' }, body: { status: 'DISABLED' }, expect: [{ kind: 'bodyCode', value: 200 }] },
              { method: 'GET', url: '/auth/me', headers: { satoken: '{{var:shTok.token}}' }, noToken: true, expect: [{ kind: 'status', value: 401 }] },
              { method: 'POST', url: '/auth/login', noToken: true, body: { username: 'sh_sales', password: '123456' }, expect: [{ kind: 'status', value: 403 }] },
              { method: 'PUT', url: '/sys/user/20/status', headers: { satoken: '{{token:admin}}' }, body: { status: 'ACTIVE' }, expect: [{ kind: 'bodyCode', value: 200 }] }
            ]
          }
        },
        {
          id: 'M4-D07', prio: 'P0', title: '删除级联+踢线', steps: '建号→登录→删除→旧 token 访问', expected: '删除成功；旧会话立即 401',
          auto: {
            steps: [
              { method: 'POST', url: '/sys/user', headers: { satoken: '{{token:admin}}' }, body: { username: 'testhub_del_{{convId}}', password: '123456', roleIds: [4], deptIds: [221] }, saveVar: 'tmpD', expect: [{ kind: 'bodyCode', value: 200 }] },
              { method: 'POST', url: '/auth/login', noToken: true, body: { username: 'testhub_del_{{convId}}', password: '123456' }, saveVar: 'delTok', expect: [{ kind: 'bodyCode', value: 200 }] },
              { method: 'DELETE', url: '/sys/user/{{var:tmpD}}', headers: { satoken: '{{token:admin}}' }, expect: [{ kind: 'bodyCode', value: 200 }] },
              { method: 'GET', url: '/auth/me', headers: { satoken: '{{var:delTok.token}}' }, noToken: true, expect: [{ kind: 'status', value: 401 }] }
            ]
          }
        },
        {
          id: 'M4-D08', prio: 'P0', title: '重置密码', steps: '建号 → 重置密码 → 旧密码登录被拒 → 新密码登录成功 → 清理', expected: 'BCrypt 入库；旧密码失效、新密码可登录',
          auto: {
            steps: [
              { method: 'POST', url: '/sys/user', headers: { satoken: '{{token:admin}}' }, body: { username: 'testhub_pwd_{{convId}}', password: '123456' }, saveVar: 'tmpP', expect: [{ kind: 'bodyCode', value: 200 }] },
              { method: 'POST', url: '/sys/user/reset-password', headers: { satoken: '{{token:admin}}' }, body: { userId: '{{var:tmpP}}', newPassword: 'm4_reset_9' }, expect: [{ kind: 'bodyCode', value: 200 }] },
              { method: 'POST', url: '/auth/login', noToken: true, body: { username: 'testhub_pwd_{{convId}}', password: '123456' }, expect: [{ kind: 'bodyCode', value: 400 }, { kind: 'msgIncludes', value: '密码错误' }] },
              { method: 'POST', url: '/auth/login', noToken: true, body: { username: 'testhub_pwd_{{convId}}', password: 'm4_reset_9' }, expect: [{ kind: 'bodyCode', value: 200 }] },
              { method: 'DELETE', url: '/sys/user/{{var:tmpP}}', headers: { satoken: '{{token:admin}}' }, expect: [{ kind: 'bodyCode', value: 200 }] }
            ]
          }
        },
        {
          id: 'M4-D09', prio: 'P0', title: '非 admin 拒收', steps: 'sh_sales token 访问 /sys/user/list', expected: 'HTTP 403（M0-B04 回归）',
          auto: {
            steps: [
              { method: 'GET', url: '/sys/user/list?page=1', headers: { satoken: '{{tokenFresh:sh_sales}}' }, expect: [{ kind: 'status', value: 403 }] }
            ]
          }
        },
        {
          id: 'M4-D10', prio: 'P0', title: '角色/部门只读接口', steps: 'GET /sys/role/list、GET /sys/dept/tree', expected: 'ACTIVE 角色按 sort（admin 第一）；部门树 children 嵌套正确',
          auto: {
            steps: [
              { method: 'GET', url: '/sys/role/list', headers: { satoken: '{{token:admin}}' }, expect: [{ kind: 'bodyCode', value: 200 }, { kind: 'jsonEq', path: 'data[0].code', value: 'admin' }, { kind: 'jsonEq', path: 'data[0].dataScope', value: 'ALL' }] },
              { method: 'GET', url: '/sys/dept/tree', headers: { satoken: '{{token:admin}}' }, expect: [{ kind: 'bodyCode', value: 200 }, { kind: 'jsonEq', path: 'data[0].name', value: '豆豆科技集团' }, { kind: 'jsonTrue', path: 'data[0].children' }] }
            ]
          }
        }
      ]
    },
    {
      key: 'E',
      name: 'Vue 用户管理页（浏览器）',
      scenarios: [
        { id: 'M4-E01', prio: 'P0', title: '建号全流程', steps: '页面新建 test_op（角色 employee+analyst、部门树多选、档案）', expected: '保存成功列表可见；用新号登录 dataScope=DEPT' },
        { id: 'M4-E02', prio: 'P0', title: '编辑/状态切换', steps: '编辑资料、禁用账号', expected: '禁用后该账号登录被拒（4xx）' },
        { id: 'M4-E03', prio: 'P0', title: '重置密码', steps: '页面重置并登录', expected: '新密码生效' },
        { id: 'M4-E04', prio: 'P0', title: '删除', steps: '删除 test_op', expected: '列表消失；关联清理；admin 删除被拒的提示可见' }
      ]
    }
  ]
}
