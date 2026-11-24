package com.llmetor.dataagent.dataagent.monitor;

import com.llmetor.dataagent.dataagent.monitor.prompt.MonitorDiagnosePrompts;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报告模板 v1/v2 双兼容回归（07 文档 V3 融合模板升级）：
 * 1. 归因/置信度/影响轮正则提取对两版报告样例同时成立（metrics_json 契约，报告列表/日报/风险卡关联依赖）；
 * 2. v2 报告头新增「异常类型」行可提取，v1 报告提取为空不报错；
 * 3. 日报 extractSummarySection 对 v2 §1（固定行 + 表格）引用不含表格行；
 * 4. PromptSet 版本切换整套返回，v2 系统提示词含七段骨架全部章节（防误删），
 *    且两版「归因：」「影响轮：」锁死行措辞一致。
 */
class DiagnoseReportMetricsTest {

    /** v1 四段式报告样例（工具类归因，含反证/待确认节选） */
    static final String V1_SAMPLE = """
            # 单会话诊断报告

            ## 结论摘要
            归因：【工具】（置信度：高）
            影响轮：R3
            R3 executeSql 接口持续 5xx，前面轮次均正常，最终零数据失败收场。

            ## 证据链
            1. session=dodo_sess_a round=3：executeSql 返回 500。
            2. round=4：重试同参数仍 500。

            ## 待人工确认
            - executeSql 服务端 5xx 是否为偶发。

            ## 建议
            ① 【工具层】为 executeSql 增加超时与重试退避。
            """;

    /** v2 七段骨架报告样例（报告头 + 表格 + 规则级置顶节选），异常类型取「用户反馈语义类」 */
    static final String V2_SAMPLE = """
            # 单会话诊断报告

            会话：dodo_conv_x1 / dodo_sess_y1
            触发来源：提问级全链路诊断
            异常类型：用户反馈语义类
            诊断时间：2026-11-23 15:30

            ## 可信度声明

            - 规则证据完整性：无跨轮错配
            - 数据来源异常：R1 的 tool_calls 参数未落盘，系从 R2 input_data 反查还原
            - 采集缺失：首现索引未覆盖工具返回正文
            - 本结论不依赖：TOOL-005 命中（已复核不成立）

            ## 结论摘要
            归因：【模型能力】（置信度：中）
            影响轮：R1（根因轮）；R2（自行纠正轮）
            R1 生成 SQL 使用不存在列「quarter」，工具合法报错，R2 自行纠正成功，最终答案可用。

            | 项 | 内容 |
            | --- | --- |
            | 最终结果 | 答案可用：R3 输出 86，与工具返回 count=86 严格一致 |
            | 归因先决条件 | 若 schema 确未下发，归因向「提示词」修正 |
            | 置信度降级理由 | tools 定义未随 trace 返回，无法确认 R1 时字段清单已下发 |

            ## 轮次对照表

            | 轮次 | 工具调用参数 | 观察结果 | 规则命中 | 复核结论 | 数据来源 |
            | --- | --- | --- | --- | --- | --- |
            | R1 | SELECT count(*) FROM doc_fund_payment WHERE quarter = '2025Q3' | column "quarter" does not exist | TOOL-001@R1（属实） | 模型臆造列名，工具合法报错 | 反查还原 |
            | R2 | SELECT count(*) FROM doc_fund_payment WHERE pay_quarter = '2025Q3' | {"count":86} | 无 | 自行纠正，成功 | 原始落盘 |

            ## 归因推理链

            1. 排除工具缺陷：报错信息结构清晰（列名+语义错误类型），属合法失败。
            2. 排除信息缺失：R2 自行改用真实字段 pay_quarter 成功，说明模型本已掌握该字段。
            3. 排除编造：R3 输出 86 与工具返回严格一致。
            4. 残余不确定性：schema 是否下发未确认。

            ## 规则命中复核矩阵

            | 规则@轮次 | 实际命中 | 期望命中 | 依据 | 处理方 |
            | --- | --- | --- | --- | --- |
            | TOOL-001@R1 | 是 | 待定 | 合法失败是否命中 high 级需产品拍板 | 规则引擎 |

            ## 修复建议
            ① 【提示词层 · 落点：system prompt 模板】注入当前日期与 doc_fund_payment 字段清单。

            ## 复现信息

            | 项 | 值 |
            | --- | --- |
            | 会话 | dodo_conv_x1 / dodo_sess_y1 |
            | 触发来源 | 提问级全链路诊断 |
            | 异常类型 | 用户反馈语义类 |
            | 关键轮次 | R1（根因）/R2（纠正）/R3（输出） |
            """;

    @Test
    void v1ReportMetricsExtracted() {
        assertThat(DiagnoseReportMetrics.extract(V1_SAMPLE, DiagnoseReportMetrics.ATTRIBUTION)).isEqualTo("工具");
        assertThat(DiagnoseReportMetrics.extract(V1_SAMPLE, DiagnoseReportMetrics.CONFIDENCE)).isEqualTo("高");
        assertThat(DiagnoseReportMetrics.extract(V1_SAMPLE, DiagnoseReportMetrics.AFFECTED_ROUND)).isEqualTo("3");
        // v1 报告无报告头，异常类型提取为空串（metrics_json 存 null），不报错
        assertThat(DiagnoseReportMetrics.extract(V1_SAMPLE, DiagnoseReportMetrics.ANOMALY_TYPE)).isEmpty();
    }

