package com.llmetor.dataagent.dataagent.permission;

/**
 * 数据权限改写无法安全完成时抛出：executeSql 会拒绝执行该 SQL（fail-closed，
 * 宁可让 LLM 改写 SQL，也不放行一条权限条件注入不进去的查询）。
 */
public class RewriteException extends RuntimeException {

    public RewriteException(String message) {
        super(message);
    }
}
