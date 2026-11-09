package com.llmetor.dataagent.dataagent.permission;

import net.sf.jsqlparser.expression.Expression;

import java.util.Set;

/**
 * 数据权限改写规则 SPI：一条规则管一组表，负责把"可见范围并集"翻译成该表的过滤条件。
 *
 * <p>注册即生效（Spring @Component 扫入 {@link DataScopeRewriter} 的规则表），
 * 新增管控表只需实现本接口注册 Bean，改写器不感知具体表。
 *
 * <p>条件由 {@link DataScopeContext} 并集落点（all/self/deptIds）
 * 组合而成，与主后端 DeptDataPermissionRule 的 OR 组合同构——不再区分 SELF/DEPT 档位。
 */
public interface PermissionRule {

    /**
     * 该规则管控的表名集合（小写，不带库名）。
     */
    Set<String> managedTables();

    /**
     * 按可见范围并集构建该表的过滤条件。
     *
     * @param ref       该表在当前 SELECT 作用域里的引用名（别名，无别名时为表名）
     * @param tableName 表名（小写，不带库名）
     * @param ctx       当前用户可见范围并集
     * @return 条件表达式；返回 null 表示该表无需注入（如 ALL 档的普通表）
     */
    Expression buildCondition(String ref, String tableName, DataScopeContext ctx);
}
