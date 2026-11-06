package com.llmetor.dataagent.dataagent.permission;

import net.sf.jsqlparser.expression.Expression;

import java.util.Set;

/**
 * 数据权限改写规则 SPI：一条规则管一组表，负责把"数据范围"翻译成该表的过滤条件。
 *
 * <p>注册即生效（Spring @Component 扫入 {@link DataScopeRewriter} 的规则表），
 * 新增管控表只需实现本接口注册 Bean，改写器不感知具体表。
 * 匹配顺序：先注册的具体表规则优先，DefaultPermissionRule 兜底最后。
 */
public interface PermissionRule {

    /**
     * 该规则管控的表名集合（小写，不带库名）。
     */
    Set<String> managedTables();

    /**
     * SELF 范围的条件：只能看到自己的数据（如 {@code 别名.user_id = :userId}）。
     *
     * @param ref    该表在当前 SELECT 作用域里的引用名（别名，无别名时为表名）
     * @param userId 当前用户 ID
     */
    Expression buildSelfCondition(String ref, String tableName, Long userId);

    /**
     * DEPT / DEPT_AND_SUB 范围的条件：本部门（含子孙已展开）的数据。
     *
     * @param ref            引用名（同上）
     * @param deptIdAndSubIds 部门 ID 集合（DEPT=直接挂载；DEPT_AND_SUB=含子孙展开）。
     *                        可能空前集合——此时应生成恒假条件（如 {@code IN (NULL)}）
     */
    Expression buildDeptCondition(String ref, String tableName, Set<Long> deptIdAndSubIds);
}
