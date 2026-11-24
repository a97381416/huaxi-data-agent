package com.llmetor.dataagent.dataagent.monitor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 诊断报告结构化指标提取（v1/v2 报告模板双兼容，措辞契约见 MonitorDiagnosePrompts 输出格式段）：
 * 归因/置信度/影响轮三个正则原口径集中于此——此前 MonitorDiagnoseService 与
 * ReportScheduleService 各持一份，模板升级时容易漏改漂移；v2 报告头新增异常类型行。
 * 报告模板可整体回退（data-agent.monitor.diagnose-template=v1），故提取必须对两版同时成立。
 */
public final class DiagnoseReportMetrics {

    public static final Pattern ATTRIBUTION = Pattern.compile("归因[：:]\\s*【?([^】\\n（(]+)】?");
    public static final Pattern CONFIDENCE = Pattern.compile("置信度[：:]\\s*([高中低])");
    public static final Pattern AFFECTED_ROUND = Pattern.compile("影响轮[：:]?\\s*R?(\\d+)");
    /** v2 报告头行「异常类型：模型生成类|规则采集类|用户反馈语义类」；v1 报告无此行，提取为空串 */
    public static final Pattern ANOMALY_TYPE =
            Pattern.compile("异常类型[：:]\\s*(模型生成类|规则采集类|用户反馈语义类)");

    private DiagnoseReportMetrics() {
    }

    /** 首个匹配的捕获组；无匹配或文本为空返回空串 */
    public static String extract(String text, Pattern pattern) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }
}
