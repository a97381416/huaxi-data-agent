package com.llmetor.dataagent.dataagent.permission;

import java.util.List;

/**
 * 数据权限改写结果：改写后的 SQL（直接交给 EXPLAIN 预检与只读执行）+
 * 审计信息（生效范围摘要、注入了过滤条件的表）。
 */
public record RewriteResult(String sql, String scopeSummary, List<String> injectedTables) {
}
