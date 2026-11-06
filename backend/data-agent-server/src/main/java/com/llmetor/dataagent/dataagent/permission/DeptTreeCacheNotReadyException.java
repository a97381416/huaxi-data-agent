package com.llmetor.dataagent.dataagent.permission;

/**
 * 部门树缓存未就绪时抛出（DEPT/DEPT_AND_SUB 改写依赖子孙展开）。
 * fail-closed：宁可拒绝执行，也不在未知部门树下放行全量数据。
 * 继承 RewriteException，改写器的"意外异常回落 SELF"兜底不会吞掉它。
 */
public class DeptTreeCacheNotReadyException extends RewriteException {

    public DeptTreeCacheNotReadyException(String message) {
        super(message);
    }
}
