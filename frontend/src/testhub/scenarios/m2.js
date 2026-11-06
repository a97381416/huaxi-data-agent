/**
 * M2 测试场景 —— NL2SQL 最小闭环（对应 test/M2-测试用例.md）
 * M2 已交付：SSE 用例走生产链路真实判分。
 * 自动化方式：走生产 POST /agent/stream，对 SSE 工具事件序列/工具结果/最终回答做断言；
 * 单条走真实模型链路约 30~120 秒（timeoutMs 180s），建议逐条运行。
 * 保留手工：A03（Redis 日志）、A05/B09/B10/B12（改配置/构造边缘 SQL/代码审查）、D03、E07-20（自补题）、F 组（页面 UI）。
 */
export default {
  m: 2,
  delivered: true,
  short: 'NL2SQL',
  title: 'M2 · NL2SQL 最小闭环',
  theme: 'M-Schema 体系、SQL 安全链基础版、领域工具集、/agent/stream SSE、data-analysis 技能、Vue 对话页基础版',
  groups: [
    {
      key: 'A',
      name: 'M-Schema 体系（SSE 工具断言）',
      scenarios: [
        {
          id: 'M2-A01',
          prio: 'P0',
          title: '内省完整（listTables）',
          steps: '对话页问"库里有哪些表"，断言 listTables 被调用且结果含 sakila 表',
          expected: '返回 sakila 全部表（排除 agentx_*），含表注释',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '库里有哪些表？', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'toolCalledAny', value: ['listTables'] },
                  { kind: 'toolResultIncludes', tool: 'listTables', value: 'film' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              }
            ]
          }
        },
        {
          id: 'M2-A02',
          prio: 'P0',
          title: '示例值采集（describeTables）',
          steps: '问"film 表结构是什么"，断言 describeTables 结果含字段与示例值',
          expected: 'VARCHAR/ENUM 字段带 ≤5 个真实示例值；单值超 30 字符截断；TEXT/敏感列无示例值（示例值细节在事件明细中人工复核）',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: 'film 表结构是什么？', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'toolCalledAny', value: ['describeTables'] },
                  { kind: 'toolResultIncludes', tool: 'describeTables', value: 'title' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              }
            ]
          }
        },
        {
          id: 'M2-A03',
          prio: 'P1',
          title: 'Redis 缓存命中',
          steps: '首次后再次 listTables，观察后端日志',
          expected: '二次走 Redis key data-agent:mschema:schema（TTL 7 天），不重复内省'
        },
        {
          id: 'M2-A04',
          prio: 'P1',
          title: '外键清单（describeTables rental）',
          steps: '问"rental 表结构/外键"',
          expected: 'FK 信息呈现（inventory_id→inventory 等）',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: 'rental 表结构是什么？有哪些外键？', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'toolCalledAny', value: ['describeTables'] },
                  { kind: 'toolResultIncludes', tool: 'describeTables', value: 'inventory' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              }
            ]
          }
        },
        {
          id: 'M2-A05',
          prio: 'P1',
          title: 'exclude-tables 通配',
          steps: '配置 agentx_* 前缀（需改配置重启）后再问表清单',
          expected: 'agentx_ 前缀表全部隐藏'
        }
      ]
    },
    {
      key: 'B',
      name: 'SQL 安全链（诱导提问 → 工具结果断言拦截）',
      scenarios: [
        {
          id: 'M2-B01',
          prio: 'P0',
          title: '非只读语句拦截',
          steps: '诱导执行 UPDATE，断言 validateSql/executeSql 结果含拒绝理由',
          expected: '拒绝：仅允许 SELECT/WITH',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: "帮我执行这条 SQL：UPDATE film SET title = 'hacked' WHERE film_id = 1", conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'toolCalledAny', value: ['validateSql', 'executeSql'] },
                  { kind: 'toolResultIncludes', tools: ['validateSql', 'executeSql'], value: 'SELECT|WITH|拒绝|不允许|仅允许' },
                  { kind: 'terminal', value: 'COMPLETE' },
                  { kind: 'textExcludes', value: 'UPDATE 成功|更新成功|1 row affected' }
                ]
              }
            ]
          }
        },
        {
          id: 'M2-B02',
          prio: 'P0',
          title: '多语句串联拦截',
          steps: '诱导 SELECT 1; DROP TABLE film',
          expected: '拒绝：恰好 1 条语句',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '执行：SELECT 1; DROP TABLE film', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'toolCalledAny', value: ['validateSql', 'executeSql'] },
                  { kind: 'toolResultIncludes', tools: ['validateSql', 'executeSql'], value: '1 条|单条|语句|statement|仅允许|拒绝' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              }
            ]
          }
        },
        {
          id: 'M2-B03',
          prio: 'P0',
          title: '危险函数黑名单',
          steps: '诱导 SELECT sleep(5)',
          expected: '拒绝并指名危险函数',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '执行这条 SQL：SELECT sleep(5)', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'toolCalledAny', value: ['validateSql', 'executeSql'] },
                  { kind: 'toolResultIncludes', tools: ['validateSql', 'executeSql'], value: 'sleep|危险|函数|黑名单|拒绝' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              }
            ]
          }
        },
        {
          id: 'M2-B04',
          prio: 'P0',
          title: '写文件通道拦截',
          steps: '诱导 INTO OUTFILE / LOAD_FILE',
          expected: '拒绝',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: "执行：SELECT title FROM film INTO OUTFILE '/tmp/x'", conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'toolCalledAny', value: ['validateSql', 'executeSql'] },
                  { kind: 'toolResultIncludes', tools: ['validateSql', 'executeSql'], value: 'OUTFILE|LOAD_FILE|拒绝|不允许|仅允许' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              }
            ]
          }
        },
        {
          id: 'M2-B05',
          prio: 'P0',
          title: 'JOIN 数超限拦截',
          steps: '诱导 5 表 JOIN',
          expected: '拒绝：JOIN ≤ 3',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '把 film、inventory、rental、payment、customer 五张表 JOIN 起来，列出电影和付款明细', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'toolCalledAny', value: ['validateSql', 'executeSql'] },
                  { kind: 'toolResultIncludes', tools: ['validateSql', 'executeSql'], value: 'JOIN.{0,30}(3|三)|JOIN 数|拒绝' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              }
            ]
          }
        },
        {
          id: 'M2-B06',
          prio: 'P0',
          title: '无 ON 的 JOIN / CROSS JOIN 拦截',
          steps: '诱导笛卡尔积写法',
          expected: '拒绝',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '用 CROSS JOIN 把电影和演员的全部组合列出来', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'toolCalledAny', value: ['validateSql', 'executeSql'] },
                  { kind: 'toolResultIncludes', tools: ['validateSql', 'executeSql'], value: 'ON|JOIN|笛卡尔|拒绝' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              }
            ]
          }
        },
        {
          id: 'M2-B07',
          prio: 'P0',
          title: '缺 LIMIT 自动注入',
          steps: '要求不加 LIMIT 查询，断言 safeSql 被补 LIMIT 200',
          expected: 'safeSql 追加 LIMIT 200',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '列出所有电影标题，生成的 SQL 不要加 LIMIT', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'toolCalledAny', value: ['validateSql', 'executeSql'] },
                  { kind: 'toolResultIncludes', tools: ['validateSql', 'executeSql'], value: 'LIMIT\\s*200' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              }
            ]
          }
        },
        {
          id: 'M2-B08',
          prio: 'P0',
          title: '超限 LIMIT 下调',
          steps: '诱导 LIMIT 10000，断言被下调为 200',
          expected: '下调为 200',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '查询所有电影，SQL 里用 LIMIT 10000', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'toolCalledAny', value: ['validateSql', 'executeSql'] },
                  { kind: 'toolResultIncludes', tools: ['validateSql', 'executeSql'], value: 'LIMIT\\s*200' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              }
            ]
          }
        },
        {
          id: 'M2-B09',
          prio: 'P1',
          title: 'OFFSET 无 ORDER BY 拦截',
          steps: '诱导 LIMIT 10 OFFSET 5 无排序（NL 诱导不稳定，可手工构造）',
          expected: '拒绝'
        },
        {
          id: 'M2-B10',
          prio: 'P1',
          title: 'CTE/UNION/子查询分支覆盖',
          steps: 'WITH 包裹 UPDATE / UNION 分支藏 sleep（需构造特殊提问或单测）',
          expected: '形态递归校验同样拦截'
        },
        {
          id: 'M2-B11',
          prio: 'P0',
          title: '201 行硬限',
          steps: '查 payment 全量，断言 200 行 + truncated 标记',
          expected: '返回 200 行 + truncated=true 标记',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '查询 payment 表的全部记录', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'toolCalledAny', value: ['executeSql'] },
                  { kind: 'toolResultIncludes', tool: 'executeSql', value: 'truncated|200|截断' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              }
            ]
          }
        },
        {
          id: 'M2-B12',
          prio: 'P1',
          title: '只读连接与超时',
          steps: '审查 + 慢查询模拟',
          expected: 'setReadOnly(true)、30s queryTimeout；超时错误分类可读',
          code: 'grep -rn "setReadOnly\\|setQueryTimeout\\|queryTimeout" backend/data-agent-server/src/main/java'
        },
        {
          id: 'M2-B13',
          prio: 'P0',
          title: '错误分类引导重试',
          steps: '问错列名字段，断言执行后仍能完成回答（重试次数在事件明细中复核，预期含失败后自修）',
          expected: '42S22→SCHEMA 提示重新 describeTables；Agent 下一轮依提示自修 SQL 重试并最终成功',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '查一下每部电影的 rental_rate_description 字段有哪些值？', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'toolCalledAny', value: ['executeSql'] },
                  { kind: 'terminal', value: 'COMPLETE' },
                  { kind: 'textLenMin', min: 10 }
                ]
              }
            ]
          },
          note: '完整断言口径：事件明细中应能看到一次执行失败 + 依错误分类自修后重试成功'
        },
        {
          id: 'M2-B14',
          prio: 'P0',
          title: '输出格式化',
          steps: '正常查询，断言工具输出带 ✅/❌、耗时/行数',
          expected: '✅/❌ 前缀、耗时/行数、markdown 表格预览 ≤20 行；0 行给排查建议；>20 行警示',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '每个电影分类有多少部电影？', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'toolCalledAny', value: ['executeSql'] },
                  { kind: 'toolResultIncludes', tool: 'executeSql', value: '✅|❌|行|ms|rows' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              }
            ]
          }
        }
      ]
    },
    {
      key: 'C',
      name: '领域工具（SOP 工具序列断言）',
      scenarios: [
        {
          id: 'M2-C01',
          prio: 'P0',
          title: 'listTables',
          steps: '问"库里有哪些表"',
          expected: '先调 listTables 再回答，表清单正确',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '库里有哪些表？', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'toolCalledAny', value: ['listTables'] },
                  { kind: 'toolResultIncludes', tool: 'listTables', value: 'film' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              }
            ]
          }
        },
        {
          id: 'M2-C02',
          prio: 'P0',
          title: 'describeTables',
          steps: '问"film 表结构是什么"',
          expected: '返回字段/类型/注释/示例值',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: 'film 表结构是什么？', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'toolCalledAny', value: ['describeTables'] },
                  { kind: 'toolResultIncludes', tool: 'describeTables', value: 'title' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              }
            ]
          }
        },
        {
          id: 'M2-C03',
          prio: 'P0',
          title: 'validateSql 先行',
          steps: '数据问题，断言 validateSql 在 executeSql 之前',
          expected: '生成 SQL 后先校验（valid/safeSql/reason）再执行',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '每个电影分类有多少部电影？', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'toolOrderContains', value: ['validateSql', 'executeSql'] },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              }
            ]
          }
        },
        {
          id: 'M2-C04',
          prio: 'P1',
          title: 'calculate',
          steps: '问"film 平均租赁单价×16049 单大概多少钱"',
          expected: 'exp4j 计算正确标量；SUM/AVG 类被推回 SQL（推回行为在事件明细中复核）',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: 'film 平均租赁单价乘以 16049 单大概是多少钱？', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'toolCalledAny', value: ['calculate'] },
                  { kind: 'terminal', value: 'COMPLETE' },
                  { kind: 'textLenMin', min: 5 }
                ]
              }
            ]
          }
        },
        {
          id: 'M2-C05',
          prio: 'P0',
          title: 'currentTime',
          steps: '问"今天几号？本月租金多少？"',
          expected: '相对时间词先调 currentTime 再查库',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '今天几号？本月的租金收入是多少？', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'toolCalledAny', value: ['currentTime'] },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              }
            ]
          }
        },
        {
          id: 'M2-C06',
          prio: 'P0',
          title: 'lookupGlossary 精确匹配',
          steps: '问"时间锚点是什么"',
          expected: '术语/同义词双索引命中；全部术语拼在工具 description（编程式注册在代码审查）',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '时间锚点是什么？', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'toolCalledAny', value: ['lookupGlossary'] },
                  { kind: 'toolResultIncludes', tool: 'lookupGlossary', value: '锚点|anchor|rental_date' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              }
            ]
          }
        },
        {
          id: 'M2-C07',
          prio: 'P0',
          title: 'glossary 强制环节（SOP）',
          steps: '数据问题，断言首个工具是 lookupGlossary',
          expected: 'data-analysis SOP：无条件先查 glossary，再 describeTables→validate→execute',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '每个电影分类有多少部电影？', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'firstToolIs', value: 'lookupGlossary' },
                  { kind: 'toolOrderContains', value: ['validateSql', 'executeSql'] },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              }
            ]
          }
        }
      ]
    },
    {
      key: 'D',
      name: '/agent/stream SSE（直连最小版 · 接口探测）',
      scenarios: [
        {
          id: 'M2-D01',
          prio: 'P0',
          title: 'POST SSE 消费',
          steps: 'POST /agent/stream {query, conversationId}',
          expected: 'SSE 事件流（M1 七种事件）；Content-Type=text/event-stream',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '库里有哪些表？', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'contentTypeIncludes', value: 'text/event-stream' },
                  { kind: 'eventsMin', min: 1 }
                ]
              }
            ]
          }
        },
        {
          id: 'M2-D02',
          prio: 'P1',
          title: 'conversationId 生成',
          steps: 'POST /agent/stream 不传 conversationId',
          expected: '后端生成 dodo_conv_+UUID 并在事件中可见',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '库里有哪些表？' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'contentTypeIncludes', value: 'text/event-stream' },
                  { kind: 'eventsMin', min: 1 }
                ]
              }
            ]
          }
        },
        { id: 'M2-D03', prio: 'P2', title: '断线即断执行（M3 前已知边界）', steps: '中途 Ctrl+C', expected: '执行终止/不续传——本里程碑预期行为，M3 改造后本用例作废' }
      ]
    },
    {
      key: 'E',
      name: '端到端问题集（SSE 全链路 · 验收核心 ≥80%）',
      scenarios: [
        {
          id: 'M2-E01',
          prio: 'P0',
          title: '单表聚合',
          steps: '"每个电影分类有多少部电影？"——断言完成 + markdown 表格 + 覆盖多分类（数值正确性人工抽核）',
          expected: '正确数字（category×film_category 聚合），markdown 表格',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '每个电影分类有多少部电影？', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'terminal', value: 'COMPLETE' },
                  { kind: 'textIncludes', value: '\\|.*\\|' },
                  { kind: 'textIncludes', value: 'Action' },
                  { kind: 'textIncludes', value: 'Sports' }
                ]
              }
            ]
          }
        },
        {
          id: 'M2-E02',
          prio: 'P0',
          title: '多表 JOIN',
          steps: '"收入最高的前 5 部电影及其金额？"',
          expected: 'payment→rental→inventory→film 四表链路正确（金额明细人工抽核）',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '收入最高的前 5 部电影及其金额是多少？', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'toolCalledAny', value: ['executeSql'] },
                  { kind: 'terminal', value: 'COMPLETE' },
                  { kind: 'textIncludes', value: '\\|.*\\|' }
                ]
              }
            ]
          }
        },
        {
          id: 'M2-E03',
          prio: 'P0',
          title: '时间锚点题',
          steps: '"最新的租赁记录是哪天？那天租了几部？"——断言结果落在 sakila 数据期（2005 年）内，即未用 NOW() 口径',
          expected: '基于 MAX(rental_date) 锚点（glossary 口径），不得用 NOW()',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '最新的租赁记录是哪天？那天租了几部？', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'toolCalledAny', value: ['executeSql'] },
                  { kind: 'toolResultIncludes', tool: 'executeSql', value: '2005' },
                  { kind: 'terminal', value: 'COMPLETE' },
                  { kind: 'textIncludes', value: '2005' }
                ]
              }
            ]
          }
        },
        {
          id: 'M2-E04',
          prio: 'P0',
          title: '多轮追问（同会话两步）',
          steps: '第 1 问"最活跃的客户是谁？"，同 conversationId 第 2 问"他一共花多少钱？"',
          expected: '第二轮正确指代（同一 conversationId），不重述全问题',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '最活跃的客户是谁？', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [{ kind: 'terminal', value: 'COMPLETE' }]
              },
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '他一共花多少钱？', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'terminal', value: 'COMPLETE' },
                  { kind: 'textLenMin', min: 10 }
                ]
              }
            ]
          },
          note: '两步共用同一个 conversationId（引擎内 convId 持久），指代是否正确在第二步回答中人工复核'
        },
        {
          id: 'M2-E05',
          prio: 'P0',
          title: '失败自愈',
          steps: '诱导首版 SQL 报错（复杂列名），断言最终仍完成回答',
          expected: 'LLM 依错误分类自修 SQL，重试 ≤3 次成功',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '查一下每部电影的 rental_rate_description 字段有哪些值？', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'terminal', value: 'COMPLETE' },
                  { kind: 'textLenMin', min: 30 }
                ]
              }
            ]
          },
          note: '重试过程在事件明细中人工复核（预期可见一次报错回灌 + 自修重试）'
        },
        {
          id: 'M2-E06',
          prio: 'P0',
          title: '报告结构',
          steps: '断言回答含 markdown 表格；"结论先行、正文无中间过程泄漏"人工复核',
          expected: '结论先行 + 表格 + 附录口径/SQL；正文无中间过程泄漏（过渡语在 thinking）',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '每个电影分类有多少部电影？', conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [
                  { kind: 'terminal', value: 'COMPLETE' },
                  { kind: 'textIncludes', value: '\\|.*\\|' }
                ]
              }
            ]
          }
        },
        { id: 'M2-E07-20', prio: 'P0', title: '扩展题集（补足 15-20 题）', steps: '比值/TopN/分组/日期区间/负例澄清各补几题（自补题走对话页或复制提问）', expected: '汇总通过率 ≥80%', copy: '各分类的平均租赁时长是多久？（按天）' }
      ]
    },
    {
      key: 'F',
      name: 'Vue 对话页基础版（浏览器 · 手工）',
      scenarios: [
        { id: 'M2-F01', prio: 'P0', title: '流式渲染', steps: '对话页提问观察', expected: 'thinking 折叠区实时流、正文 markdown 逐字渲染、工具事件条目（名称+状态）' },
        { id: 'M2-F02', prio: 'P0', title: '多轮会话切换', steps: '两个 conversationId 交替提问', expected: '上下文隔离正确' },
        { id: 'M2-F03', prio: 'P1', title: 'POST SSE 前端消费', steps: '抓包确认', expected: '前端用 fetch ReadableStream 消费 POST SSE（对照原版 chat.js 契约）' },
        { id: 'M2-F04', prio: 'P2', title: '文件上传入口', steps: '页面有上传按钮', expected: '入口可见（真实链路 M5 才通，点击给"即将上线"类提示即可）' }
      ]
    }
  ]
}
