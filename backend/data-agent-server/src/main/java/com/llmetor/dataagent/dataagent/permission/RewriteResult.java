package com.llmetor.dataagent.dataagent.permission;

import com.llmetor.dataagent.common.enums.DataScope;

import java.util.List;

/**
 * 数据权限改写结果：改写后的 SQL（直接交给 EXPLAIN 预检与只读执行）+
 * 审计信息（生效范围、注入了过滤条件的表）。
 */
public record RewriteResult(String sql, DataScope dataScope, List<String> injectedTables) {
}
