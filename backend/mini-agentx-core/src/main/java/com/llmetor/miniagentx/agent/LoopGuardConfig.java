package com.llmetor.miniagentx.agent;

import java.util.Set;

/**
 * 循环熔断配置（2026-11-12 TodoWrite 死循环事故修复 Fix A，docs/错误诊断集合）。
 *
 * <p>判定口径：一轮的全部工具调用均为同一工具（"纯单工具轮"）才对该工具计数；
 * 计数按工具名分别累计、不因其他工具穿插而重置（事故中 context_reload 在 round 31/64
 * 穿插后又回到 TodoWrite，严格"连续"计数会被绕过）。豁免名单内的工具
 * （如 executeSql 这类合法高频工具）不计数不告警。
 *
 * @param enabled       是否启用
 * @param warnAfter     纯单工具轮累计第 N 次注入系统提示
 * @param escalateAfter 累计第 N 次注入强警告
 * @param breakAfter    累计第 N 次熔断：跳过执行、进 forceFinal
 * @param ignoreTools   豁免名单（工具名）
 */
public record LoopGuardConfig(
        boolean enabled,
        int warnAfter,
        int escalateAfter,
        int breakAfter,
        Set<String> ignoreTools) {

    public LoopGuardConfig {
        ignoreTools = ignoreTools == null ? Set.of() : Set.copyOf(ignoreTools);
    }

    /** 默认开启：3/5/7 阈值、无豁免（保险丝应默认在位） */
    public static LoopGuardConfig defaults() {
        return new LoopGuardConfig(true, 3, 5, 7, Set.of());
    }

    /** 显式关闭熔断 */
    public static LoopGuardConfig disabled() {
        return new LoopGuardConfig(false, 3, 5, 7, Set.of());
    }
}
