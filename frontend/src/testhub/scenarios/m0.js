/**
 * M0 测试场景 —— 工程骨架与底座（对应 test/M0-测试用例.md）
 * 里程碑已交付：A/B 组接口类用例可自动运行判分；C 组 SQL、D 组浏览器、E 组构建为手工用例。
 */
export default {
  m: 0,
  delivered: true,
  short: '底座',
  title: 'M0 · 工程骨架与底座',
  theme: '最小认证（BCrypt）、通用设施（R/异常处理/Jackson/分页）、系统表与种子数据、Vue 前端骨架与登录页',
  groups: [
    {
      key: 'A',
      name: '认证接口（/auth/*）',
      scenarios: [
        {
          id: 'M0-A01',
          prio: 'P0',
          title: 'admin 正常登录',
          steps: 'POST /auth/login {"username":"admin","password":"123456"}',
          expected: 'code=200；data 含 roles=[{code:admin}]、depts、dataScope=ALL、profile(王大锤)、token；id 序列化为字符串 "1"',
          auto: {
            steps: [
              {
                method: 'POST',
                url: '/auth/login',
                noToken: true,
                body: { username: 'admin', password: '123456' },
                saveToken: 'admin',
                expect: [
                  { kind: 'status', value: 200 },
                  { kind: 'bodyCode', value: 200 },
                  { kind: 'jsonTrue', path: 'data.token' },
                  { kind: 'jsonIsString', path: 'data.id' },
                  { kind: 'jsonEq', path: 'data.roles[0].code', value: 'admin' },
                  { kind: 'jsonEq', path: 'data.dataScope', value: 'ALL' },
                  { kind: 'jsonTrue', path: 'data.profile' },
                  { kind: 'jsonLen', path: 'data.depts', min: 1 }
                ]
              }
            ]
          }
        },
        {
          id: 'M0-A02',
          prio: 'P0',
          title: '四档 dataScope 账号登录',
          steps: '分别用 mgr_test / analyst_test / sh_sales / cross_analyst 登录',
          expected: '全部 code=200；dataScope 依次 = DEPT_AND_SUB / DEPT / SELF / DEPT',
          auto: {
            steps: [
              { method: 'POST', url: '/auth/login', noToken: true, body: { username: 'mgr_test', password: '123456' }, expect: [{ kind: 'bodyCode', value: 200 }, { kind: 'jsonEq', path: 'data.dataScope', value: 'DEPT_AND_SUB' }] },
              { method: 'POST', url: '/auth/login', noToken: true, body: { username: 'analyst_test', password: '123456' }, expect: [{ kind: 'bodyCode', value: 200 }, { kind: 'jsonEq', path: 'data.dataScope', value: 'DEPT' }] },
              { method: 'POST', url: '/auth/login', noToken: true, body: { username: 'sh_sales', password: '123456' }, expect: [{ kind: 'bodyCode', value: 200 }, { kind: 'jsonEq', path: 'data.dataScope', value: 'SELF' }] },
              { method: 'POST', url: '/auth/login', noToken: true, body: { username: 'cross_analyst', password: '123456' }, expect: [{ kind: 'bodyCode', value: 200 }, { kind: 'jsonEq', path: 'data.dataScope', value: 'DEPT' }] }
            ]
          }
        },
        {
          id: 'M0-A03',
          prio: 'P0',
          title: '跨部门账号返回多部门',
          steps: 'cross_analyst 登录查看 depts',
          expected: '返回 2 个部门：上海销售部、杭州销售部，path 各自完整（四级链）',
          auto: {
            steps: [
              { method: 'POST', url: '/auth/login', noToken: true, body: { username: 'cross_analyst', password: '123456' }, expect: [{ kind: 'bodyCode', value: 200 }, { kind: 'jsonLen', path: 'data.depts', min: 2, max: 2 }, { kind: 'jsonMatch', path: 'data.depts[0].name', value: '.+' }, { kind: 'jsonMatch', path: 'data.depts[1].name', value: '.+' }, { kind: 'jsonLen', path: 'data.depts[0].path', min: 3 }, { kind: 'jsonLen', path: 'data.depts[1].path', min: 3 }] }
            ]
          }
        },
        {
          id: 'M0-A04',
          prio: 'P0',
          title: '密码错误',
          steps: 'POST /auth/login {"username":"admin","password":"wrong"}',
          expected: 'code=400，msg=密码错误（BCrypt 拒绝）',
          auto: {
            steps: [
              { method: 'POST', url: '/auth/login', noToken: true, body: { username: 'admin', password: 'wrong' }, expect: [{ kind: 'bodyCode', value: 400 }, { kind: 'msgIncludes', value: '密码错误' }] }
            ]
          }
        },
        {
          id: 'M0-A05',
          prio: 'P0',
          title: '用户名不存在',
          steps: 'POST /auth/login {"username":"nobody","password":"x"}',
          expected: 'code=400，msg=用户名不存在',
          auto: {
            steps: [
              { method: 'POST', url: '/auth/login', noToken: true, body: { username: 'nobody', password: 'x' }, expect: [{ kind: 'bodyCode', value: 400 }, { kind: 'msgIncludes', value: '用户名不存在' }] }
            ]
          }
        },
        {
          id: 'M0-A06',
          prio: 'P0',
          title: '参数为空',
          steps: 'POST /auth/login {}',
          expected: 'code=400，msg=用户名或密码不能为空',
          auto: {
            steps: [
              { method: 'POST', url: '/auth/login', noToken: true, body: {}, expect: [{ kind: 'bodyCode', value: 400 }] }
            ]
          }
        },
        {
          id: 'M0-A07',
          prio: 'P1',
          title: '禁用账号登录',
          steps: "先 UPDATE sys_user SET status='DISABLED' WHERE username='sh_sales' 再登录，测完恢复 ACTIVE",
          expected: '提示"账号已被禁用"（当前 HTTP 500，原版遗留行为，M4 修复为 4xx）',
          code: "UPDATE sys_user SET status='DISABLED' WHERE username='sh_sales';\n-- 测试后恢复\nUPDATE sys_user SET status='ACTIVE' WHERE username='sh_sales';"
        },
        {
          id: 'M0-A08',
          prio: 'P0',
          title: '无 token 访问 /auth/me',
          steps: 'curl -i http://localhost:8889/auth/me',
          expected: 'HTTP 401，响应体 {"code":401,"msg":"未登录或登录已过期"}',
          auto: {
            steps: [
              { method: 'GET', url: '/auth/me', noToken: true, expect: [{ kind: 'status', value: 401 }, { kind: 'bodyCode', value: 401 }] }
            ]
          }
        },
        {
          id: 'M0-A09',
          prio: 'P0',
          title: '带 token 访问 /auth/me',
          steps: 'curl -H "satoken: $TOKEN" /auth/me（测试引擎自动登录 admin 取 token）',
          expected: 'code=200，返回当前用户完整信息（与登录响应 data 一致）',
          auto: {
            steps: [
              { method: 'GET', url: '/auth/me', headers: { satoken: '{{token:admin}}' }, expect: [{ kind: 'status', value: 200 }, { kind: 'bodyCode', value: 200 }, { kind: 'jsonEq', path: 'data.username', value: 'admin' }] }
            ]
          }
        },
        {
          id: 'M0-A10',
          prio: 'P1',
          title: '伪造 token',
          steps: 'curl -i -H "satoken: fake-token" /auth/me',
          expected: 'HTTP 401',
          auto: {
            steps: [
              { method: 'GET', url: '/auth/me', headers: { satoken: 'fake-token' }, expect: [{ kind: 'status', value: 401 }] }
            ]
          }
        },
        {
          id: 'M0-A11',
          prio: 'P0',
          title: 'logout 后会话失效',
          steps: '新登录取 token → POST /auth/logout → 原token再访问 /auth/me（用独立 token，不影响当前页面登录态）',
          expected: 'logout 返回 200；随后 /auth/me 返回 401（Redis 会话已删）',
          auto: {
            steps: [
              { method: 'POST', url: '/auth/login', noToken: true, body: { username: 'admin', password: '123456' }, saveVar: 'tmpLogin', expect: [{ kind: 'bodyCode', value: 200 }] },
              { method: 'POST', url: '/auth/logout', headers: { satoken: '{{var:tmpLogin.token}}' }, expect: [{ kind: 'bodyCode', value: 200 }] },
              { method: 'GET', url: '/auth/me', headers: { satoken: '{{var:tmpLogin.token}}' }, expect: [{ kind: 'status', value: 401 }] }
            ]
          }
        },
        {
          id: 'M0-A12',
          prio: 'P2',
          title: 'token 放 header 名 satoken',
          steps: '用 Authorization 头携带有效 token 访问 /auth/me',
          expected: '401（Sa-Token 只认 satoken 头）',
          auto: {
            steps: [
              { method: 'GET', url: '/auth/me', headers: { Authorization: 'Bearer {{token:admin}}' }, expect: [{ kind: 'status', value: 401 }] }
            ]
          }
        }
      ]
    },
    {
      key: 'B',
      name: '通用设施（异常处理 / Jackson / 拦截规则）',
      scenarios: [
        {
          id: 'M0-B01',
          prio: 'P0',
          title: 'R<T> 统一结构',
          steps: '任意成功/失败响应检查结构',
          expected: '结构恒为 {code, msg, data, success}；成功 code=200',
          auto: {
            steps: [
              { method: 'POST', url: '/auth/login', noToken: true, body: { username: 'admin', password: '123456' }, expect: [{ kind: 'jsonKeys', path: '', value: ['code', 'msg', 'data'] }, { kind: 'jsonEq', path: 'code', value: 200 }] },
              { method: 'POST', url: '/auth/login', noToken: true, body: { username: 'admin', password: 'wrong' }, expect: [{ kind: 'jsonKeys', path: '', value: ['code', 'msg'] }, { kind: 'jsonEq', path: 'code', value: 400 }] }
            ]
          }
        },
        {
          id: 'M0-B02',
          prio: 'P0',
          title: 'Long→String 序列化',
          steps: '登录响应检查 id / roles[].id / depts[].id 类型',
          expected: '全部为字符串类型（雪花 ID 防 JS 精度丢失）',
          auto: {
            steps: [
              { method: 'POST', url: '/auth/login', noToken: true, body: { username: 'admin', password: '123456' }, expect: [{ kind: 'jsonIsString', path: 'data.id' }, { kind: 'jsonIsString', path: 'data.roles[0].id' }, { kind: 'jsonIsString', path: 'data.depts[0].id' }] }
            ]
          }
        },
        {
          id: 'M0-B03',
          prio: 'P0',
          title: 'NotLogin → HTTP 401',
          steps: '同 M0-A08（HTTP 状态码必须是 401，不只业务码）',
          expected: 'HTTP 状态码 401，前端拦截器据此跳登录',
          auto: {
            steps: [
              { method: 'GET', url: '/auth/me', noToken: true, expect: [{ kind: 'status', value: 401 }] }
            ]
          }
        },
        {
          id: 'M0-B04',
          prio: 'P0',
          title: 'NotRole → HTTP 403',
          steps: 'sh_sales 的 token 访问 GET /sys/role/list',
          expected: 'HTTP 403 {"code":403,"msg":"无权限访问"}（拦截器先于路由）',
          auto: {
            steps: [
              { method: 'GET', url: '/sys/role/list', headers: { satoken: '{{token:sh_sales}}' }, expect: [{ kind: 'status', value: 403 }, { kind: 'bodyCode', value: 403 }] }
            ]
          }
        },
        {
          id: 'M0-B05',
          prio: 'P1',
          title: 'admin 访问 /sys/** 放行角色检查',
          steps: 'admin token 访问 GET /sys/role/list',
          expected: '通过角色检查（返回 404/兜底，因为接口 M4 才实现——不应是 403）',
          auto: {
            steps: [
              { method: 'GET', url: '/sys/role/list', headers: { satoken: '{{token:admin}}' }, expect: [{ kind: 'statusNot', value: 403 }] }
            ]
          }
        },
        {
          id: 'M0-B06',
          prio: 'P0',
          title: '参数错误 → 400',
          steps: '见 M0-A04/A05/A06（可依次重跑三个用例）',
          expected: 'R{400}'
        },
        {
          id: 'M0-B07',
          prio: 'P2',
          title: '兜底异常 → 500',
          steps: '（库不可用时）任意登录请求',
          expected: 'R{500} 且后端 log.error 打印堆栈'
        },
        {
          id: 'M0-B08',
          prio: 'P0',
          title: '/auth/login 免登录放行',
          steps: '不带 token POST /auth/login（空参数触发 400）',
          expected: '不被拦截器 401（登录接口本身可达，返回业务码 400）',
          auto: {
            steps: [
              { method: 'POST', url: '/auth/login', noToken: true, body: {}, expect: [{ kind: 'statusNot', value: 401 }, { kind: 'bodyCode', value: 400 }] }
            ]
          }
        },
        {
          id: 'M0-B09',
          prio: 'P2',
          title: '静态资源扩展名跳过登录',
          steps: 'curl -i http://localhost:8889/foo.png',
          expected: '非 401（404 即可，为生产托管前端产物预留）',
          auto: {
            steps: [
              { method: 'GET', url: '/foo.png', noToken: true, expect: [{ kind: 'statusNot', value: 401 }] }
            ]
          }
        }
      ]
    },
    {
      key: 'C',
      name: '数据库与种子数据（SQL 直查 · 手工）',
      scenarios: [
        { id: 'M0-C01', prio: 'P0', title: '华西业务数据量抽查', steps: 'SELECT COUNT(*) FROM doc_project WHERE deleted=0; 等', expected: 'doc_project≥20、doc_warning_event>0、doc_file_entry>0、system_users>0（活库数字会增长，只验连通性与量级）', code: "SELECT 'doc_project' t, COUNT(*) FROM doc_project WHERE deleted=0\nUNION ALL SELECT 'doc_warning_event', COUNT(*) FROM doc_warning_event\nUNION ALL SELECT 'doc_file_entry', COUNT(*) FROM doc_file_entry\nUNION ALL SELECT 'system_users', COUNT(*) FROM system_users WHERE deleted=0;" },
        { id: 'M0-C02', prio: 'P0', title: '业务表口径到位', steps: 'SELECT DISTINCT status FROM doc_project WHERE deleted=0; 与 category', expected: 'status/category 为字典码值（0/1 等，配合 system_dict_data 翻译），不出现自由文本', code: 'SELECT DISTINCT status FROM doc_project WHERE deleted=0 ORDER BY status;\nSELECT DISTINCT category FROM doc_project WHERE deleted=0;' },
        { id: 'M0-C03', prio: 'P1', title: '部门树完整', steps: 'SELECT id, name, parent_id FROM system_dept WHERE deleted=0; 抽查父子链', expected: '根节点为华西集团；子部门 parent_id 正确上溯', code: "SELECT id, name, parent_id FROM system_dept WHERE parent_id = 0 AND deleted = 0;\nSELECT id, name, parent_id FROM system_dept WHERE deleted = 0 ORDER BY parent_id LIMIT 20;" },
        { id: 'M0-C04', prio: 'P0', title: '密码全部 BCrypt', steps: 'SELECT username, password FROM system_users WHERE deleted=0;', expected: 'password 均为 $2a$10$... 60 位哈希，无明文；password=123456 计数为 0', code: "SELECT username, password FROM system_users WHERE deleted=0;\nSELECT COUNT(*) FROM system_users WHERE password='123456';" },
        { id: 'M0-C05', prio: 'P0', title: '角色数据范围档齐备', steps: 'SELECT code, data_scope FROM system_role WHERE deleted=0;', expected: 'data_scope 为 yudao 五档码值（1全部/2自定义/3本部门及以下/4本部门/5仅本人）', code: 'SELECT code, data_scope FROM system_role WHERE deleted=0;' },
        { id: 'M0-C06', prio: 'P2', title: 'agentx_* 平台表就位', steps: "SELECT tablename FROM pg_tables WHERE tablename LIKE 'agentx%';", expected: '出现 agentx_conversation/agentx_session/agentx_trace 等七张平台表（启动时自动建表）', code: "SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename LIKE 'agentx%' ORDER BY tablename;" }
      ]
    },
    {
      key: 'D',
      name: '前端（浏览器 · 手工）',
      scenarios: [
        { id: 'M0-D01', prio: 'P0', title: '登录页渲染', steps: '打开 http://localhost:517x/login', expected: '标题"数据分析智能体"、用户名/密码输入框（自动聚焦）、登录按钮、默认账号提示' },
        { id: 'M0-D02', prio: 'P0', title: '空表单前端校验', steps: '不填直接点"登 录"', expected: '出现"请输入用户名和密码"，不发请求' },
        { id: 'M0-D03', prio: 'P1', title: '密码显隐切换', steps: '点密码框右侧眼睛图标', expected: '密码明文/密文切换' },
        { id: 'M0-D04', prio: 'P0', title: 'admin 登录进主框架', steps: '填 admin/123456 提交', expected: '跳转主框架；首页表格展示用户名/角色/部门/数据范围/档案；顶栏"超级管理员"；"登录成功"提示' },
        { id: 'M0-D05', prio: 'P0', title: '登录失败提示', steps: '填 admin/错误密码', expected: '登录页内联错误提示"密码错误"，不跳转' },
        { id: 'M0-D06', prio: 'P1', title: '已登录访问 /login', steps: '登录后手动访问 /login', expected: '自动跳回主框架' },
        { id: 'M0-D07', prio: 'P0', title: '未登录访问受保护路由', steps: '退出后直接访问 http://localhost:517x/', expected: '路由守卫跳转 /login' },
        { id: 'M0-D08', prio: 'P1', title: 'token 失效自动跳登录', steps: '手工删 Redis 会话后再点页面内操作', expected: 'axios 拦截器捕获 401 → 清 token → 跳登录页' },
        { id: 'M0-D09', prio: 'P0', title: '退出登录闭环', steps: '顶栏头像下拉 → 退出登录', expected: '回登录页；回退/直访主页被守卫拦截' },
        { id: 'M0-D10', prio: 'P1', title: '里程碑占位菜单', steps: '主框架左侧菜单', expected: '首页可用；未交付里程碑菜单为禁用态带标签；测试功能区 M0-M8 可进入' },
        { id: 'M0-D11', prio: 'P0', title: 'Vite proxy 生效', steps: '通过前端端口访问 /auth/me（本用例即走 5173 代理执行）', expected: '转发到 8889：无 token 返回 401，而非前端 index.html', auto: { steps: [{ method: 'GET', url: '/auth/me', noToken: true, expect: [{ kind: 'status', value: 401 }, { kind: 'bodyCode', value: 401 }] }] } },
        { id: 'M0-D12', prio: 'P1', title: '生产构建', steps: 'cd frontend && npm run build', expected: '构建成功产出 dist/', code: 'cd frontend && npm run build' }
      ]
    },
    {
      key: 'E',
      name: '工程与构建（手工）',
      scenarios: [
        { id: 'M0-E01', prio: 'P0', title: '双模块构建+单测', steps: 'cd backend && mvn clean package', expected: 'BUILD SUCCESS；BCryptTest(2)、DataScopeTest(2) 全绿', code: 'cd backend && mvn clean package' },
        { id: 'M0-E02', prio: 'P0', title: 'fat jar 启动', steps: 'java -jar data-agent-server/target/data-agent-server-1.0.0-SNAPSHOT.jar', expected: '~5s 启动完成，Tomcat 8889，无 Bean 冲突/告警', code: 'java -jar data-agent-server/target/data-agent-server-1.0.0-SNAPSHOT.jar' }
      ]
    }
  ]
}
