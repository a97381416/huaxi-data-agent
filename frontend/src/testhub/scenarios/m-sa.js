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
                  { kind: 'jsonMatch', path: 'data.agentMode', value: '^(single|multi)$' }
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
    },
    {
      key: 'M2',
      name: 'M-SA2 · 父子级联中断 + 断点恢复 + 级联清理',
      scenarios: [
        {
          id: 'MSA-201',
          prio: 'P0',
          title: '父停子必停：运行中点 stop 级联打断子Agent并落主子双快照',
          steps: '1) 启动含 8s 慢工具的子Agent流；2) 3.5s 后自动调 stop；3) 调 subagent-check 检查运行态与双快照元数据',
          expected: '流以 PAUSED 收尾；后台无残留子任务；主快照与子快照均落库且 subSnapshotIsSubAgent=true',
          auto: {
            steps: [
              {
                kind: 'sseStop',
                stopAfterMs: 3500,
                sse: {
                  url: '/agent/demo/subagent/stream',
                  body: { query: '请立刻委派子助手调用 slowStep 工具（seconds 传 8）完成计算', conversationId: '{{convId}}' }
                },
                stop: {
                  method: 'GET',
                  url: '/agent/demo/subagent/stop?conversationId={{convId}}'
                },
                expect: [
                  { kind: 'terminal', value: 'PAUSED' }
                ]
              },
              {
                method: 'GET',
                url: '/agent/demo/subagent-check?conversationId={{convId}}',
                expect: [
                  { kind: 'jsonEq', path: 'data.anySubTaskRunning', value: false },
                  { kind: 'jsonEq', path: 'data.rootPauseSnapshot', value: true },
                  { kind: 'jsonEq', path: 'data.subPauseSnapshot', value: true },
                  { kind: 'jsonEq', path: 'data.subSnapshotIsSubAgent', value: true }
                ]
              }
            ]
          }
        },
        {
          id: 'MSA-202',
          prio: 'P0',
          title: '空消息纯恢复：主子双快照联动续跑并在完成后自动清理快照',
          steps: '1) sseStop 中断子Agent流产生双快照；2) 发空 query 恢复执行；3) 检查流正常 COMPLETE 且主子快照均被清理',
          expected: '恢复流 COMPLETE；rootPauseSnapshot 与 subPauseSnapshot 均变为 false；不误写子 conversation 行',
          auto: {
            steps: [
              {
                kind: 'sseStop',
                stopAfterMs: 3500,
                sse: { url: '/agent/demo/subagent/stream', body: { query: '请立刻委派子助手调用 slowStep（seconds 传 6）并返回 OK-RESUME', conversationId: '{{convId}}' } },
                stop: { method: 'GET', url: '/agent/demo/subagent/stop?conversationId={{convId}}' },
                expect: [{ kind: 'terminal', value: 'PAUSED' }]
              },
              {
                kind: 'sse',
                url: '/agent/demo/subagent/resume',
                body: { query: '', conversationId: '{{convId}}' },
                timeoutMs: 60000,
                expect: [
                  { kind: 'terminal', value: 'COMPLETE' },
                  { kind: 'textIncludes', value: 'OK-RESUME' }
                ]
              },
              {
                method: 'GET',
                url: '/agent/demo/subagent-check?conversationId={{convId}}',
                expect: [
                  { kind: 'jsonEq', path: 'data.rootPauseSnapshot', value: false },
                  { kind: 'jsonEq', path: 'data.subPauseSnapshot', value: false },
                  { kind: 'jsonEq', path: 'data.subConvRowCount', value: 0 }
                ]
              }
            ]
          }
        },
        {
          id: 'MSA-203',
          prio: 'P0',
          title: '带补充消息恢复：自动丢弃旧子快照按新指令执行（防诈尸）',
          steps: '1) sseStop 中断慢工具子流；2) 带新指令 query="改调 echo 输出 NEW-PLAN" 恢复；3) 校验输出新结果且无残留快照',
          expected: '子Agent不重放旧慢工具，直接执行 echo 输出 NEW-PLAN；主子快照全部清除',
          auto: {
            steps: [
              {
                kind: 'sseStop',
                stopAfterMs: 3500,
                sse: { url: '/agent/demo/subagent/stream', body: { query: '请立刻委派子助手调用 slowStep（seconds 传 8）', conversationId: '{{convId}}' } },
                stop: { method: 'GET', url: '/agent/demo/subagent/stop?conversationId={{convId}}' },
                expect: [{ kind: 'terminal', value: 'PAUSED' }]
              },
              {
                kind: 'sse',
                url: '/agent/demo/subagent/resume',
                body: { query: '放弃刚才的慢任务，改为让子助手调用 echo 输出 NEW-PLAN', conversationId: '{{convId}}' },
                timeoutMs: 60000,
                expect: [
                  { kind: 'subToolCalled', value: 'echo' },
                  { kind: 'textIncludes', value: 'NEW-PLAN' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              },
              {
                method: 'GET',
                url: '/agent/demo/subagent-check?conversationId={{convId}}',
                expect: [
                  { kind: 'jsonEq', path: 'data.rootPauseSnapshot', value: false },
                  { kind: 'jsonEq', path: 'data.subPauseSnapshot', value: false }
                ]
              }
            ]
          }
        },
        {
          id: 'MSA-204',
          prio: 'P0',
          title: 'DELETE /session/{id} 级联删除主会话与全部 :: 子会话状态及快照',
          steps: '1) 产生带子记忆的会话；2) 调用 DELETE /session/{convId}；3) 调 subagent-check 确认主子表记录全清',
          expected: 'DELETE 返回 200；rootWorkingCount=0 且 subWorkingCount=0 且快照全空',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/demo/subagent/stream',
                body: { query: '请让子助手调用 echo 输出 cleanup-test', conversationId: '{{convId}}' },
                expect: [{ kind: 'terminal', value: 'COMPLETE' }]
              },
              {
                method: 'DELETE',
                url: '/session/{{convId}}',
                expect: [{ kind: 'bodyCode', value: 200 }]
              },
              {
                method: 'GET',
                url: '/agent/demo/subagent-check?conversationId={{convId}}',
                expect: [
                  { kind: 'jsonEq', path: 'data.rootWorkingCount', value: 0 },
                  { kind: 'jsonEq', path: 'data.subWorkingCount', value: 0 },
                  { kind: 'jsonEq', path: 'data.subPauseSnapshot', value: false }
                ]
              }
            ]
          }
        }
      ]
    },
    {
      key: 'M3',
      name: 'M-SA3 · 父子 Trace 调用树与监控聚合校准',
      scenarios: [
        {
          id: 'MSA-301',
          prio: 'P0',
          title: '父子 Trace 树完整落库（round=1 不撞键）且监控提问数不虚增',
          steps: '1) 新窗口执行1次主调子完整问答；2) 调 subagent-check 核验新旧 Trace 表行数与父子挂载；3) 核验主列表不混入子会话',
          expected: 'question_count=1，subSession=1 且 parent_session_id 匹配根 sessionId；新旧 trace 均落库无丢失；监控提问主列表仅1行',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/demo/subagent/stream',
                body: { query: '请委派子助手调用 echo 工具输出 trace-tree-ok', conversationId: '{{convId}}' },
                expect: [{ kind: 'terminal', value: 'COMPLETE' }]
              },
              {
                method: 'GET',
                url: '/agent/demo/subagent-check?conversationId={{convId}}',
                expect: [
                  { kind: 'bodyCode', value: 200 },
                  { kind: 'jsonEq', path: 'data.monitorQuestionCount', value: 1 },
                  { kind: 'jsonEq', path: 'data.monitorSubSessionCount', value: 1 },
                  { kind: 'jsonEq', path: 'data.subParentSessionMatched', value: true },
                  { kind: 'jsonNumMin', path: 'data.monitorTotalRounds', min: 3 },
                  { kind: 'jsonNumMin', path: 'data.oldTraceCount', min: 3 }
                ]
              }
            ]
          }
        }
      ]
    },
    {
      key: 'M4',
      name: 'M-SA4 · 主路由 + 四大有状态垂类子 Agent 切换',
      scenarios: [
        {
          id: 'MSA-401',
          prio: 'P0',
          title: 'Multi模式-闲聊直答：主路由直接响应不误唤醒业务子Agent',
          steps: 'agentMode=multi 发送问候语，检查不触发任何子Agent工具调用直接返回',
          expected: '无 TOOL_START 事件，正文正常返回助手能力介绍，COMPLETE 收尾',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '你好，请简单一句话告诉我你是谁', conversationId: '{{convId}}', agentMode: 'multi' },
                expect: [
                  { kind: 'noEventType', value: 'TOOL_START' },
                  { kind: 'terminal', value: 'COMPLETE' },
                  { kind: 'textLenMin', min: 5 }
                ]
              }
            ]
          }
        },
        {
          id: 'MSA-402',
          prio: 'P0',
          title: 'Multi模式-问数有状态子Agent：查库权限生效 + 跨轮下钻追问',
          steps: '1) 第1轮问当前可见项目总数；2) 第2轮同窗口追问"把刚才这些项目按状态分组统计数量"',
          expected: '两轮均路由 call_data_analysis 且子内部调用 executeSql；返回真实统计结果正常 COMPLETE',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '帮我查一下目前库里我能看到的项目一共有多少个？', conversationId: '{{convId}}', agentMode: 'multi', skill: 'data-analysis' },
                timeoutMs: 120000,
                expect: [
                  { kind: 'toolNameCalled', value: 'call_data_analysis' },
                  { kind: 'subToolCalled', value: 'executeSql' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              },
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '把刚才查到的这些项目按状态分组统计一下各自的数量', conversationId: '{{convId}}', agentMode: 'multi' },
                timeoutMs: 120000,
                expect: [
                  { kind: 'toolNameCalled', value: 'call_data_analysis' },
                  { kind: 'subToolCalled', value: 'executeSql' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              }
            ]
          }
        },
        {
          id: 'MSA-403',
          prio: 'P0',
          title: 'Multi模式-办理类垂类子Agent精准路由与物理工具隔离',
          steps: '1) 发起首次填报意图验证路由至 call_declarant_fill 并调 listMyProjects；2) 新窗口发起待审查询验证路由至 call_reviewer_workflow 并调 listReviewTasks',
          expected: '各自命中对应垂类子Agent与专属读工具，不跨组误调 SQL 或其他组写工具',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '我要填报项目资料，先列出我名下的项目清单', conversationId: 'test_fill_{{uuid}}', agentMode: 'multi' },
                timeoutMs: 90000,
                expect: [
                  { kind: 'toolNameCalled', value: 'call_declarant_fill' },
                  { kind: 'subToolCalled', value: 'listMyProjects' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              },
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '帮我查一下我名下有哪些待我审核审批的任务', conversationId: 'test_rev_{{uuid}}', agentMode: 'multi' },
                timeoutMs: 90000,
                expect: [
                  { kind: 'toolNameCalled', value: 'call_reviewer_workflow' },
                  { kind: 'subToolCalled', value: 'listReviewTasks' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              }
            ]
          }
        },
        {
          id: 'MSA-404',
          prio: 'P0',
          title: 'Multi模式-高危确认令牌取消指令直达与 Single 模式回滚可用性',
          steps: '1) 发送"取消 ct_probe_0000"验证 cancelConfirm 秒级响应；2) 切 agentMode=single 验证回滚通道随时可用',
          expected: 'cancelConfirm 被正确调用并返回作废/失效说明；single 模式正常 COMPLETE',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '取消 ct_probe_0000', conversationId: 'test_cancel_{{uuid}}', agentMode: 'multi' },
                timeoutMs: 60000,
                expect: [
                  { kind: 'toolNameCalled', value: 'cancelConfirm' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              },
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '当前系统时间是多少？', conversationId: 'test_rb_{{uuid}}', agentMode: 'single' },
                timeoutMs: 60000,
                expect: [
                  { kind: 'terminal', value: 'COMPLETE' },
                  { kind: 'textLenMin', min: 2 }
                ]
              }
            ]
          }
        }
      ]
    },
    {
      key: 'M5',
      name: 'M-SA5 · 前端嵌套卡片 + 历史回放对齐 + 双层计划面板',
      scenarios: [
        {
          id: 'MSA-501',
          prio: 'P0',
          title: '历史回放对齐：GET /session/{id} 的 timeline 完整内嵌子Agent执行步骤 subSteps',
          steps: '1) multi 模式执行一次问数查询直至 COMPLETE；2) 请求 GET /session/{{convId}}；3) 校验返回的 timeline JSON 中包含 subSteps 与子工具名',
          expected: 'session 详情返回 200；timeline 字符串中包含 call_data_analysis 及嵌套的 subSteps 与 executeSql',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '请帮我查一下当前一共有多少个项目？', conversationId: '{{convId}}', agentMode: 'multi', skill: 'data-analysis' },
                timeoutMs: 120000,
                expect: [
                  { kind: 'toolNameCalled', value: 'call_data_analysis' },
                  { kind: 'subToolCalled', value: 'executeSql' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              },
              {
                method: 'GET',
                url: '/session/{{convId}}',
                expect: [
                  { kind: 'bodyCode', value: 200 },
                  { kind: 'jsonMatch', path: 'data.messages[0].timeline', value: '"subSteps"\\s*:\\s*\\[' },
                  { kind: 'jsonMatch', path: 'data.messages[0].timeline', value: 'executeSql' }
                ]
              }
            ]
          }
        },
        {
          id: 'MSA-502',
          prio: 'P1',
          title: '双层 TodoWrite 计划归并：主宏观计划挂载子微观 subItems 不互相覆盖',
          steps: '1) 触发主路由2步计划 + 子Agent 3步计划演示流；2) 检查 SSE 双层 TodoWrite 事件与 /session 回放归并结构',
          expected: 'SSE 含主子两级 TodoWrite；历史回放 todo 事件保留顶层2步且对应步骤内含 subItems 数组',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/demo/subagent/stream',
                body: { query: '请先调用 TodoWrite 建立2步主计划，再委派子助手先调用 TodoWrite 建立3步子计划并调用 echo 输出 dual-todo-ok 完成任务', conversationId: '{{convId}}', enableDualTodoDemo: true },
                timeoutMs: 90000,
                expect: [
                  { kind: 'toolNameCalled', value: 'TodoWrite' },
                  { kind: 'subToolCalled', value: 'TodoWrite' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              },
              {
                method: 'GET',
                url: '/session/{{convId}}',
                expect: [
                  { kind: 'bodyCode', value: 200 },
                  { kind: 'jsonMatch', path: 'data.messages[0].timeline', value: '"subItems"\\s*:\\s*\\[' }
                ]
              }
            ]
          }
        }
      ]
    },
    {
      key: 'M6',
      name: 'M-TT · 监控链路跟踪子Agent穿透（追溯视图补齐子会话轮次树）',
      scenarios: [
        {
          id: 'MSA-601',
          prio: 'P0',
          title: '监控 timeline 子会话穿透：委派 tool 事件挂载 subSession 完整事件树且 callId 命中委派点',
          steps: '1) multi 模式问数跑一次完整问答直至 COMPLETE；2) GET /monitor/conversations/{{convId}} 取根会话行；3) GET /monitor/sessions/{rootSid}/timeline 校验委派行 subSession 挂载与孤儿兜底结构',
          expected: 'timeline.events 存在 toolName=call_data_analysis 且 subSession 非空的委派事件（挂载即证明 parent_tool_call_id 与委派 callId 匹配）：agentCode=data-analysis、task 非空、subSession.events ≥2 条且子事件内含 executeSql；顶层含 orphanSubSessions 数组',
          auto: {
            steps: [
              {
                kind: 'sse',
                url: '/agent/stream',
                body: { query: '请帮我统计一下当前库里一共有多少个项目？', conversationId: '{{convId}}', agentMode: 'multi', skill: 'data-analysis' },
                timeoutMs: 120000,
                expect: [
                  { kind: 'toolNameCalled', value: 'call_data_analysis' },
                  { kind: 'subToolCalled', value: 'executeSql' },
                  { kind: 'terminal', value: 'COMPLETE' }
                ]
              },
              {
                method: 'GET',
                url: '/monitor/conversations/{{convId}}',
                expect: [
                  { kind: 'bodyCode', value: 200 },
                  { kind: 'jsonLen', path: 'data.sessions', min: 1 }
                ],
                saveVar: 'convDetail'
              },
              {
                method: 'GET',
                url: '/monitor/sessions/{{var:convDetail.sessions.0.sessionId}}/timeline',
                timeoutMs: 60000,
                expect: [
                  { kind: 'bodyCode', value: 200 },
                  { kind: 'jsonLen', path: 'data.events', min: 3 },
                  { kind: 'jsonKeys', path: 'data', value: ['sessionId', 'conversationId', 'question', 'status', 'roundCount', 'events', 'orphanSubSessions'] },
                  { kind: 'jsonSome', path: 'data.events', field: 'toolName', eq: 'call_data_analysis' },
                  { kind: 'jsonSome', path: 'data.events', field: 'subSession.agentCode', eq: 'data-analysis' },
                  { kind: 'jsonSome', path: 'data.events', field: 'subSession.sessionId', match: '.+' },
                  { kind: 'jsonSome', path: 'data.events', field: 'subSession.task', match: '.+' },
                  { kind: 'jsonSome', path: 'data.events', field: 'subSession.status', eq: 'completed' },
                  { kind: 'jsonSome', path: 'data.events', field: 'subSession.roundCount', min: 1 },
                  { kind: 'jsonSome', path: 'data.events', field: 'subSession.events', lenMin: 2 },
                  { kind: 'jsonSome', path: 'data.events', field: 'subSession.events', match: 'executeSql' }
                ]
              }
            ]
          }
        }
      ]
    }
  ]
}