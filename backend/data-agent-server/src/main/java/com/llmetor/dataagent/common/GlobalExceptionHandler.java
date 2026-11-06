package com.llmetor.dataagent.common;

import com.llmetor.dataagent.auth.service.HuaxiAuthException;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;
import java.util.Locale;

/**
 * 全局异常处理：把异常统一转成 R 响应体。
 *
 * - HuaxiAuthException       → 401（M-H2：华西鉴权失败，前端收到后跳登录页）
 * - 客户端断开（SSE）          → warn 一行提示（M3 补齐）
 * - IllegalArgumentException → 400（参数错误）
 * - 兜底 Exception           → 500
 *
 * 注意：流式接口（SSE）执行过程中的业务异常不经过 ControllerAdvice，由 Reactor 的
 * onError 信号传递、前端在 Error 事件里处理；但客户端断开导致的写出异常会走异步
 * ERROR 分发进入这里。M3 起执行与 HTTP 连接解耦（replay + 后台驱动），断开不影响
 * 服务端执行、前端回来还能 recovery 续传，属预期现象，降噪为 warn。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HuaxiAuthException.class)
    public ResponseEntity<R<Void>> handleNotLogin(HuaxiAuthException e) {
        // HTTP 401 让前端请求拦截器统一处理（跳转登录页）
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(R.unauthorized(e.getMessage()));
    }

    /**
     * 账号被禁用 → HTTP 403（4xx 客户端错误，不是 500）。
     * 复刻缺陷清单第 2 条：原版禁用账号登录返回 500，M4 修正（M4-D06 验收项）。
     */
    @ExceptionHandler(DisabledAccountException.class)
    public ResponseEntity<R<Void>> handleDisabledAccount(DisabledAccountException e) {
        log.warn("账号被禁用: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(R.forbidden(e.getMessage()));
    }

    /**
     * 客户端提前断开（关闭/刷新页面、中断 SSE）：响应写回失败属预期行为。
     * <p>
     * Windows 的连接中止有时以裸 IOException 上抛（不被 Tomcat 包成 ClientAbortException），
     * 沿异常链按类名与消息特征识别；其余 IOException 仍按系统异常处理。
     */
    @ExceptionHandler({AsyncRequestNotUsableException.class, IOException.class})
    public ResponseEntity<R<Void>> handleClientAbort(Exception e) {
        if (isClientAbort(e)) {
            log.warn("[data-agent] 客户端已断开，SSE 连接中止（回答仍在后台执行，重连可续传）");
            return null;   // 连接已断，响应体写不出去，返回 null 避免二次异常
        }
        log.error("系统异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail("IO 异常：" + e.getMessage()));
    }

    /** 沿异常链识别客户端断开：AsyncRequestNotUsableException 或连接中止/重置/断管类 IOException */
    private static boolean isClientAbort(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof AsyncRequestNotUsableException) {
                return true;
            }
            String message = current.getMessage();
            if (current instanceof ClientAbortException
                    || (current instanceof IOException && message != null && isAbortMessage(message))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isAbortMessage(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("reset") || lower.contains("broken pipe") || lower.contains("aborted")
                || message.contains("中止") || message.contains("远程主机强迫关闭");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public R<Void> handleIllegalArg(IllegalArgumentException e) {
        return R.paramError(e.getMessage());
    }

    /**
     * Controller 抛出的 ResponseStatusException（recovery 属主 403 / 无流 404 / takeOver 超时 409）
     * 透出真实 HTTP 状态码：SSE 端点上的同步异常会被兜底 Exception 处理器吞成 HTTP 200 + code:500，
     * 前端按状态码分支（404=流已结束拉历史）会失效，必须单独处理。
     */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<R<Void>> handleResponseStatus(org.springframework.web.server.ResponseStatusException e) {
        log.warn("[data-agent] 请求被拒绝: {}", e.getMessage());
        return ResponseEntity.status(e.getStatusCode())
                .body(R.fail(e.getStatusCode().value(), e.getReason()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public R<Void> handleIllegalState(IllegalStateException e) {
        return R.fail(e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return R.fail(e.getMessage());
    }
}
