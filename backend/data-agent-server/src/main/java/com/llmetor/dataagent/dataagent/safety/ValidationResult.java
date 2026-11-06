package com.llmetor.dataagent.dataagent.safety;

/**
 * SQL 校验结果：valid=true 时 safeSql 为可执行的安全 SQL（已注入/下调 LIMIT）；
 * valid=false 时 reason 为面向 LLM 的修改建议。
 */
public record ValidationResult(boolean valid, String reason, String safeSql) {

    public static ValidationResult ok(String safeSql) {
        return new ValidationResult(true, "OK", safeSql);
    }

    public static ValidationResult reject(String reason) {
        return new ValidationResult(false, reason, null);
    }
}