    @Test
    void v2ReportMetricsExtracted() {
        assertThat(DiagnoseReportMetrics.extract(V2_SAMPLE, DiagnoseReportMetrics.ATTRIBUTION)).isEqualTo("模型能力");
        assertThat(DiagnoseReportMetrics.extract(V2_SAMPLE, DiagnoseReportMetrics.CONFIDENCE)).isEqualTo("中");
        // 影响轮行带后缀注释（根因轮/级联轮）时取首个轮次
        assertThat(DiagnoseReportMetrics.extract(V2_SAMPLE, DiagnoseReportMetrics.AFFECTED_ROUND)).isEqualTo("1");
        // 报告头异常类型行（§7 表格同名行在其后，首现即报告头）
        assertThat(DiagnoseReportMetrics.extract(V2_SAMPLE, DiagnoseReportMetrics.ANOMALY_TYPE))
                .isEqualTo("用户反馈语义类");
    }

    @Test
    void v2MetricsExtractionOnRuleLevelReport() {
        // 规则级诊断报告：「根因假设判定」置顶在最前，归因/影响轮在其后仍可提取
        String ruleLevel = """
                # 单会话诊断报告

                会话：dodo_conv_r / dodo_sess_r
                触发来源：规则级定向诊断（规则 TOOL-004）
                异常类型：模型生成类
                诊断时间：2026-11-23 16:00

                ## 根因假设判定

                主因：④ 模型幻觉（生成了 DELETE 写操作，用户请求未触发）。

                ## 结论摘要
                归因：【模型能力】（置信度：高）
                影响轮：R2
                模型在 R2 自行生成 DELETE 语句，触发 TOOL-004。
                """;
        assertThat(DiagnoseReportMetrics.extract(ruleLevel, DiagnoseReportMetrics.ATTRIBUTION)).isEqualTo("模型能力");
        assertThat(DiagnoseReportMetrics.extract(ruleLevel, DiagnoseReportMetrics.CONFIDENCE)).isEqualTo("高");
        assertThat(DiagnoseReportMetrics.extract(ruleLevel, DiagnoseReportMetrics.AFFECTED_ROUND)).isEqualTo("2");
        assertThat(DiagnoseReportMetrics.extract(ruleLevel, DiagnoseReportMetrics.ANOMALY_TYPE)).isEqualTo("模型生成类");
    }

    @Test
    void dailySummarySectionSkipsV2Tables() {
        String section = ReportScheduleService.extractSummarySection(V2_SAMPLE);
        // 固定行（归因/影响轮）与根因句进日报引用
        assertThat(section).contains("归因：【模型能力】").contains("R1 生成 SQL 使用不存在列");
        // v2 §1 表格行不进单行引用（拍扁成管道串不可读）
        assertThat(section).doesNotContain("|");
        // v1 报告无表格，行为不变
        assertThat(ReportScheduleService.extractSummarySection(V1_SAMPLE))
                .contains("归因：【工具】").doesNotContain("|");
    }

    @Test
    void promptSetSwitchesByVersion() {
        MonitorDiagnosePrompts.PromptSet v1 = MonitorDiagnosePrompts.PromptSet.of("v1");
        assertThat(v1.instructions()).isSameAs(MonitorDiagnosePrompts.MONITOR_INSTRUCTIONS_V1);
        assertThat(v1.userTemplate()).isSameAs(MonitorDiagnosePrompts.MONITOR_USER_TEMPLATE_V1);
        assertThat(v1.sessionTemplate()).isSameAs(MonitorDiagnosePrompts.MONITOR_SESSION_USER_TEMPLATE_V1);
        assertThat(v1.ruleTemplate()).isSameAs(MonitorDiagnosePrompts.MONITOR_RULE_USER_TEMPLATE_V1);

        // 默认与未知值均落 v2（回退安全侧：新版提示词 + 新报告）
        for (String version : new String[]{"v2", "V2", null, "", "garbage"}) {
            MonitorDiagnosePrompts.PromptSet v2 = MonitorDiagnosePrompts.PromptSet.of(version);
            assertThat(v2.instructions()).isSameAs(MonitorDiagnosePrompts.MONITOR_INSTRUCTIONS);
            assertThat(v2.userTemplate()).isSameAs(MonitorDiagnosePrompts.MONITOR_USER_TEMPLATE);
            assertThat(v2.sessionTemplate()).isSameAs(MonitorDiagnosePrompts.MONITOR_SESSION_USER_TEMPLATE);
            assertThat(v2.ruleTemplate()).isSameAs(MonitorDiagnosePrompts.MONITOR_RULE_USER_TEMPLATE);
        }
    }

    @Test
    void v2InstructionsContainSkeletonAndLockedLines() {
        String v2 = MonitorDiagnosePrompts.MONITOR_INSTRUCTIONS;
        // 七段骨架章节齐全（防误删）
        assertThat(v2).contains("## 可信度声明").contains("## 结论摘要").contains("## 轮次对照表")
                .contains("## 归因推理链").contains("## 规则命中复核矩阵").contains("## 待办")
                .contains("## 修复建议").contains("## 复现信息");
        // 异常类型三分支与归因六类
        assertThat(v2).contains("模型生成类").contains("规则采集类").contains("用户反馈语义类")
                .contains("## 归因类别（六选一）");
        // 两版锁死行措辞一致（正则提取契约）
        assertThat(v2).contains("归因：【").contains("影响轮：R{");
        assertThat(MonitorDiagnosePrompts.MONITOR_INSTRUCTIONS_V1).contains("归因：【").contains("影响轮：R{");
    }
}
