/**
 * M8 测试场景 —— 评测体系（对应 test/M8-测试用例.md）
 * 已交付：trace 三工具（诊断 Agent 内注入）+ 裁判/诊断接口（免鉴权）+ 数据集校验接口 + 报告查看页（/eval）。
 * A 组经 /agent/eval/dataset/check 自动判分（配比/交错/固化复跑/题型覆盖）；
 * C01/D05 接口契约自动探测；跑题/基线/诊断闭环为 scripts/agent 下命令行用例（人工执行）。
 */
export default {
  m: 8,
  delivered: true,
  short: '评测',
  title: 'M8 · 评测体系',
  theme: 'trace 查询三工具、出题/跑题/裁判、诊断 Agent；建议 M4 之后再跑正式基线',
  groups: [
    {
      key: 'A',
      name: '出题（dataset.jsonl）',
      scenarios: [
        {
          id: 'M8-A01',
          prio: 'P0',
          title: '题量与难度配比',
          steps: 'GET /agent/eval/dataset/check',
          expected: '难度 7:2:1；三池每 10 题交错（任意截断比例稳定）',
          auto: {
            steps: [
              {
                method: 'GET',
                url: '/agent/eval/dataset/check',
                expect: [
                  { kind: 'bodyCode', value: 200 },
                  { kind: 'jsonTrue', path: 'data.found' },
                  { kind: 'jsonEq', path: 'data.total', value: 100 },
                  { kind: 'jsonEq', path: 'data.difficulty.简单', value: 70 },
                  { kind: 'jsonEq', path: 'data.difficulty.中等', value: 20 },
                  { kind: 'jsonEq', path: 'data.difficulty.困难', value: 10 },
                  { kind: 'jsonTrue', path: 'data.interleaveOk' }
                ]
              }
            ]
          },
          note: 'interleaveOk=true 表示每个 10 题分组恰好 7 简单 + 2 中等 + 1 困难（任意截断比例稳定）'
        },
        {
          id: 'M8-A02',
          prio: 'P0',
          title: 'gold_answer 预执行固化',
          steps: 'GET /agent/eval/dataset/check（gold_sql 全量对库复跑）',
          expected: '标准答案全部由预执行 SQL 固化，非人工编造；断言可对库复跑一致',
          auto: {
            steps: [
              {
                method: 'GET',
                url: '/agent/eval/dataset/check',
                expect: [
                  { kind: 'bodyCode', value: 200 },
                  { kind: 'jsonEq', path: 'data.goldExecutable', value: 100 },
                  { kind: 'jsonLen', path: 'data.goldFailed', min: 0, max: 0 }
                ]
              }
            ]
          },
          note: 'dataset.jsonl 由 build_dataset.py 直连业务库预执行生成；check 再复跑一遍验证 SQL 全部可执行（数字一致性由生成机制保证）'
        },
        {
          id: 'M8-A03',
          prio: 'P0',
          title: '题型覆盖',
          steps: 'GET /agent/eval/dataset/check',
          expected: '含多轮题、数据权限题（多账号 run_role）、时间口径题（currentTime 真实时间口径）',
          auto: {
            steps: [
              {
                method: 'GET',
                url: '/agent/eval/dataset/check',
                expect: [
                  { kind: 'bodyCode', value: 200 },
                  { kind: 'jsonNumMin', path: 'data.multiRound', min: 13 },
                  { kind: 'jsonNumMin', path: 'data.runRoles.sh_sales', min: 1 },
                  { kind: 'jsonNumMin', path: 'data.runRoles.admin', min: 80 },
                  { kind: 'jsonNumMin', path: 'data.anchorQuestions', min: 10 }
                ]
              }
            ]
          },
          note: '实测：多轮 13 道（question 为数组逐轮发问）、run_role=sh_sales 3 道 + admin 97 道、时间锚点 14 道'
        }
      ]
    },
    {
      key: 'B',
      name: '跑题（runner，命令行人工执行）',
      scenarios: [
        { id: 'M8-B01', prio: 'P0', title: '走生产链路', steps: 'python agent_eval_runner.py --ids 1', expected: '全部经 /agent/stream 真实链路（非旁路直调）' },
        { id: 'M8-B02', prio: 'P0', title: '多轮同会话', steps: 'python agent_eval_runner.py --ids 100', expected: '同题各轮同一 conversationId 逐轮发问' },
        { id: 'M8-B03', prio: 'P0', title: '数据权限题多账号', steps: 'python agent_eval_runner.py --ids 24', expected: '按 run_role 以不同账号登录执行（sh_sales SELF 档只看到本人经手数据）' },
        { id: 'M8-B04', prio: 'P0', title: '断点续跑', steps: '中途 Ctrl-C 后重跑', expected: '状态 JSONL 恢复：同 key 覆盖、成功跳过；--rerun-failed/--ids/--runs 可用' },
        { id: 'M8-B05', prio: 'P1', title: '多遍并发', steps: 'python agent_eval_runner.py --runs 3 --max-questions 5', expected: '结果按遍次记录，互不串写' }
      ]
    },
    {
      key: 'C',
      name: '裁判（/agent/eval/judge）',
      scenarios: [
        {
          id: 'M8-C01',
          prio: 'P0',
          title: '裸模型判分（真实 LLM 调用）',
          steps: 'POST /agent/eval/judge 走一次真实判分',
          expected: '裸模型判分：无工具、temperature=0、120s 超时，只比对 问题/标准答案/Agent 报告；返回四维分 + 程序算的 totalScore/pass',
          auto: {
            steps: [
              {
                method: 'POST',
                url: '/agent/eval/judge',
                timeoutMs: 130000,
                body: {
                  question: '项目档案库共有多少个项目？',
                  goldAnswer: '项目档案库共有 28 个项目',
                  agentAnswer: '经过查询 doc_project 表统计，项目档案库共有 28 个项目。'
                },
                expect: [
                  { kind: 'status', value: 200 },
                  { kind: 'jsonTrue', path: 'success' },
                  { kind: 'jsonTrue', path: 'pass' },
                  { kind: 'jsonKeys', path: '', value: ['success', 'pass', 'correctness', 'completeness', 'relevance', 'logic', 'totalScore', 'reason', 'durationMs'] }
                ]
              }
            ]
          },
          note: '免鉴权端点（本地 runner 专用）；此处顺带真实调一次 MiniMax 裁判'
        },
        { id: 'M8-C02', prio: 'P0', title: '四维打分程序算总分', steps: '检查 judge 响应与单测', expected: '四维各 0-5 档；加权总分与 pass（correctness≥3）由程序计算，不经裁判自由发挥', code: 'cd backend && mvn test -pl data-agent-server -Dtest=AgentEvalJudgeServiceTest' },
        { id: 'M8-C03', prio: 'P0', title: '数值容差', steps: '数值答案题', expected: '±1% 内容差仍算正确；离散值必须精确一致', note: '裁判 rubric 比对规则（AgentEvalPrompts.JUDGE_INSTRUCTIONS）；C01 实测观察' },
        { id: 'M8-C04', prio: 'P1', title: '多轮逐轮对照', steps: '多轮题', expected: '每轮分别对照；任一轮编造 → 正确性 0', note: '多轮题 runner 拼完整对话记录一次送判，rubric 约定逐轮对照' },
        { id: 'M8-C05', prio: 'P2', title: '长报告截断', steps: '>8000 字报告', expected: '截断后送判，不超时', code: 'cd backend && mvn test -pl data-agent-server -Dtest=AgentEvalJudgeServiceTest#超长报告截断送判' },
        { id: 'M8-C06', prio: 'P1', title: '解析失败重试', steps: '构造坏输出', expected: '结构化解析失败自动重试 1 次', code: 'cd backend && mvn test -pl data-agent-server -Dtest=AgentEvalJudgeServiceTest' },
        { id: 'M8-C07', prio: 'P1', title: '可复现', steps: '同一批判两次', expected: 'temperature=0 下两次判分一致', note: 'EvalChatModels temperature 固定 0' }
      ]
    },
    {
      key: 'D',
      name: '诊断 Agent（/agent/eval/diagnose）',
      scenarios: [
        { id: 'M8-D01', prio: 'P1', title: '诊断自身是 ReactAgent', steps: '代码审查 AgentDiagnoseService', expected: '不传 dataSource（enableSession=false 语义）+ enableTrace=false（不落库不污染被测数据）、结构化 outputType' },
        { id: 'M8-D02', prio: 'P0', title: '三工具注入', steps: '审查工具列表', expected: '仅 traceOverview/traceRound/traceFirstAppear' },
        { id: 'M8-D03', prio: 'P0', title: '找第一次出错轮', steps: 'python agent_diagnose.py --id N（挑基线错题）', expected: 'rootRound 指向第一次出错的轮次（非最后出错轮）；evidence 引用 trace 原文' },
        { id: 'M8-D04', prio: 'P0', title: '四类归因', steps: '汇总 3 例', expected: 'attribution ∈ {模型能力/提示词/工具/框架}；工具返回异常→"工具"，工具正常模型推理错→"模型能力"' },
        {
          id: 'M8-D05',
          prio: 'P0',
          title: '输出结构 + 参数契约探测',
          steps: 'POST /agent/eval/diagnose 空 conversationId',
          expected: '{success, conversationId, rootSessionId, rootRound, attribution, evidence, analysis, suggestion, error, durationMs} 字段齐全；缺参直接 success=false（不触发 LLM）',
          auto: {
            steps: [
              {
                method: 'POST',
                url: '/agent/eval/diagnose',
                timeoutMs: 10000,
                body: {},
                expect: [
                  { kind: 'status', value: 200 },
                  { kind: 'jsonEq', path: 'success', value: false },
                  { kind: 'jsonKeys', path: '', value: ['success', 'conversationId', 'rootSessionId', 'rootRound', 'attribution', 'evidence', 'analysis', 'suggestion', 'error', 'durationMs'] }
                ]
              }
            ]
          },
          note: '空参走参数校验短路，不消耗模型调用；完整诊断闭环由 D03 人工用例验证'
        }
      ]
    },
    {
      key: 'E',
      name: 'trace 查询工具（给诊断 Agent 的三工具）',
      scenarios: [
        { id: 'M8-E01', prio: 'P1', title: 'traceOverview', steps: '诊断会话观察工具入参出参', expected: 'session 清单+轮次概览（失败轮/压缩点）+消息预览（截 200 字、省思考）', code: 'cd backend && mvn test -pl data-agent-server -Dtest=TraceQueryServiceTest' },
        { id: 'M8-E02', prio: 'P0', title: 'traceRound', steps: '失败轮还原', expected: '完整 input/output（失败轮详情唯一入口）；includeTools 开关省略工具定义', code: 'cd backend && mvn test -pl data-agent-server -Dtest=TraceQueryServiceTest' },
        { id: 'M8-E03', prio: 'P1', title: 'traceFirstAppear', steps: '关键词反查', expected: '首现位置+前后 120 字符，覆盖正文/思考/工具参数/工具结果', code: 'cd backend && mvn test -pl data-agent-server -Dtest=TraceQueryServiceTest' }
      ]
    },
    {
      key: 'F',
      name: '基线报告（命令行人工执行，报告在 /eval 页查看）',
      scenarios: [
        { id: 'M8-F01', prio: 'P0', title: '100 题基线', steps: 'python agent_eval_runner.py --runs 2', expected: '产出报告：通过率/平均分/全对率（scripts/agent/report/agent_results.report.md）' },
        { id: 'M8-F02', prio: 'P0', title: '稳定性', steps: '同 commit 重跑一遍', expected: '通过率波动 <5%' },
        { id: 'M8-F03', prio: 'P0', title: '错题诊断闭环', steps: 'python agent_diagnose.py --id N（挑 3 道错题）', expected: '归因合理、建议可执行（人工评审；报告同步出现在 /eval 页）' }
      ]
    }
  ]
}
