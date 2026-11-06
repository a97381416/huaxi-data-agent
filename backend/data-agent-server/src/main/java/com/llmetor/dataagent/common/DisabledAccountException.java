package com.llmetor.dataagent.common;

/**
 * 账号被禁用时抛出（登录或已禁用账号的其他操作）。
 *
 * <p>独立异常类型而不是复用 IllegalStateException——禁用是客户端错误（4xx），
 * 不是服务端故障（500）。复刻缺陷清单第 2 条：原版禁用账号登录返回 500，M4 修正。
 */
public class DisabledAccountException extends RuntimeException {

    public DisabledAccountException(String message) {
        super(message);
    }
}
