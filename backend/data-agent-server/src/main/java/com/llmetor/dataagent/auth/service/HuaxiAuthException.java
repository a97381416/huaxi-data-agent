package com.llmetor.dataagent.auth.service;

/**
 * 华西认证异常（M-H2）：登录失败统一抛出（401 语义，文案不区分具体原因防探测）。
 */
public class HuaxiAuthException extends RuntimeException {

    public HuaxiAuthException(String message) {
        super(message);
    }
}
