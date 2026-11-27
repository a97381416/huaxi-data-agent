package com.llmetor.dataagent.dataagent.action;

import lombok.Getter;

/**
 * 三期 HITL：写操作（POST/PUT/DELETE）结果未知异常。
 *
 * <p>当写请求已发出但在等待响应时发生 ReadTimeout / 504 / 连接重置 / 响应体断流，
 * 主平台可能已受理也可能未受理——<b>严禁自动重试</b>，由上层捕获并拉起 {@code VERIFY_OUTCOME} 核对型 HITL。
 */
@Getter
public class UnknownOutcomeException extends RuntimeException {

    private final String method;
    private final String path;
    private final String requestTime;
    private final String errorSummary;
    private final int httpStatus;

    public UnknownOutcomeException(String method, String path, String requestTime,
                                   String errorSummary, int httpStatus, Throwable cause) {
        super("写操作结果未知 [" + method + " " + path + "]: " + errorSummary, cause);
        this.method = method;
        this.path = path;
        this.requestTime = requestTime;
        this.errorSummary = errorSummary;
        this.httpStatus = httpStatus;
    }
}
