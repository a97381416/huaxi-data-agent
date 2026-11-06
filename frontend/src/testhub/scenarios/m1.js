/**
 * M1 测试场景 —— 自研 Agent 内核 mini-agentx-core（对应 test/M1-测试用例.md）
 * 里程碑已交付：A/C/F 组流式用例走 /agent/demo/stream；D 组落库查证走只读接口 /agent/demo/persist-check；
 * C02 用二次 stop 代理验证任务清理。仍为手工：B01-B07/E 组解析器与 JSON 修复单测（JVM 内跑，卡片带一键复制命令）、
 * A03/A04/A05 构造模型流单测、A06/F02/F03/G01 代码审查与配置类。
 * 注意：真实链路需要 MiniMax API Key（application-local.yml spring.ai.openai.*）。
 */
export default {
  m: 1,
  delivered: true,
  short: '内核',
  title: 'M1 · 自研 Agent 内核 mini-agentx-core',
  theme: 'ReAct 循环、7 种流式事件、思考流解析、会话三表、打断、trace、结构化输出、MiniMax M3.1-Flash 接入',
  groups: [
    {
      key: 'A',
      name: 'ReAct 主循环与流式事件',
      scenarios: [
        {
          id: 'M1-A01',
          prio: 'P0',
          title: '全链路事件顺序（echo 工具）',
          steps: '提问触发 echo 工具，消费完整流',
          expected: '事件顺序 tool_start → tool_end → complete（TEXT 先于工具）；流以 Complete 收尾；正文无 <think> 标签泄漏',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/demo/stream',
                body: { query: "请调用 echo 工具，参数文本传 kernel-test-ok，然后把工具返回的结果原样告诉我。", conversationId: '{{convId}}' },
                expect: [
                  { kind: 'contentTypeIncludes', value: 'text/event-stream' },
                  { kind: 'eventTypeMin', type: 'TEXT', min: 1 },
                  { kind: 'toolNameCalled', value: 'echo' },
                  { kind: 'orderContains', value: ['TOOL_START', 'TOOL_END'] },
                  { kind: 'terminal', value: 'COMPLETE' },
                  { kind: 'textExcludes', value: '<think>|</think>' }
                ]
              }
            ]
          },
          note: 'THINKING 是否出现取决于模型（M3 偶发跳过思考），不作硬断言；思考解析由 B-STREAM 与 B01-B07 单测覆盖'
        },
        {
          id: 'M1-A02',
          prio: 'P0',
          title: '工具失败不终止循环（boom）',
          steps: '提问触发必失败工具 boom',
          expected: '错误 JSON 回灌给模型；模型下一轮自我修正后正常 complete（循环未中断）。详情里检查 TOOL_END 携带的错误内容',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/demo/stream',
                body: { query: '请调用 boom 工具（它必定失败），然后把失败原因用一句话告诉我。', conversationId: '{{convId}}' },
                expect: [
                  { kind: 'toolNameCalled', value: 'boom' },
                  { kind: 'terminal', value: 'COMPLETE' },
                  { kind: 'textLenMin', min: 1 }
                ]
              }
            ]
          }
        },
        {
          id: 'M1-A03',
          prio: 'P0',
          title: 'toolCall 分片合并',
          steps: 'ScriptedChatModel 单测：一次 tool_call 的 arguments 分 3 个 chunk 到达',
          expected: '按 id 合并分片后得到完整合法 JSON arguments，工具执行一次',
          code: 'cd backend && mvn test -pl mini-agentx-core -Dtest=AgentLoopTest'
        },
        {
          id: 'M1-A04',
          prio: 'P1',
          title: '多工具并发+按序回灌',
          steps: '单测：一轮内 3 个 toolCall（含 sleep 1s 慢工具）',
          expected: '并发执行（总耗时≈最慢者）；tool_result 按原始顺序回灌',
          code: 'cd backend && mvn test -pl mini-agentx-core -Dtest=AgentLoopTest'
        },
        {
          id: 'M1-A05',
          prio: 'P0',
          title: 'maxRounds 兜底',
          steps: '单测：maxRounds=2 提多轮问题',
          expected: '第 2 轮后 forceFinal 收尾，流 Complete 而非死循环',
          code: 'cd backend && mvn test -pl mini-agentx-core -Dtest=AgentLoopTest'
        },
        {
          id: 'M1-A06',
          prio: 'P1',
          title: '框架不自动执行工具',
          steps: '代码审查 + 行为验证',
          expected: 'internalToolExecutionEnabled(false) 生效，工具调用永远由内核线程执行'
        },
        {
          id: 'M1-A07',
          prio: 'P0',
          title: '纯文本响应无工具事件',
          steps: '提问"直接回答 1+1 等于几？不要使用任何工具"',
          expected: '仅 text → complete，无 tool_start',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/demo/stream',
                body: { query: '请直接回答 1+1 等于几？不要使用任何工具。', conversationId: '{{convId}}' },
                expect: [
                  { kind: 'noEventType', value: 'TOOL_START' },
                  { kind: 'terminal', value: 'COMPLETE' },
                  { kind: 'textLenMin', min: 1 }
                ]
              }
            ]
          }
        }
      ]
    },
    {
      key: 'B',
      name: 'THINK_TAG 思考解析（B-STREAM 真实流验证；B01-B07 单测固化）',
      scenarios: [
        {
          id: 'M1-B-STREAM',
          prio: 'P0',
          title: '真实流思考/正文分离（跨 chunk 解析代理验证）',
          steps: '真实流提问（引导模型思考），断言：有 THINKING 事件、正文无 &lt;think&gt; 标签泄漏、流 Complete（真实思考流数百 chunk，即跨 chunk 前缀匹配的生产验证）',
          expected: 'thinking 与 text 分离输出，标签不泄漏到正文；边界拆分细节由 B01-B07 单测固化',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/demo/stream',
                body: { query: '请先仔细思考再回答：什么是 ReAct 循环？它解决什么问题？', conversationId: '{{convId}}' },
                timeoutMs: 120000,
                expect: [
                  { kind: 'eventTypeMin', type: 'THINKING', min: 1 },
                  { kind: 'eventTypeMin', type: 'TEXT', min: 1 },
                  { kind: 'textExcludes', value: '<think>|</think>' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              }
            ]
          },
          note: 'M3 偶发跳过思考会导致 THINKING 断言失败，遇到时直接重跑一次即可'
        },
        { id: 'M1-B01', prio: 'P0', title: '标签完整单 chunk', steps: '单测：content=<think>推理</think>答案', expected: 'thinking 输出"推理"，text 输出"答案"，标签不泄漏正文' },
        { id: 'M1-B02', prio: 'P0', title: '标签跨 chunk 拆分', steps: '单测：<th / ink>推 / 理</think>答 分片', expected: '跨 chunk 前缀匹配状态机解析正确，不丢字不多字' },
        { id: 'M1-B03', prio: 'P0', title: '标签拆在闭合处', steps: '单测：...</th + ink>text', expected: '解析正确' },
        { id: 'M1-B04', prio: 'P1', title: '带属性变体', steps: '单测：<think lang="zh">…', expected: '兼容解析' },
        { id: 'M1-B05', prio: 'P0', title: '无思考内容', steps: '单测：content 无标签', expected: '只有 text 事件' },
        { id: 'M1-B06', prio: 'P1', title: '只有思考无正文', steps: '单测：<think>…</think> 后流结束', expected: 'thinking 后正常 complete，不报错' },
        { id: 'M1-B07', prio: 'P1', title: '前缀悬挂缓冲释放', steps: '单测：流结束缓冲区残留 <thi', expected: '残留作为正文 text 发出，不吞字' },
        {
          id: 'M1-B-ALL',
          prio: 'P0',
          title: 'B 组全部单测（一键回归）',
          steps: 'mvn test -Dtest=ThinkTagParserTest（11 例）',
          expected: '全绿',
          code: 'cd backend && mvn test -pl mini-agentx-core -Dtest=ThinkTagParserTest'
        }
      ]
    },
    {
      key: 'C',
      name: '打断（interrupt）',
      scenarios: [
        {
          id: 'M1-C01',
          prio: 'P0',
          title: '中途打断收 Paused',
          steps: '发起长任务流，3 秒后调 /agent/demo/stop，再查快照',
          expected: '流以 Paused 事件收尾；快照写入（hasSnapshot=true，safePoint=LLM_STREAMING 等）',
          auto: {
            steps: [
              {
                kind: 'sseStop',
                stopAfterMs: 3000,
                sse: {
                  url: '/agent/demo/stream',
                  body: { query: '请从 1 慢慢数到 50，每行一个数字，每数 5 个调用一次 echo 工具确认进度，不要提前结束。', conversationId: '{{convId}}' }
                },
                stop: { url: '/agent/demo/stop?conversationId={{convId}}' },
                snapshot: { url: '/agent/demo/snapshot?conversationId={{convId}}' },
                expect: [
                  { kind: 'terminal', value: 'PAUSED' },
                  { kind: 'snapshotTrue' }
                ]
              }
            ]
          }
        },
        {
          id: 'M1-C02',
          prio: 'P1',
          title: '打断后任务登记清理（二次 stop 代理验证）',
          steps: '长任务流 → stop（Paused）→ 对同一会话再次 stop',
          expected: '第二次 stop 返回 stopped=false 且 hasInterruptState=true（任务登记已清理，快照仍在）',
          auto: {
            steps: [
              {
                kind: 'sseStop',
                stopAfterMs: 3000,
                sse: {
                  url: '/agent/demo/stream',
                  body: { query: '请从 1 慢慢数到 50，每行一个数字，每数 5 个调用一次 echo 工具确认进度，不要提前结束。', conversationId: '{{convId}}' }
                },
                stop: { url: '/agent/demo/stop?conversationId={{convId}}' },
                expect: [{ kind: 'terminal', value: 'PAUSED' }]
              },
              {
                method: 'GET',
                url: '/agent/demo/stop?conversationId={{convId}}',
                expect: [
                  { kind: 'bodyCode', value: 200 },
                  { kind: 'jsonEq', path: 'data.stopped', value: false },
                  { kind: 'jsonEq', path: 'data.hasInterruptState', value: true }
                ]
              }
            ]
          },
          note: 'AgentTaskManager 内部列表无查询接口，用"二次 stop 幂等返回 stopped=false"作代理断言'
        },
        {
          id: 'M1-C03',
          prio: 'P2',
          title: '未运行会话打断',
          steps: '对不存在的 conversationId 调 /agent/demo/stop',
          expected: '幂等无异常（stopped=false）',
          auto: {
            steps: [
              { method: 'GET', url: '/agent/demo/stop?conversationId=not_running_{{uuid}}', expect: [{ kind: 'bodyCode', value: 200 }, { kind: 'jsonEq', path: 'data.stopped', value: false }] }
            ]
          }
        }
      ]
    },
    {
      key: 'D',
      name: '持久化（三表 + trace · persist-check 自动查证）',
      scenarios: [
        {
          id: 'M1-D01',
          prio: 'P0',
          title: '三表落库可查（自动）',
          steps: '跑一次带工具的完整对话 → GET /agent/demo/persist-check 断言 conversation/session 落库',
          expected: 'agentx_conversation 一行（status=completed、completed_at 有值）；agentx_session 双视图行数正确、item_index 从 0 连续',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/demo/stream',
                body: { query: "请调用 echo 工具，参数文本传 persist-check-01，然后告诉我工具返回了什么。", conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [{ kind: 'terminal', value: 'COMPLETE' }]
              },
              {
                method: 'GET',
                url: '/agent/demo/persist-check?conversationId={{convId}}',
                expect: [
                  { kind: 'jsonTrue', path: 'data.conversation.found' },
                  { kind: 'jsonEq', path: 'data.conversation.status', value: 'completed' },
                  { kind: 'jsonTrue', path: 'data.conversation.hasCompletedAt' },
                  { kind: 'jsonNumMin', path: 'data.session.originalMessageCount', min: 2 },
                  { kind: 'jsonNumMin', path: 'data.session.workingCount', min: 1 },
                  { kind: 'jsonTrue', path: 'data.session.itemIndexContiguous' }
                ]
              }
            ]
          }
        },
        { id: 'M1-D02', prio: 'P1', title: '循环期零写入', steps: '对话进行中观察表（时序观察，无法页面代跑）', expected: '执行期间无 insert，终态一次落库', code: "-- 对话进行中反复执行，行数应不变；Complete 后才增长\nSELECT COUNT(*) FROM agentx_session;" },
        { id: 'M1-D03', prio: 'P1', title: 'CAS 幂等', steps: '同一 sessionId 重复落库（内部 CAS 机制，无法从接口层构造）', expected: '不产生重复行', code: "SELECT session_id, COUNT(*) c FROM agentx_conversation GROUP BY session_id HAVING c > 1;\n-- 应返回空集" },
        {
          id: 'M1-D04',
          prio: 'P0',
          title: 'trace 完整（自动）',
          steps: '跑一次完整对话 → persist-check 断言 agentx_trace 每轮字段齐全',
          expected: '每轮 LLM 调用一行：input/output/tokens/duration 齐全、success=1',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/demo/stream',
                body: { query: "请调用 echo 工具，参数文本传 persist-check-02，然后告诉我工具返回了什么。", conversationId: '{{convId}}' },
                timeoutMs: 180000,
                expect: [{ kind: 'terminal', value: 'COMPLETE' }]
              },
              {
                method: 'GET',
                url: '/agent/demo/persist-check?conversationId={{convId}}',
                expect: [
                  { kind: 'jsonNumMin', path: 'data.trace.rounds', min: 1 },
                  { kind: 'jsonNumMin', path: 'data.trace.successRounds', min: 1 },
                  { kind: 'jsonTrue', path: 'data.trace.allHaveInputOutput' },
                  { kind: 'jsonTrue', path: 'data.trace.allHaveTokens' },
                  { kind: 'jsonTrue', path: 'data.trace.allHaveDuration' }
                ]
              }
            ]
          }
        },
        {
          id: 'M1-D05',
          prio: 'P1',
          title: '打断后持久化（自动）',
          steps: '长任务流 3 秒后打断（Paused）→ persist-check 断言 status=interrupted',
          expected: 'conversation.status=interrupted；快照存在（内存快照由 C01 的 hasSnapshot 断言）',
          auto: {
            steps: [
              {
                kind: 'sseStop',
                stopAfterMs: 3000,
                sse: {
                  url: '/agent/demo/stream',
                  body: { query: '请从 1 慢慢数到 60，每行一个数字，不要提前结束。', conversationId: '{{convId}}' }
                },
                stop: { url: '/agent/demo/stop?conversationId={{convId}}' },
                expect: [{ kind: 'terminal', value: 'PAUSED' }]
              },
              {
                method: 'GET',
                url: '/agent/demo/persist-check?conversationId={{convId}}',
                expect: [
                  { kind: 'jsonTrue', path: 'data.conversation.found' },
                  { kind: 'jsonEq', path: 'data.conversation.status', value: 'interrupted' }
                ]
              }
            ]
          }
        }
      ]
    },
    {
      key: 'E',
      name: '结构化输出（单测）',
      scenarios: [
        { id: 'M1-E01', prio: 'P0', title: '合法 JSON', steps: '单测：outputType 指定 record', expected: '直接反序列化成功' },
        { id: 'M1-E02', prio: 'P0', title: '带 markdown 围栏', steps: '单测：模型返回 ```json …```', expected: 'JsonRepairUtil 剥离围栏后解析成功' },
        { id: 'M1-E03', prio: 'P1', title: '尾逗号/单引号', steps: '单测：非严格 JSON', expected: '修复后解析成功或显式失败（不静默 null）' },
        { id: 'M1-E-ALL', prio: 'P0', title: 'E 组全部单测（一键回归）', steps: 'mvn test -Dtest=JsonRepairUtilTest（10 例）', expected: '全绿', code: 'cd backend && mvn test -pl mini-agentx-core -Dtest=JsonRepairUtilTest' }
      ]
    },
    {
      key: 'F',
      name: '模型接入（MiniMax M3.1-Flash 真实调用）',
      scenarios: [
        {
          id: 'M1-F01',
          prio: 'P0',
          title: '真实流式对话',
          steps: '任意闲聊问题（需配置 MiniMax API Key）',
          expected: '正常出 text、无 401/超时、流 Complete；正文无 <think> 标签泄漏',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/demo/stream',
                body: { query: '你好，用一句话介绍你自己。', conversationId: '{{convId}}' },
                expect: [
                  { kind: 'terminal', value: 'COMPLETE' },
                  { kind: 'textLenMin', min: 5 },
                  { kind: 'textExcludes', value: '<think>|</think>' }
                ]
              }
            ]
          },
          note: 'THINKING 是否出现取决于模型（M3 偶发跳过思考），不作硬断言'
        },
        { id: 'M1-F02', prio: 'P1', title: 'API Key 错误', steps: '配置错 key 后提问', expected: 'Error 事件携带可读错误（鉴权失败），进程不崩' },
        { id: 'M1-F03', prio: 'P1', title: '网络断开', steps: '断网提问', expected: 'Error 事件收尾，流不悬挂' }
      ]
    },
    {
      key: 'G',
      name: '内核纯度（架构约束）',
      scenarios: [
        { id: 'M1-G01', prio: 'P0', title: '零业务依赖', steps: 'mvn dependency:tree -pl mini-agentx-core', expected: '无 data-agent-server / 业务包依赖', code: 'cd backend && mvn dependency:tree -pl mini-agentx-core' }
      ]
    }
  ]
}
