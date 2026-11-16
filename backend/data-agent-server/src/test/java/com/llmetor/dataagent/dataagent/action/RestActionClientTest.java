package com.llmetor.dataagent.dataagent.action;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

/**
 * RestActionClient 单测（M-E0）：MockRestServiceServer 验证转发协议与错误映射。
 * 覆盖：Bearer/tenant-id 头透传 / CommonResult 成功 / 401 / 403 / 业务错误透出 /
 * 5xx+可重试 / GET 重试后成功 / GET 重试耗尽 / 网络不可达降级 / 写不重试（网络异常也不重发）/
 * 缺 token。
 */
class RestActionClientTest {

    private static final String BASE = "http://192.168.2.77:9031/admin-api";
    private static final String TOKEN = "tok123";
    private static final long TENANT = 1L;

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private RestActionClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestActionClient(builder, BASE, 1); // GET 最多重试 1 次
    }

    @Test
    void getSuccessParssesCommonResultAndPassesAuthHeaders() {
        server.expect(requestTo(BASE + "/system/auth/get-permission-info"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(header("tenant-id", "1"))
                .andRespond(withSuccess(
                        "{\"code\":0,\"data\":{\"permissions\":[\"doc:project:file:fill\"]},\"msg\":\"\"}",
                        MediaType.APPLICATION_JSON));

        RestActionClient.ActionResult result =
                client.get("/system/auth/get-permission-info", Map.of(), TOKEN, TENANT);

        assertThat(result.success()).isTrue();
        assertThat(result.errorType()).isEqualTo(RestActionClient.ErrorType.NONE);
        assertThat(result.data().path("permissions").get(0).asText())
                .isEqualTo("doc:project:file:fill");
        server.verify();
    }

    @Test
    void queryParamsAreAppended() {
        server.expect(requestTo(BASE + "/doc/ledger/get?id=42"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"id\":42},\"msg\":\"\"}",
                        MediaType.APPLICATION_JSON));

        RestActionClient.ActionResult result =
                client.get("/doc/ledger/get", Map.of("id", "42"), TOKEN, TENANT);
        assertThat(result.success()).isTrue();
    }

    @Test
    void unauthorizedMapsToAuthExpired() {
        server.expect(requestTo(BASE + "/doc/ledger/get"))
                .andRespond(withUnauthorizedRequest().body(
                        "{\"code\":401,\"data\":null,\"msg\":\"账号未登录\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        RestActionClient.ActionResult result =
                client.get("/doc/ledger/get", Map.of(), TOKEN, TENANT);

        assertThat(result.success()).isFalse();
        assertThat(result.errorType()).isEqualTo(RestActionClient.ErrorType.AUTH_EXPIRED);
        assertThat(result.msg()).contains("重新登录");
        // 固定话术，不透出主后端原始 msg（防探测）
        assertThat(result.msg()).doesNotContain("账号未登录");
    }

    @Test
    void forbiddenMapsToNoPermission() {
        server.expect(requestTo(BASE + "/doc/project/delete?id=1"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess(
                        "{\"code\":403,\"data\":null,\"msg\":\"没有该操作权限\"}",
                        MediaType.APPLICATION_JSON));

        RestActionClient.ActionResult result =
                client.delete("/doc/project/delete", Map.of("id", "1"), TOKEN, TENANT);

        assertThat(result.errorType()).isEqualTo(RestActionClient.ErrorType.NO_PERMISSION);
        assertThat(result.msg()).contains("没有执行该操作的权限");
    }

    @Test
    void bizErrorTransparencyForLlm() {
        server.expect(requestTo(BASE + "/doc/project/create"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"code\":1002000001,\"data\":null,\"msg\":\"项目编码已存在\"}",
                        MediaType.APPLICATION_JSON));

        RestActionClient.ActionResult result = client.post(
                "/doc/project/create", Map.of("name", "x"), TOKEN, TENANT);

        assertThat(result.errorType()).isEqualTo(RestActionClient.ErrorType.BIZ_ERROR);
        // 业务错误结构化透出主后端 msg（LLM 需要它自纠/转述）
        assertThat(result.msg()).contains("1002000001").contains("项目编码已存在");
    }

    @Test
    void postSendsJsonBody() {
        server.expect(requestTo(BASE + "/doc/ledger/create"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.phaseId").value("77"))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"id\":9},\"msg\":\"\"}",
                        MediaType.APPLICATION_JSON));

        RestActionClient.ActionResult result = client.post(
                "/doc/ledger/create", Map.of("phaseId", "77"), TOKEN, TENANT);
        assertThat(result.success()).isTrue();
        assertThat(result.data().path("id").asLong()).isEqualTo(9);
    }

    @Test
    void getRetriesOnServerErrorAndSucceeds() {
        server.expect(requestTo(BASE + "/doc/ledger/get-draft"))
                .andRespond(withServerError().body("boom").contentType(MediaType.TEXT_PLAIN));
        server.expect(requestTo(BASE + "/doc/ledger/get-draft"))
                .andRespond(withSuccess("{\"code\":0,\"data\":{},\"msg\":\"\"}",
                        MediaType.APPLICATION_JSON));

        RestActionClient.ActionResult result =
                client.get("/doc/ledger/get-draft", Map.of(), TOKEN, TENANT);

        assertThat(result.success()).isTrue(); // 第一次 5xx → 重试 → 成功
        server.verify(); // 恰好两次请求
    }

    @Test
    void getRetryExhaustedReturnsServerError() {
        server.expect(requestTo(BASE + "/doc/ledger/get-draft"))
                .andRespond(withServerError().body("boom").contentType(MediaType.TEXT_PLAIN));
        server.expect(requestTo(BASE + "/doc/ledger/get-draft"))
                .andRespond(withServerError().body("boom").contentType(MediaType.TEXT_PLAIN));

        RestActionClient.ActionResult result =
                client.get("/doc/ledger/get-draft", Map.of(), TOKEN, TENANT);

        assertThat(result.success()).isFalse();
        assertThat(result.errorType()).isEqualTo(RestActionClient.ErrorType.SERVER_ERROR);
        assertThat(result.msg()).contains("主系统服务异常").contains("HTTP 500");
        server.verify(); // 1 次首发 + 1 次重试，不再多发
    }

    @Test
    void writeNeverRetriesEvenOnNetworkError() {
        // 幂等性未知：网络异常只发一次，绝不重发（防重复推进审批流）
        server.expect(requestTo(BASE + "/doc/ledger/resubmit"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withException(new java.io.IOException("connection reset")));

        RestActionClient.ActionResult result = client.put(
                "/doc/ledger/resubmit", Map.of("ledgerId", 1), TOKEN, TENANT);

        assertThat(result.success()).isFalse();
        assertThat(result.errorType()).isEqualTo(RestActionClient.ErrorType.UNREACHABLE);
        assertThat(result.msg()).contains("主系统暂不可达").contains("查询类问题不受影响");
        server.verify(); // 只发了一次
    }

    @Test
    void missingTokenShortCircuitsWithoutHttpCall() {
        RestActionClient.ActionResult result =
                client.get("/doc/ledger/get", Map.of(), null, TENANT);

        assertThat(result.errorType()).isEqualTo(RestActionClient.ErrorType.AUTH_MISSING);
        assertThat(result.msg()).contains("缺少登录凭证");
        server.verify(); // 零 HTTP 请求
    }

    @Test
    void bizErrorOnGetIsNotRetried() {
        // 4xx/业务错误重试必然同结果：只允许一次请求
        server.expect(requestTo(BASE + "/doc/ledger/get"))
                .andRespond(withSuccess("{\"code\":1001000000,\"data\":null,\"msg\":\"台账不存在\"}",
                        MediaType.APPLICATION_JSON));

        RestActionClient.ActionResult result =
                client.get("/doc/ledger/get", Map.of(), TOKEN, TENANT);

        assertThat(result.errorType()).isEqualTo(RestActionClient.ErrorType.BIZ_ERROR);
        server.verify(); // 未重试
    }
}
