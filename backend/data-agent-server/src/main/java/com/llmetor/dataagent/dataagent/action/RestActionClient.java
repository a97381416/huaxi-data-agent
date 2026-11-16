package com.llmetor.dataagent.dataagent.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * 写通道 REST 转发客户端（M-E0）：agent 进程内以**当前用户身份**调主后端 /admin-api/*。
 *
 * <p>转发协议（对齐 yudao）：{@code Authorization: Bearer {token}} + {@code tenant-id: {租户}}；
 * 响应为 CommonResult {code, data, msg}，code=0 成功，非 0 结构化透出 msg 给 LLM。
 *
 * <p>重试策略：**写请求（POST/PUT/DELETE）一律不重试**（幂等性未知，重复提交可能重复推进审批流）；
 * 读请求（GET）在网络异常/5xx 时可重试（默认 1 次，{@code data-agent.action.read-retry-times}）。
 *
 * <p>错误映射（面向 LLM，结构化而非裸栈）：
 * <ul>
 *   <li>401 → 登录已过期，引导重新登录</li>
 *   <li>403 → 无权限（不泄露权限机制细节，对齐数据披露红线话术风格）</li>
 *   <li>其他非 0 code → 业务错误，透出主后端 msg</li>
 *   <li>网络不可达 → 降级话术，读能力不受影响</li>
 * </ul>
 */
@Slf4j
@Component
public class RestActionClient {

    /** 错误分类：驱动上层（工具层/提示词）给出针对性话术 */
    public enum ErrorType {
        /** 成功 */
        NONE,
        /** 缺少登录态（本侧就没拿到 token） */
        AUTH_MISSING,
        /** 主后端判定登录过期/无效（code=401） */
        AUTH_EXPIRED,
        /** 主后端判定无权限（code=403） */
        NO_PERMISSION,
        /** 主后端业务错误（其他非 0 code） */
        BIZ_ERROR,
        /** 主后端不可达（网络/超时） */
        UNREACHABLE,
        /** 主后端 5xx（服务异常，响应体非 CommonResult） */
        SERVER_ERROR
    }

    /**
     * 转发结果：成功携带 data（JsonNode）；失败携带分类 + 话术。
     * {@link #toToolMessage()} 产出面向 LLM 的结构化文本。
     */
    public record ActionResult(boolean success, int httpStatus, Integer code, String msg,
                               JsonNode data, ErrorType errorType) {

        public static ActionResult ok(int httpStatus, JsonNode data) {
            return new ActionResult(true, httpStatus, 0, null, data, ErrorType.NONE);
        }

        public static ActionResult fail(int httpStatus, Integer code, String msg, ErrorType type) {
            return new ActionResult(false, httpStatus, code, msg, null, type);
        }

        /** 面向 LLM 的结构化输出：成功给数据，失败给分类话术（含主后端原始 msg） */
        public String toToolMessage() {
            if (success) {
                return "✅ 主系统操作成功。返回数据（JSON）：" + (data == null ? "无" : data.toString());
            }
            return "❌ 主系统操作未完成：" + msg;
        }
    }

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int readRetryTimes;

    @org.springframework.beans.factory.annotation.Autowired
    public RestActionClient(
            @Value("${data-agent.action.base-url:http://127.0.0.1:9031/admin-api}") String baseUrl,
            @Value("${data-agent.action.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${data-agent.action.read-timeout-ms:15000}") int readTimeoutMs,
            @Value("${data-agent.action.read-retry-times:1}") int readRetryTimes) {
        this(RestClient.builder(), baseUrl, connectTimeoutMs, readTimeoutMs, readRetryTimes, true);
    }

    /**
     * 测试构造：builder 已被 MockRestServiceServer 绑定（requestFactory 为 mock，
     * 不可再覆盖），因此本构造不设超时工厂。
     */
    RestActionClient(RestClient.Builder mockBoundBuilder, String baseUrl, int readRetryTimes) {
        this(mockBoundBuilder, baseUrl, 0, 0, readRetryTimes, false);
    }

