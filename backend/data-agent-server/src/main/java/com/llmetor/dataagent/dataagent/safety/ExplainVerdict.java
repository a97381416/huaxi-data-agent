package com.llmetor.dataagent.dataagent.safety;

/**
 * EXPLAIN 预检结论：allowed=false 时 reason 为面向 LLM 的拦截说明与改写引导。
 */
public record ExplainVerdict(boolean allowed, String reason, long planRows) {

    public static ExplainVerdict allow(long planRows) {
        return new ExplainVerdict(true, "OK", planRows);
    }

    public static ExplainVerdict block(String reason, long planRows) {
        return new ExplainVerdict(false, reason, planRows);
    }
}
