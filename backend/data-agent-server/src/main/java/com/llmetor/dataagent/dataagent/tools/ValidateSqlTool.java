package com.llmetor.dataagent.dataagent.tools;

import com.llmetor.dataagent.dataagent.safety.SqlSafetyGuard;
import com.llmetor.dataagent.dataagent.safety.ValidationResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * SQL 安全校验工具（LLM 主动调用）。executeSql 内部也会兜底校验一次（双保险）。
 */
@Component
public class ValidateSqlTool {

    private final SqlSafetyGuard guard;

    public ValidateSqlTool(SqlSafetyGuard guard) {
        this.guard = guard;
    }

    @Tool(name = "validateSql",
            description = "校验 SQL 是否安全（白名单/防注入/强制 LIMIT）。生成 SQL 后、执行前必须调用。" +
                    "参数名严格为 sql，严禁使用任何其他参数名。" +
                    "传错会收到 \"参数名错误\" 提示，必须改用 sql 重新调用。" +
                    "返回 valid=true 时给出可直接执行的安全 SQL（已注入 LIMIT）；" +
                    "返回 valid=false 时按 reason 修改 SQL 后重新校验。" +
                    "禁止写 INSERT/UPDATE/DELETE/DROP 等非查询语句；禁止多语句拼接；禁止危险函数。")
    public String validateSql(
            @ToolParam(description = "待校验的 SQL（仅支持 SELECT / WITH 查询）。参数名必须是 sql")
            String sql) {
        if (sql == null || sql.isBlank()) {
            return "❌ 参数错误：sql 为空。本工具参数名严格为 `sql`，禁止用任何其他名字。" +
                    "请重新调用 validateSql，把 SQL 语句放在 `sql` 参数中。";
        }

        ValidationResult r = guard.validate(sql);
        if (r.valid()) {
            return "校验通过。安全 SQL（已注入/截断 LIMIT，可直接交给 executeSql 执行）：\n" + r.safeSql();
        }
        return "校验未通过。" + r.reason() + "\n"
                + "请根据上述提示修改 SQL 后重新调用本工具校验。";
    }
}