    private RestActionClient(RestClient.Builder builder, String baseUrl,
                             int connectTimeoutMs, int readTimeoutMs, int readRetryTimes,
                             boolean applyTimeoutFactory) {
        if (applyTimeoutFactory) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(connectTimeoutMs);
            factory.setReadTimeout(readTimeoutMs);
            builder.requestFactory(factory);
        }
        this.restClient = builder.baseUrl(baseUrl).build();
        this.readRetryTimes = readRetryTimes;
        log.info("[action] RestActionClient 就绪: baseUrl={}, readRetryTimes={}, 写请求不重试",
                baseUrl, readRetryTimes);
    }

    // ── 对外方法：path 为 /admin-api 之后的路径（如 /doc/ledger/get）────────────

    /** 读（GET）：网络异常/5xx 重试 readRetryTimes 次 */
    public ActionResult get(String path, Map<String, String> query, String token, Long tenantId) {
        ActionResult result = exchangeOnce(path, query, token, tenantId, HttpMethod.GET, null);
        for (int attempt = 0; attempt < readRetryTimes && isRetryable(result); attempt++) {
            log.warn("[action] GET {} 第{}次重试（errorType={}）", path, attempt + 1, result.errorType());
            result = exchangeOnce(path, query, token, tenantId, HttpMethod.GET, null);
        }
        return result;
    }

    /** 写（POST）：不重试 */
    public ActionResult post(String path, Object body, String token, Long tenantId) {
        return exchangeOnce(path, null, token, tenantId, HttpMethod.POST, body);
    }

    /** 写（PUT）：不重试 */
    public ActionResult put(String path, Object body, String token, Long tenantId) {
        return exchangeOnce(path, null, token, tenantId, HttpMethod.PUT, body);
    }

    /** 写（DELETE）：不重试 */
    public ActionResult delete(String path, Map<String, String> query, String token, Long tenantId) {
        return exchangeOnce(path, query, token, tenantId, HttpMethod.DELETE, null);
    }

    // ── 内部实现 ──────────────────────────────────────────────────────────────

    /** 网络/5xx 才重试；4xx 与业务错误不重试（重试必然同样失败） */
    private static boolean isRetryable(ActionResult r) {
        return r.errorType() == ErrorType.UNREACHABLE || r.errorType() == ErrorType.SERVER_ERROR;
    }

    private ActionResult exchangeOnce(String path, Map<String, String> query,
                                      String token, Long tenantId, HttpMethod method, Object body) {
        if (token == null || token.isBlank()) {
            return ActionResult.fail(0, 401, "当前会话缺少登录凭证，请重新登录后再执行该操作。", ErrorType.AUTH_MISSING);
        }
        try {
            RestClient.RequestBodySpec spec = restClient
                    .method(method)
                    .uri(uriBuilder -> {
                        uriBuilder.path(path);
                        if (query != null) {
                            query.forEach((k, v) -> {
                                if (k != null && v != null) {
                                    uriBuilder.queryParam(k, v);
                                }
                            });
                        }
                        return uriBuilder.build();
                    })
                    .headers(h -> applyAuthHeaders(h, token, tenantId));
            RestClient.ResponseSpec response = (body != null)
                    ? spec.contentType(MediaType.APPLICATION_JSON).body(body).retrieve()
                    : spec.retrieve();
            String json = response.body(String.class);
            return parseCommonResult(200, json);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String json = e.getResponseBodyAsString();
            if (status >= 500) {
                // 5xx：服务级异常，可重试分类；尽量从 body 取原因，取不到用固定降级话术
                String reason = extractMsg(json);
                return ActionResult.fail(status, null,
                        "主系统服务异常（HTTP " + status + "）"
                                + (reason == null ? "，请稍后再试或前往主平台操作。" : "：" + reason),
                        ErrorType.SERVER_ERROR);
            }
            // 4xx：yudao GlobalExceptionHandler 仍返回 CommonResult JSON（401/403/400）
            return parseCommonResult(status, json);
        } catch (ResourceAccessException e) {
            log.warn("[action] 主系统不可达: {} {} - {}", method, path, e.getMessage());
            return ActionResult.fail(0, null,
                    "主系统暂不可达（网络异常），请稍后再试，或前往主平台操作。查询类问题不受影响。",
                    ErrorType.UNREACHABLE);
        } catch (Exception e) {
            log.warn("[action] 转发异常: {} {} - {}", method, path, e.getMessage());
            return ActionResult.fail(0, null, "调用主系统时发生未知异常，请稍后再试。", ErrorType.SERVER_ERROR);
        }
    }

    private void applyAuthHeaders(HttpHeaders headers, String token, Long tenantId) {
        headers.setBearerAuth(token);
        if (tenantId != null) {
            headers.set("tenant-id", String.valueOf(tenantId));
        }
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
    }

    /** CommonResult {code,msg,data} 解析：code=0 成功；401/403/其他分类映射 */
    private ActionResult parseCommonResult(int httpStatus, String json) {
        if (json == null || json.isBlank()) {
            return ActionResult.fail(httpStatus, null, "主系统返回空响应。", ErrorType.SERVER_ERROR);
        }
        try {
            JsonNode node = mapper.readTree(json);
            int code = node.path("code").asInt(-1);
            String msg = node.path("msg").asText("");
            if (code == 0 && httpStatus < 400) {
                return ActionResult.ok(httpStatus, node.path("data"));
            }
            return ActionResult.fail(httpStatus, code, bizMessage(code, msg), mapErrorType(code));
        } catch (Exception e) {
            return ActionResult.fail(httpStatus, null,
                    "主系统响应格式异常（无法解析），请稍后再试。", ErrorType.SERVER_ERROR);
        }
    }

    /** 尽力从响应体提取 msg（不要求是合法 CommonResult） */
    private String extractMsg(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            String msg = node.path("msg").asText(null);
            return (msg == null || msg.isBlank()) ? null : msg;
        } catch (Exception e) {
            return null;
        }
    }

    /** code → 错误分类（对齐 yudao GlobalErrorCodeConstants） */
    private static ErrorType mapErrorType(int code) {
        if (code == 401) {
            return ErrorType.AUTH_EXPIRED;
        }
        if (code == 403) {
            return ErrorType.NO_PERMISSION;
        }
        return ErrorType.BIZ_ERROR;
    }

    /** code + 主后端 msg → 面向 LLM 的话术（401/403 固定话术，其余透出 msg） */
    private static String bizMessage(int code, String mainMsg) {
        String safeMsg = (mainMsg == null || mainMsg.isBlank()) ? "（主系统未返回原因）" : mainMsg;
        return switch (code) {
            case 401 -> "登录已过期或登录态无效，请引导用户重新登录后再试。";
            case 403 -> "您没有执行该操作的权限。如需开通，请联系管理员在主平台配置后再试。";
            default -> "主系统返回错误（code=" + code + "）：" + safeMsg;
        };
    }
}
