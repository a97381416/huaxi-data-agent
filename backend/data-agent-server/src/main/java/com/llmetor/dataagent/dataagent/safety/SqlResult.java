package com.llmetor.dataagent.dataagent.safety;

import java.util.List;
import java.util.Map;

/**
 * SQL 执行结果：成功带列元数据 + 行数据；失败带分类后的错误信息（供 LLM 修复 SQL）。
 */
public record SqlResult(
        boolean success,
        String executedSql,
        List<String> columns,
        List<ColumnMeta> columnMetas,
        List<Map<String, Object>> rows,
        int rowCount,
        boolean truncated,
        long durationMs,
        String error) {

    /** 列元数据（label + 表名.列名全名，脱敏匹配用，M4 消费） */
    public record ColumnMeta(String label, String tableName, String columnName) {
    }

    public static SqlResult ok(String executedSql, List<String> columns, List<ColumnMeta> columnMetas,
                               List<Map<String, Object>> rows, boolean truncated, long durationMs) {
        return new SqlResult(true, executedSql, columns, columnMetas, rows, rows.size(),
                truncated, durationMs, null);
    }

    public static SqlResult fail(String executedSql, String error, long durationMs) {
        return new SqlResult(false, executedSql, List.of(), List.of(), List.of(), 0, false, durationMs, error);
    }
}
