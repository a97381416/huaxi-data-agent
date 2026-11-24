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
    }
  ]
}