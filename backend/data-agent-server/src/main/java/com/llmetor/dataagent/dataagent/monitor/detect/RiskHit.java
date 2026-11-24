package com.llmetor.dataagent.dataagent.monitor.detect;

/**
 * 一次规则命中（agentx_monitor_risk 一行的内存形态，RiskStore.replace 落库）。
 *
 * @param ruleId       规则 ID（REACT-003 / TOOL-001 …）
 * @param severity     high / medium / low
 * @param round        主定位轮（提问内 round，深链用；可空=规则不定位到轮）
 * @param traceId      主定位轮 trace 行 id（可空）
 * @param roundsHit    跨轮规则涉及轮清单 "5,6,7"（可空）
 * @param detail       证据摘要，一句人话（列表 first_risk / 风险卡直出）
 * @param evidenceJson 结构化证据 JSON 字符串（落 jsonb：args哈希/观察摘录/比对数字）
 */
public record RiskHit(String ruleId, String severity, Integer round, Long traceId,
                      String roundsHit, String detail, String evidenceJson) {
}
