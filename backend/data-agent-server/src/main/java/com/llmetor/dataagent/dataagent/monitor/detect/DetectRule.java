package com.llmetor.dataagent.dataagent.monitor.detect;

import java.util.List;
import java.util.Map;

/**
 * 异常检测规则 SPI（03 方案 §4，Step 3「找到异常」）：规则实现类只做纯判定，
 * 数据访问全部经 {@link DetectContext}（瘦行 + 惰性 payload 视图，观察在 SQL 侧截取），
 * 命中产物 = {@link RiskHit}（规则 ID + 级别 + 定位轮 + 证据），落 {@code agentx_monitor_risk}。
 *
 * <p>判定逻辑在代码（SPI 实现类），阈值/开关在配置表（{@link DetectConfig}，web 可改即时生效）；
 * 注册顺序即执行顺序（ANSWER-001 依赖 TOOL-001/REACT-002 的命中，见 DetectRuleRegistry）。
 */
public interface DetectRule {

    /** 规则 ID，如 "REACT-003"（落 agentx_monitor_risk.rule_id，发布后勿改） */
    String id();

    /** 规则名（风险卡标题 / 规则弹层，落 agentx_monitor_risk.title） */
    String title();

    /** 固定级别：high=链路已坏/结论不可信，medium=高度可疑，low=提示（不进配置表） */
    String severity();

    /** 一句话说明（规则弹层「说明」列） */
    String description();

    /** 代码默认阈值（配置表 seed 与缺行兜底都取这里，如 {"maxRounds":15}） */
    Map<String, Object> defaultParams();

    /** 代码默认开关（TOOL-005 默认关闭，其余 true；配置表行覆盖） */
    default boolean defaultEnabled() {
        return true;
    }

    /** 是否启用（配置表行 → 代码默认；enabled=false 跳过 eval） */
    boolean enabled(DetectConfig cfg);

    /**
     * 判定：命中返回 RiskHit 列表（一次命中一行），未命中返回空列表。
     * 实现约定：自吞异常（单条规则挂掉不影响其余规则），惰性 payload 读取失败视为无数据（漏报不误报）。
     */
    List<RiskHit> eval(DetectContext ctx);
}
