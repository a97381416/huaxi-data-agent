/**
 * M3 测试场景 —— 流式体验与中断恢复（对应 test/M3-测试用例.md）
 * 已交付（2026-10-30）：A01/A04/D01 可页面自动判分；核心"刷新不丢、可打断、可顶掉"
 * （A02/A03/B 组/C 组/E01/F 组）走智能对话页浏览器真实链路手工验证。
 */
export default {
  m: 3,
  delivered: true,
  short: '流式恢复',
  title: 'M3 · 流式体验与中断恢复',
  theme: '事件序号+断线续传、执行/连接解耦、Pause/Resume、takeOver、会话历史、对话页完整版+会话列表',
  groups: [
    {
      key: 'A',
      name: '事件序号与断线续传',
      scenarios: [
        {
          id: 'M3-A01',
          prio: 'P0',
          title: '事件自增序号',
          steps: '消费一次完整流，检查 SSE id 字段',
          expected: '每个事件 id 从 1 起严格自增、不重不断',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '每个项目分类下有多少个项目？', conversationId: '{{convId}}' },
                timeoutMs: 90000,
                expect: [
                  { kind: 'contentTypeIncludes', value: 'text/event-stream' },
                  { kind: 'idsIncreasing' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              }
            ]
          }
        },
        { id: 'M3-A02', prio: 'P0', title: '执行与连接解耦', steps: '提问后回答中途关闭页面/断网，看后端日志', expected: '执行继续（LiveStreamRegistry 后台驱动），完成落库' },
        { id: 'M3-A03', prio: 'P0', title: '断线续传不丢不重', steps: '回答中途刷新页面 → 前端自动 recovery=true + startSeq', expected: '补发序号 ≥ startSeq 的缓存事件 + 实时尾流；无重复无缺失' },
        {
          id: 'M3-A04',
          prio: 'P0',
          title: '/agent/stream/status 探测',
          steps: 'GET /agent/stream/status?conversationId=',
          expected: '进行中 {active:true}，结束 {active:false}',
          auto: {
            steps: [
              { method: 'GET', url: '/agent/stream/status?conversationId=probe_m3_{{uuid}}', expect: [{ kind: 'bodyCode', value: 200 }] }
            ]
          }
        },
        { id: 'M3-A05', prio: 'P1', title: '续传属主校验', steps: '用 B 账号 token 对 A 的会话 recovery', expected: '拒绝（流属主校验），不越权' },
        { id: 'M3-A06', prio: 'P2', title: '已结束流续传', steps: 'Complete 后再 recovery', expected: '不报错，返回终止信号/空补发' }
      ]
    },
    {
      key: 'B',
      name: 'Pause / Resume 完整闭环',
      scenarios: [
        { id: 'M3-B01', prio: 'P0', title: '打断落快照', steps: '回答中途 GET /agent/stop?conversationId=', expected: '{stopped:true, hasInterruptState:true}；流 Paused 收尾；快照持久化 agentx_pause_state 表', code: 'SELECT conversation_id, safe_point, created_at FROM agentx_pause_state ORDER BY id DESC LIMIT 5;' },
        { id: 'M3-B02', prio: 'P0', title: '空消息恢复', steps: '打断后同 conversationId 发空 query', expected: '从快照恢复续跑（本地工具重放，不重调 LLM），流最终 Complete', copy: '（直接点发送，输入框留空）' },
        { id: 'M3-B03', prio: 'P0', title: 'resumeQuery 自决策', steps: '打断后带新指令', expected: '不重跑已完成轮次；模型基于快照上下文自决策续跑', copy: '继续，改成按月统计' },
        { id: 'M3-B04', prio: 'P1', title: '追加澄清语', steps: '打断后发一条补充说明', expected: '澄清语注入后续上下文，回答体现新信息' },
        { id: 'M3-B05', prio: 'P2', title: '快照过期清理', steps: '观察 expires_at', expected: '过期快照可被清理/忽略，不僵尸恢复' }
      ]
    },
    {
      key: 'C',
      name: 'takeOver 顶掉',
      scenarios: [
        { id: 'M3-C01', prio: 'P0', title: '新消息顶掉旧回答', steps: '会话回答进行中再发新消息', expected: '旧执行 interrupt → 轮询 ≤3s 等流停 → 丢弃旧快照 → 全新执行回答新问题' },
        { id: 'M3-C02', prio: 'P1', title: '顶不掉返回 409', steps: '构造停不下来的场景/mock 慢停', expected: '3s 超时返回 HTTP 409，不静默吞' },
        { id: 'M3-C03', prio: 'P1', title: '半截回答保留', steps: 'takeOver 后查历史', expected: '被顶掉的半截回答保留在历史时间线中（不丢）' }
      ]
    },
    {
      key: 'D',
      name: '会话历史管理',
      scenarios: [
        {
          id: 'M3-D01',
          prio: 'P0',
          title: '/session/list 分页分组',
          steps: '造 3 个会话后 GET /session/list?page=0&size=10',
          expected: '按 conversationId 分组：title=首轮问题截 40 字、messageCount 正确、streaming 标记实时',
          auto: {
            steps: [
              { method: 'GET', url: '/session/list?page=0&size=10', expect: [{ kind: 'bodyCode', value: 200 }] }
            ]
          }
        },
        { id: 'M3-D02', prio: 'P0', title: '详情 timeline 重构', steps: 'GET /session/{conversationId}', expected: '每轮 {question, answer, timeline, attachments}；timeline 含 thinking/text/工具三类事件且顺序正确' },
        { id: 'M3-D03', prio: 'P1', title: 'answer 实质性判定', steps: '构造一轮"好的/明白了"+ 一轮长报告', expected: 'answer 取倒序第一个实质性 AssistantMessage（≥100 字符或含 #/|/http）' },
        { id: 'M3-D04', prio: 'P1', title: 'TodoWrite → todo 面板', steps: '跑触发 TodoWrite 的长任务', expected: '工具参数侧信道解析出 todo items，前端面板渲染' },
        { id: 'M3-D05', prio: 'P0', title: 'DELETE 级联 + 属主校验', steps: 'DELETE /session/{conversationId}', expected: 'conversation+session 行级联删；B 删 A 的会话失败' }
      ]
    },
    {
      key: 'E',
      name: 'SSE 断开识别',
      scenarios: [
        { id: 'M3-E01', prio: 'P1', title: '客户端断开降噪', steps: '中途反复刷新页面，观察后端日志', expected: 'ClientAbortException 识别为 warn 一行，无 ERROR 堆栈刷屏' }
      ]
    },
    {
      key: 'F',
      name: 'Vue 对话页完整版 + 会话列表（浏览器）',
      scenarios: [
        { id: 'M3-F01', prio: 'P0', title: '刷新自动续传', steps: '回答中途按 F5', expected: '页面回来后自动补齐后续内容' },
        { id: 'M3-F02', prio: 'P0', title: '打断按钮', steps: '回答中点"停止"', expected: '流 Paused；下一条消息（可为空）从断点恢复' },
        { id: 'M3-F03', prio: 'P0', title: '会话列表交互', steps: '左侧列表切换/删除', expected: '列表分组正确；删除即时刷新；进行中会话有 streaming 标识' },
        { id: 'M3-F04', prio: 'P0', title: '历史回放', steps: '打开历史会话', expected: 'thinking/工具事件完整回放（timeline 三类事件），非只显示最终答案' },
        { id: 'M3-F05', prio: 'P1', title: 'todo 面板', steps: '长任务观察', expected: 'TodoWrite 状态实时更新' }
      ]
    }
  ]
}
