/**
 * M7 测试场景 —— 上下文工程（对应 test/M7-测试用例.md）
 * 已交付：tool_search 延迟工具发现（KEYWORD/Jieba）+ 上下文压缩（L1/L2/L4 + offload + context_reload）。
 * A01/A04 经 /agent/capabilities 三层装配速览自动判分；A02 走真实 SSE 流断言（tool_search 先发现、延迟工具后调用）；
 * B 组压缩类用例需长会话/查库观察，按手工步骤执行（联调实测记录见 README M7 节）。
 */
export default {
  m: 7,
  delivered: true,
  short: '上下文',
  title: 'M7 · 上下文工程',
  theme: '上下文压缩（L1/L2/L4 + offload + context_reload）、tool_search 延迟工具发现（KEYWORD 模式）',
  groups: [
    {
      key: 'A',
      name: 'tool_search 延迟工具发现',
      scenarios: [
        {
          id: 'M7-A01',
          prio: 'P0',
          title: '首轮工具极简',
          steps: '新会话首轮抓请求的 tools（后端日志「首轮工具清单」/ capabilities 接口）',
          expected: '只有常驻工具 + tool_search；SQL 六工具与图表工具全部不在初始列表',
          auto: {
            steps: [
              {
                method: 'GET',
                url: '/agent/capabilities',
                headers: { satoken: '{{token:admin}}' },
                expect: [
                  { kind: 'bodyCode', value: 200 },
                  { kind: 'jsonArrayHas', path: 'data.toolLayers.resident', value: 'tool_search' },
                  { kind: 'jsonArrayHas', path: 'data.toolLayers.resident', value: 'context_reload' },
                  { kind: 'jsonArrayNotHas', path: 'data.toolLayers.resident', value: 'executeSql' },
                  { kind: 'jsonArrayNotHas', path: 'data.toolLayers.resident', value: 'listTables' },
                  { kind: 'jsonArrayNotHas', path: 'data.toolLayers.resident', value: 'generate_bar_chart' },
                  { kind: 'jsonArrayHas', path: 'data.toolLayers.deferred', value: 'executeSql' },
                  { kind: 'jsonArrayHas', path: 'data.toolLayers.deferred', value: 'generate_bar_chart' }
                ]
              }
            ]
          },
          note: 'resident 应含 tool_search/context_reload 且不含任何延迟工具；deferred 应含 SQL 六工具与图表工具（联调实测 resident=[lookupGlossary, TodoWrite, Skill, tool_search, context_reload]，deferred=24 个）'
        },
        {
          id: 'M7-A02',
          prio: 'P0',
          title: 'KEYWORD 发现 + 发现后可调用',
          steps: '新会话问数据问题，观察 SSE 工具事件顺序',
          expected: 'Agent 先调 tool_search（Jieba 打分）返回 ≤maxResults 个工具，发现的 SQL 工具随后真实调用',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                timeoutMs: 240000,
                body: { query: '每个项目分类下有多少个项目？', conversationId: '{{convId}}' },
                expect: [
                  { kind: 'terminal', value: 'COMPLETE' },
                  { kind: 'toolNameCalled', value: 'tool_search' },
                  { kind: 'toolNameCalled', value: 'executeSql' },
                  { kind: 'toolOrderContains', value: ['tool_search', 'executeSql'] },
                  { kind: 'toolResultIncludes', tool: 'tool_search', value: '已发现' },
                  { kind: 'textLenMin', min: 100 }
                ]
              }
            ]
          },
          note: 'tool_search 必须先于 executeSql（第 N 轮发现 → 第 N+1 轮可调用，M7-A05 同步验证）；联调实测发现 6 个 SQL 工具一网打尽'
        },
        { id: 'M7-A03', prio: 'P1', title: '引导 prompt', steps: '审查 SystemPrompt（后端日志或代码 prompt/SystemPrompt.java「工具发现」节）', expected: '含 tool_search 使用引导（先发现再调用 + 推荐关键词组）' },
        { id: 'M7-A04', prio: 'P1', title: '三层装配恢复', steps: '审查 DodoAgent 与 /agent/capabilities', expected: '常驻/条件/延迟三层装配（对齐 01 §1.1）', auto: { steps: [{ method: 'GET', url: '/agent/capabilities', headers: { satoken: '{{token:admin}}' }, expect: [{ kind: 'bodyCode', value: 200 }, { kind: 'jsonLen', path: 'data.toolLayers.deferred', min: 20 }] }] }, note: '延迟层 24 个（SQL 六工具 + 18 图表工具）' },
        { id: 'M7-A05', prio: 'P0', title: '每轮重建生效', steps: '与 A02 同流观察', expected: 'DeferredToolRegistry 每请求 Session 内有效；每轮重建后发现的工具下一轮可用', note: 'A02 的 toolOrderContains [tool_search, executeSql] 即为本用例的自动证明' },
        { id: 'M7-A06', prio: 'P2', title: '重名注册告警', steps: '注册同名延迟工具（单测 DeferredToolRegistryTest/duplicateRegistrationKeepsFirst）', expected: '至少 warn 日志且保留先注册者（缺陷清单项）' }
      ]
    },
    {
      key: 'B',
      name: '上下文压缩（L1/L2/L4）',
      scenarios: [
        { id: 'M7-B01', prio: 'P0', title: '每轮 token 稳定', steps: '构造 30+ 轮工具密集会话，逐轮记录 prompt tokens（联调时可在 application-local.yml 临时调低 context-policy 阈值真实触发）', expected: '触发压缩后每轮 prompt 稳定在阈值附近，不随轮次线性膨胀', code: "SELECT session_id, round, prompt_tokens FROM agentx_trace WHERE conversation_id = '<会话号>' ORDER BY id;\n-- 联调实测（阈值压小时）：Q1 末轮 44948 → 压缩后 Q2 首轮 36430 → 再压 22162，之后每轮 +800 左右缓增" },
        { id: 'M7-B02', prio: 'P0', title: 'L1 历史工具结果 offload', steps: '长会话观察后端日志 [compaction] L1', expected: '历史轮工具大结果整段 offload（模板+uuid），最近 lastKeep 条保护', note: '单测 ContextCompactorTest/l1OffloadsToolRegionAndKeepsProtectedZone 覆盖；进行中的工具区跨保护区边界时整段跳过（协议安全）' },
        { id: 'M7-B03', prio: 'P1', title: 'L2 大消息 offload', steps: '构造单条超大工具结果或观察长会话日志', expected: '超阈值（largePayloadTokens）单条被 offload 为"预览+uuid"', note: '联调实测：L2 offload 3 条大消息（约 8517 tokens）' },
        { id: 'M7-B04', prio: 'P0', title: 'L4 历史轮次摘要', steps: '触发历史摘要（L1/L2 都压不动时），观察 [compaction] L4 日志', expected: '早期轮次被 LLM 摘要替代、user 原话保留，后续追问旧主题仍可答', note: '单测 ContextCompactorTest/l4SummarizesHistoricalRoundsKeepingUserWords、l4RespectsMaxLlmCompressionCount 覆盖' },
        { id: 'M7-B05', prio: 'P1', title: '保护区不误伤', steps: '观察压缩后的 working_messages 尾部', expected: '最近 lastKeep 条 / 90K 保护区内容不被压缩', note: '单测覆盖（保护区断言 + 跨边界区块整段跳过）' },
        {
          id: 'M7-B06',
          prio: 'P1',
          title: 'offload 状态键',
          steps: '触发压缩后查 agentx_session',
          expected: 'offload_context 状态键按 {"uuid","message":[...]} 记录被移除内容',
          code: "SELECT item_index, LEFT(state_data, 200) FROM agentx_session WHERE conversation_id='<会话号>' AND state_key='offload_context' ORDER BY item_index;\n-- 联调实测：3 行归档（Skill/tool_search 大结果），uuid 可被 context_reload 取回"
        },
        { id: 'M7-B07', prio: 'P0', title: 'context_reload 取回', steps: '在压缩过的会话里要求 Agent 按 uuid 取回历史原文', expected: 'Agent 调 context_reload 取回原文继续工作', note: '联调实测：Agent 从历史标记取 uuid → 调 context_reload → 正确识别归档内容（Skill 加载原文）并继续作答' },
        { id: 'M7-B08', prio: 'P1', title: 'TokenEstimator', steps: '单测', expected: '估算误差可接受（CJK/1.5 + 非CJK/4，与 tokenizer 对标 ±20%）', code: 'cd backend && mvn test -pl mini-agentx-core -Dtest=TokenEstimatorTest' }
      ]
    },
    {
      key: 'C',
      name: '功能不回退（压缩后链路完整性）',
      scenarios: [
        { id: 'M7-C01', prio: 'P0', title: '压缩会话正确性', steps: '压缩触发后的会话继续典型数据题', expected: '端到端正确率不因压缩下降', note: '联调实测：同一会话第二问（员工付款 TOP10）在压缩后的上下文上完整跑通 SOP 并产出正确报告' },
        { id: 'M7-C02', prio: 'P1', title: '断线续传兼容', steps: '压缩会话中途刷新页面', expected: 'M3 续传能力不受影响（LiveStreamRegistry 与压缩无耦合）' },
        { id: 'M7-C03', prio: 'P1', title: '打断恢复兼容', steps: '压缩会话打断 → 恢复', expected: '快照序列化的是压缩后的工作链，恢复后上下文一致', note: '内核 ResumeLoopTest 全绿；offload 归档按 conversationId 持久，恢复后 context_reload 仍可取回' }
      ]
    }
  ]
}
