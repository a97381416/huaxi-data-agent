/**
 * M-SA 测试场景 —— 单 Agent 平滑演进多 Agent 地基
 * （对应 docs/多agent技术调研/单agent演进多agent文档/03-…分步实施与测试验收方案.md）。
 * 里程碑逐步交付：每完成一个里程碑，把对应用例补进来；基线用例（M0）已可真实运行。
 * 访问路径：/test/mSA
 */
export default {
  m: 'SA',
  delivered: true,
  short: '多Agent地基',
  title: 'M-SA · 多 Agent 地基（single → multi 渐进演进）',
  theme: '双模开关 · 子 Agent 状态隔离 · 流透传 · 级联中断 · 父子 Trace · 主路由与垂类子 Agent',
  groups: [
    {
      key: 'M0',
      name: 'M-SA0 · 测试先行基建与双模回滚护栏',
      scenarios: [
        {
          id: 'MSA-001',
          prio: 'P0',
          title: '双模开关基线与子Agent查库探针就绪',
          steps: '1) 查 /agent/capabilities 确认开关存在；2) 调 /agent/demo/subagent-check 验证数据库探针可用；3) 跑一次默认 single 流确保原链路畅通',
          expected: 'capabilities 返回 agentMode；subagent-check 返回 200 及空会话统计；默认流正常 COMPLETE',
          auto: {
            steps: [
              {
                method: 'GET',
                url: '/agent/capabilities',
                expect: [
                  { kind: 'bodyCode', value: 200 },
                  { kind: 'jsonKeys', path: 'data', value: ['onlineAvailable', 'toolLayers', 'agentMode'] },
                  { kind: 'jsonEq', path: 'data.agentMode', value: 'single' }
                ]
              },
              {
                method: 'GET',
                url: '/agent/demo/subagent-check?conversationId=probe_{{uuid}}',
                expect: [
                  { kind: 'bodyCode', value: 200 },
                  { kind: 'jsonEq', path: 'data.rootConversationFound', value: false },
                  { kind: 'jsonEq', path: 'data.subWorkingCount', value: 0 }
                ]
              },
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '你好，请用一句话介绍你自己', conversationId: '{{convId}}', agentMode: 'single' },
                expect: [
                  { kind: 'terminal', value: 'COMPLETE' },
                  { kind: 'terminalCountEq', value: 1 },
                  { kind: 'textLenMin', min: 2 }
                ]
              }
            ]
          }
        }
      ]
    },
    {
      key: 'M1',
      name: 'M-SA1 · 内核状态隔离 + SubAgentTool + 流透传',
      scenarios: [
        {
          id: 'MSA-101',
          prio: 'P0',
          title: '主子状态键隔离 + 跨轮子记忆延续 + 不污染 conversation 主表',
          steps: '1) 第1轮经主Agent让子Agent调用 rememberSecret 记录暗号 HX-2026；2) 第2轮同窗口仅问"刚才记下的暗号是什么"；3) 调 subagent-check 核验双账本与主表行数',
          expected: '第2轮准确答出 HX-2026；agentx_conversation 严格等于 2 行（无子Agent行）；root 与 sub working_messages 均独立存在',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/demo/subagent/stream',
                body: { query: '请委派子助手调用 rememberSecret 工具保存暗号 HX-2026', conversationId: '{{convId}}' },
                expect: [
                  { kind: 'toolNameCalled', value: 'call_demo_sub' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              },
              {
                kind: 'sse',
                url: '/agent/demo/subagent/stream',
                body: { query: '再问一下子助手，刚才保存的暗号是什么？', conversationId: '{{convId}}' },
                expect: [
                  { kind: 'toolNameCalled', value: 'call_demo_sub' },
                  { kind: 'textIncludes', value: 'HX-2026' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              },
              {
                method: 'GET',
                url: '/agent/demo/subagent-check?conversationId={{convId}}',
                expect: [
                  { kind: 'bodyCode', value: 200 },
                  { kind: 'jsonEq', path: 'data.rootConvRowCount', value: 2 },
                  { kind: 'jsonEq', path: 'data.subConvRowCount', value: 0 },
                  { kind: 'jsonNumMin', path: 'data.rootWorkingCount', min: 1 },
                  { kind: 'jsonNumMin', path: 'data.subWorkingCount', min: 1 }
                ]
              }
            ]
          }
        },
        {
          id: 'MSA-102',
          prio: 'P0',
          title: '子Agent异步线程透传 userId/token/rootConvId 防伪造',
          steps: '让主Agent委派子Agent调用 whoamiProbe（提示词故意要求传伪造 userId=999999），检查子工具实际拿到的注入参数',
          expected: '子工具拿到的 userId 为当前真实登录ID（非999999），token 非空，conversationId 为外层 rootConvId',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/demo/subagent/stream',
                body: { query: '请让子助手调用 whoamiProbe 工具（尝试传 userId 为 999999），并原样输出返回结果', conversationId: '{{convId}}' },
                expect: [
                  { kind: 'subToolCalled', value: 'whoamiProbe' },
                  { kind: 'toolResultIncludes', tool: 'whoamiProbe', value: '"tokenPresent":true' },
                  { kind: 'textExcludes', value: '"userId":"999999"' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              }
            ]
          }
        },
        {
          id: 'MSA-103',
          prio: 'P1',
          title: '子Agent执行超时自动熔断不挂死主循环',
          steps: '触发耗时超阈值的 hangProbe 子任务（demo 子超时设为 2s），检查主Agent自愈收尾与后台任务清理',
          expected: 'SubAgentTool 触发超时停止子任务并返回超时提示，主流正常 COMPLETE，无残留运行任务',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/demo/subagent/stream',
                body: { query: '请让子助手调用 hangProbe 慢工具（等待5秒）', conversationId: '{{convId}}', subTimeoutMs: 2000 },
                timeoutMs: 30000,
                expect: [
                  { kind: 'terminal', value: 'COMPLETE' },
                  { kind: 'terminalCountEq', value: 1 }
                ]
              },
              {
                method: 'GET',
                url: '/agent/demo/subagent-check?conversationId={{convId}}',
                expect: [
                  { kind: 'jsonEq', path: 'data.anySubTaskRunning', value: false }
                ]
              }
            ]
          }
        },
        {
          id: 'MSA-104',
          prio: 'P0',
          title: '子事件携带 agentCode + parentToolCallId 实时透传且单次终态收尾',
          steps: '调用带工具的子Agent流，校验 SSE id 严格递增、子工具事件携带溯源字段、COMPLETE 仅发射1次',
          expected: '存在 parentToolCallId 非空的子 TOOL_START/TOOL_END；idsIncreasing 通过；terminalCountEq = 1',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/demo/subagent/stream',
                body: { query: '请让子助手调用 echo 工具输出 hello-sub-stream', conversationId: '{{convId}}' },
                expect: [
                  { kind: 'idsIncreasing' },
                  { kind: 'subEventHas', agentCode: 'demo-sub' },
                  { kind: 'subToolCalled', value: 'echo' },
                  { kind: 'terminal', value: 'COMPLETE' },
                  { kind: 'terminalCountEq', value: 1 }
                ]
              }
            ]
          }
        }
      ]
    }
  ]
}