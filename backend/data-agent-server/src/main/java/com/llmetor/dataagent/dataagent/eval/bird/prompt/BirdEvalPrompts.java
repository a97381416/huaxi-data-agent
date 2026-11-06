package com.llmetor.dataagent.dataagent.eval.bird.prompt;

/**
 * BIRD 评测专用提示词（M9 自原版 dodo-agentx 零修改移植），按场景拆成三段：
 * <ul>
 *   <li>COMMON —— 生成 SQL 的通用规则，agent 与 baseline 共享；</li>
 *   <li>AGENT —— agent 专用工具 workflow（listTables/describeTables/verifySql 与 requestedColumns）；</li>
 *   <li>BASELINE —— 无工具直出适配段，套用 COMMON 规则到内嵌 schema。</li>
 * </ul>
 * 评测场景单一（每道题固定走 Text2SQL），不需要 SkillsTool 的按需加载，直接以静态提示词固化。
 * 修改本文件 = 改卷子难度口径，改完必须复跑 BIRD 全量基线（README M9 回归口径）。
 */
public final class BirdEvalPrompts {

    private BirdEvalPrompts() {
    }

    /**
     * 通用规则：只讲"怎么生成对的 SQL"，不涉及任何工具。
     */
    public static final String COMMON_INSTRUCTIONS = """
            # BIRD SQL Evaluation

            You are solving one BIRD-SQL Dev question. BIRD scores by execution-result matching:
            every result row is an ordered tuple, so extra columns, wrong column order, inferred
            filters, or reshaped identifiers make an otherwise correct answer wrong. Output one
            executable SQLite `SELECT` or `WITH` statement.

            ## SQL rules

            - Return only the values the question asks for, in the wording order. No helper or
              explanation columns.
            - Decide the row grain first: one row per requested entity or group, one detail row,
              or one aggregate row. Do not aggregate at another grain unless the question asks.
            - Join with declared foreign keys or exact stable identifiers, never a display name.
              Use `LEFT JOIN` when the attached information is optional ("if any").
            - Compare text identifiers exactly as stored: leading zeros and length matter; do not
              pad, cast, trim, or slice them unless evidence explicitly requires it.
            - Do not assume a date/time format: follow the schema examples/descriptions and the
              question/evidence conventions.
            - Apply every formula, mapping, exclusion, and date convention from the question and
              evidence literally; do not substitute a similar-looking field.
            - When similar concepts exist in multiple tables, compare descriptions, scope, and
              examples, then pick the source matching the requested entity and measure.
            - For geographic or organizational names, establish whether the schema means city,
              county, district, state, or another scope.
            - Do not infer filters from field names or common sense; do not use a BIRD metadata
              field marked `unuseful` unless the question or evidence names it.
            - Check whether a one-to-many join expanded rows or changed the aggregate scope.
            """;

    /**
     * Agent 专用：工具 workflow 与 requestedColumns 自检机制。
     */
    public static final String AGENT_INSTRUCTIONS = """
            ## Agent workflow

            1. Call `listTables` to see tables and foreign keys, then `describeTables` for the
               relevant tables. Treat column descriptions, value mappings, and examples as
               authoritative metadata.
            2. Derive `requestedColumns` from the question and evidence: exactly the values the
               question asks to return (exact field names or stable aliases), no helper columns
               and none missing, in the final SELECT order. Never edit `requestedColumns` just
               to satisfy the verifier; if the contract itself misread the question, revise the
               contract and the SQL together.
            3. Probe with `verifySql(sql, [])` to inspect real values, identifier formats, dates,
               join direction, row grain, or why a draft is empty. When a join only attaches
               an attribute to the question's subject entity, compare the joined row count
               against the un-joined filtered count of the subject table; a big drop means
               entities are being lost and the join may need to be a LEFT JOIN.
            4. Final-check with `verifySql(sql, requestedColumns)`. The tool executes the SQL as
               written and returns the full result plus diagnostics; it never rewrites SQL or
               injects LIMIT/filters. If `passed=false`, apply each `errors[].fix` and re-verify.
               If the result still looks wrong, revise against the question, evidence, and probed
               values.

            Return exactly the SQL that passed the last final-mode `verifySql` call. No markdown,
            no alternatives.
            """;

    /**
     * Baseline 专用：无工具适配，完整 schema 内嵌，套用 COMMON 规则。
     */
    public static final String BASELINE_INSTRUCTIONS = """
            ## No-tool baseline

            Database tools are unavailable; the complete schema is embedded below. Apply the SQL
            rules above to the embedded schema only. Do not invent tables, columns, relationships,
            or values. Return exactly one JSON object matching the structured-output contract:
            one statement, no markdown, no alternatives.
            """;
}
