package com.llmetor.dataagent.dataagent.permission;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 条件表达式构造工具：把列引用 + 服务端解析出的 ID 集合拼成 JSqlParser 表达式。
 *
 * <p>所有值都来自服务端解析（userId / deptIds 均为 Long），不是用户输入，
 * 字符串拼接无注入面。空部门集合生成 {@code IN (NULL)}（恒不命中，比漏条件安全）。
 */
final class ConditionBuilder {

    private ConditionBuilder() {
    }

    /** {@code ref.col = userId} */
    static Expression eqUser(String ref, String column, Long userId) {
        return parse(ref + "." + column + " = " + userId);
    }

    /** {@code ref.col IN (id1, id2, ...)}；集合为空 → {@code ref.col IN (NULL)} */
    static Expression inDept(String ref, String column, Set<Long> deptIds) {
        String values = deptIds.isEmpty() ? "NULL"
                : deptIds.stream().map(String::valueOf).collect(Collectors.joining(", "));
        return parse(ref + "." + column + " IN (" + values + ")");
    }

    /** EXISTS 子查询文本 → 表达式（文本来自本包规则拼装，非用户输入） */
    static Expression parse(String condText) {
        try {
            return CCJSqlParserUtil.parseCondExpression(condText);
        } catch (Exception e) {
            // 本包内部拼装的表达式解析失败属程序缺陷，直接断言失败让上层兜底
            throw new IllegalStateException("权限条件表达式构造失败: " + condText, e);
        }
    }
}
