/**
 * 检测规则示例（规则管理页展开行数据源）：ruleId → markdown 文案。
 * 与 RuleManageView 的 PARAM_CN 同思路：规则语义的展示性文档放前端静态维护，
 * 后端 DetectRule SPI 不承载；示例结构 = 场景一句话 + 证据代码块 + 命中级别。
 * V2（05 规格 §4）：15 条规则示例对齐三档口径（异常/待人工确认/风险提示）。
 */
export const RULE_EXAMPLES = {
  'TOOL-001': `**场景**：失败轮错误含工具名；或工具观察 JSON 结构化状态为失败（status/success/error_code/exception/timeout 任一），无结构化状态时全文命中失败特征词（V2 取消 300 字符窗口）。

例如：

\`\`\`json
{
  "status": "error",
  "error": "Unknown column 'project_status'"
}
\`\`\`

结构化成功形态（success=true / status=ok / error_code=0）不进关键词兜底。命中 → **异常**「工具调用失败」。`,
  'TOOL-002': `**场景**：单工具轮耗时超过该工具阈值 \`perToolMs[工具名]\`（未配置用 \`defaultMs\` 30s；多工具轮无法归因一律用 defaultMs；提示级）。

例如：

\`\`\`json
{ "tool": "executeSql", "duration_ms": 8000, "thresholdMs": 5000 }
\`\`\`

命中 → **风险提示**「工具调用耗时异常」（单问最多 5 条防刷屏）。`,
  'TOOL-003': `**场景**：工具观察含异常堆栈（\`at xx.xxx(\` / \`Exception:\` 起始行 / \`Traceback\`）；期望 JSON 的工具（expectedOutput）返回非合法 JSON。

例如：

\`\`\`text
java.sql.SQLException: 列不存在
    at com.xx.sql.SqlExecutor.run(SqlExecutor.java:88)
\`\`\`

合法 JSON（业务码失败归 TOOL-007）与 Markdown/纯文本不判。命中 → **待人工确认**「工具返回格式异常」。`,
  'TOOL-004': `**场景**：SQL 词法分析（剥注释与字符串字面量、WITH 跳 CTE）后首关键字 ∉ 只读白名单，只读白名单用户豁免。

例如：

\`\`\`json
{ "sql": "DELETE FROM project WHERE id = 1001" }
\`\`\`

反例：\`WHERE remark='DELETE'\` 字符串字面量里的写词不判。命中 → **异常**「SQL 非法操作」。`,
  'TOOL-005': `**场景**：统计类问题 SQL 查询成功但结果为空（rows:[] / count:0 / 空文本），回答却给出确定性统计结论且未声明无数据（0 条不一定是异常，仅提示；V2 默认启用）。

例如：

\`\`\`json
{ "rows": [] }
\`\`\`

回答：「共 12 个项目完成验收…」

命中 → **风险提示**「统计查询空结果风险提示」。`,
  'TOOL-006': `**场景**（V2 新增）：调用入参异常——必填参数缺失（requiredArgs）、参数值 null/空串/空对象、SQL 参数残缺（以 SELECT/FROM/WHERE/AND/, 结尾或缺 FROM 的裸标识符收尾 SELECT）。

例如：

\`\`\`json
{ "sql": "SELECT * FROM" }
\`\`\`

反例：\`{"sql":"SELECT 1"}\` 不判。命中 → **异常**「工具调用参数异常」。`,
  'TOOL-007': `**场景**（V2 新增）：apiTools 配置内 API 工具返回业务码 ∉ successCodes（按工具配置 codeField/successCodes/messageField；默认未配置不触发）。

例如：

\`\`\`json
{ "code": 10001, "message": "没有权限", "data": null }
\`\`\`

Observation 非 JSON 归 TOOL-003 管。命中 → **异常**「API 业务执行失败」。`,
  'SKILL-001': `**场景**（V2 新增）：Skill 工具返回「未找到技能 / 可用技能」引导文本（技能名拼错或技能未安装；正常加载返回技能工作目录不判）。

例如：

\`\`\`text
未找到技能: data-analys。当前可用技能: data-analysis, chart-maker
\`\`\`

命中 → **待人工确认**「Skill 加载失败」。`,
  'ANSWER-001': `**场景**：存在工具失败且失败后无成功数据轮兜底（恢复感知 V2-D4），但最终回答仍含确定性数字结论且未声明获取失败（结论不可信）。

例如：第 2 轮 \`executeSql\` 超时失败、后续无成功数据轮，最终回答却写：

\`\`\`text
全院项目总投资共计 5.6 亿元。
\`\`\`

失败后有成功数据轮（哪怕数字对不上，归 ANSWER-002）不判。命中 → **异常**「失败后仍给出确定性结论」。`,
  'ANSWER-002': `**场景**：最后一次成功统计结果为 T（单一数字字段），回答的统计结论却给出其他数字（真数值比对 V2-D3；定性回答与约数不判）。

例如：

\`\`\`json
{ "count": 86 }
\`\`\`

回答：「今年完成了 68 个」

T 出现在回答中 / 回答无数值结论不判。命中 → **待人工确认**「工具结果与最终答案数值不一致」。`,
  'REACT-001': `**场景**：决策轮数（trace 行数）超过 \`maxRounds\`（V2 默认 10）；证据同时给「决策轮 X / 工具调用 Y 次」两数，调用次数不单独触发。

例如：

\`\`\`json
{ "decisionRounds": 12, "toolCalls": 15, "maxRounds": 10 }
\`\`\`

命中 → **待人工确认**「执行循环过多」。`,
  'REACT-002': `**场景**：连续失败 ≥ failStreak（默认 2）轮且直到结束未恢复（失败后换工具/改参成功不判——恢复感知 V2-D4）。

例如：第 2、3 轮工具结果均为 \`status: error\`，此后到结束再无成功轮。

命中 → **异常**「连续工具执行失败」。`,
  'REACT-003': `**场景**：相同工具 + 相同参数连续调用 ≥ \`repeatCalls\`（默认 3）且各轮结果指纹完全相同（无有效进展；改参重试或结果在变不算；V2 降级 medium）。

例如：\`executeSql("SELECT count(*) FROM project")\` 连续调用 3 次、三次返回完全一致。

命中 → **待人工确认**「相同工具重复调用」。`,
  'REACT-004': `**场景**：提问已完成但最终回答为空，或全程没有成功的文本轮。

例如：

\`\`\`json
{ "answerBlank": true, "hasTextRound": false }
\`\`\`

命中 → **待人工确认**「最终回答缺失」。`,
  'REACT-005': `**场景**（V2 新增）：连续 noProgressRounds（默认 3）轮调用在变（换工具/换参数）但结果始终相同或持续为空（rows:[] / count:0 / 空文本）——同参同结果归 REACT-003，互补不合并。

例如：SQL → API → SQL 轮换查询，三次都返回 \`{"rows":[]}\`。

命中 → **待人工确认**「多轮执行无有效进展」。`
}

/** 取规则示例（无示例返回空串，展开行显示「暂无示例」） */
export function ruleExample(ruleId) {
  return RULE_EXAMPLES[ruleId] || ''
}
