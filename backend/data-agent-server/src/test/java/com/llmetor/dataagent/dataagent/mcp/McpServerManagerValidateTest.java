package com.llmetor.dataagent.dataagent.mcp;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * McpServerManager 纯逻辑单测（M6 补充，不连 DB 不建真客户端）：
 * 三型字段校验与归一化、凭据脱敏、STDIO JSON 参数解析、"回传含 *** = 未修改"合并契约。
 */
class McpServerManagerValidateTest {

    /** 纯逻辑测试实例：Mapper 传 null，DataSource 传空桩（测试不触库，仅满足 JdbcTemplate 构造校验） */
    private static McpServerManager manager() {
        return new McpServerManager(new NullDataSource(), null, new McpClientRegistry(), "", "", "");
    }

    /** 全方法抛错的 DataSource 桩（JdbcTemplate 构造要求非 null，本测试不触库） */
    static final class NullDataSource implements javax.sql.DataSource {
        @Override public java.sql.Connection getConnection() {
            throw new UnsupportedOperationException("纯逻辑测试不应触库");
        }

        @Override public java.sql.Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException("纯逻辑测试不应触库");
        }

        @Override public java.io.PrintWriter getLogWriter() {
            return null;
        }

        @Override public void setLogWriter(java.io.PrintWriter out) {
        }

        @Override public void setLoginTimeout(int seconds) {
        }

        @Override public int getLoginTimeout() {
            return 0;
        }

        @Override public java.util.logging.Logger getParentLogger() {
            throw new UnsupportedOperationException();
        }

        @Override public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException();
        }

        @Override public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    private static AgentxMcpServer row(String transport) {
        AgentxMcpServer row = new AgentxMcpServer();
        row.setName("demo");
        row.setTransport(transport);
        row.setUrl("http://localhost:3033/mcp");
        row.setTimeoutMs(60000);
        row.setInjectMode(McpServerManager.INJECT_ALWAYS);
        row.setAuthType(McpServerManager.AUTH_NONE);
        return row;
    }

    // ==================== validate：三型字段约束 ====================

    @Test
    void streamableRequiresHttpUrl() {
        AgentxMcpServer r = row(McpServerManager.TRANSPORT_STREAMABLE);
        r.setUrl("ftp://x");
        assertThrows(IllegalArgumentException.class, () -> McpServerManager.validate(r), "非 http(s) 拒绝");
        r.setUrl(null);
        assertThrows(IllegalArgumentException.class, () -> McpServerManager.validate(r), "HTTP 系 URL 必填");
    }

    @Test
    void streamableClearsStdioFieldsAndSseEndpoint() {
        AgentxMcpServer r = row(McpServerManager.TRANSPORT_STREAMABLE);
        r.setCommand("npx.cmd");
        r.setArgs("[]");
        r.setSseEndpoint("/sse");
        McpServerManager.validate(r);
        assertNull(r.getCommand(), "HTTP 系清空 STDIO 专属字段");
        assertNull(r.getSseEndpoint(), "STREAMABLE 清空 SSE 端点");
    }

    @Test
    void sseDefaultsEndpointWhenBlank() {
        AgentxMcpServer r = row(McpServerManager.TRANSPORT_SSE);
        McpServerManager.validate(r);
        assertEquals("/sse", r.getSseEndpoint(), "SSE 端点缺省 /sse");
    }

    @Test
    void stdioRequiresCommandAndClearsHttpFieldsAndForcesAuthNone() {
        AgentxMcpServer r = row(McpServerManager.TRANSPORT_STDIO);
        r.setUrl("http://x");
        r.setAuthType(McpServerManager.AUTH_BEARER);
        r.setAuthToken("sk-xxx");
        assertThrows(IllegalArgumentException.class, () -> McpServerManager.validate(r), "STDIO 必须 command");

        r.setCommand("npx.cmd");
        r.setArgs("[\"-y\",\"@modelcontextprotocol/server-filesystem\"]");
        r.setEnv("{\"API_KEY\":\"abc\"}");
        McpServerManager.validate(r);
        assertNull(r.getUrl(), "STDIO 清空 url");
        assertNull(r.getAuthToken(), "STDIO 凭据走 env，token 清空");
        assertEquals(McpServerManager.AUTH_NONE, r.getAuthType(), "STDIO 强制 NONE");
    }

    @Test
    void stdioRejectsBadJsonArgsAndEnv() {
        AgentxMcpServer r = row(McpServerManager.TRANSPORT_STDIO);
        r.setCommand("npx.cmd");
        r.setArgs("-y 包名");   // 非 JSON 数组
        assertThrows(IllegalArgumentException.class, () -> McpServerManager.validate(r));
        r.setArgs("[]");
        r.setEnv("API_KEY=abc"); // 非 JSON 对象
        assertThrows(IllegalArgumentException.class, () -> McpServerManager.validate(r));
    }

    @Test
    void bearerWithoutTokenAllowedButStoredNull() {
        AgentxMcpServer r = row(McpServerManager.TRANSPORT_STREAMABLE);
        r.setAuthType(McpServerManager.AUTH_BEARER);
        r.setAuthToken("  ");
        McpServerManager.validate(r);
        assertNull(r.getAuthToken(), "BEARER 无凭据允许保存（远端可能匿名可访问）");
    }

    @Test
    void timeoutRangeEnforced() {
        AgentxMcpServer r = row(McpServerManager.TRANSPORT_STREAMABLE);
        r.setTimeoutMs(500);
        assertThrows(IllegalArgumentException.class, () -> McpServerManager.validate(r), "低于 1s 拒绝");
        r.setTimeoutMs(400000);
        assertThrows(IllegalArgumentException.class, () -> McpServerManager.validate(r), "高于 300s 拒绝");
        r.setTimeoutMs(null);
        McpServerManager.validate(r);
        assertEquals(60000, r.getTimeoutMs(), "缺省 60s");
    }

    @Test
    void nameRejectsBlankAndWhitespace() {
        AgentxMcpServer r = row(McpServerManager.TRANSPORT_STREAMABLE);
        r.setName("my server");
        assertThrows(IllegalArgumentException.class, () -> McpServerManager.validate(r), "空格拒绝（注册表键）");
        r.setName(" ");
        assertThrows(IllegalArgumentException.class, () -> McpServerManager.validate(r));
    }

    // ==================== 归一化 ====================

    @Test
    void normalizeDefaultsAndRejections() {
        assertEquals(McpServerManager.TRANSPORT_STREAMABLE, McpServerManager.normalizeTransport(null));
        assertEquals(McpServerManager.TRANSPORT_STDIO,
                McpServerManager.normalizeTransport("stdio"), "大小写归一");
        assertThrows(IllegalArgumentException.class, () -> McpServerManager.normalizeTransport("WEBSOCKET"));

        assertEquals(McpServerManager.INJECT_ALWAYS, McpServerManager.normalizeInjectMode(null));
        assertEquals(McpServerManager.INJECT_ONLINE_TOGGLE, McpServerManager.normalizeInjectMode("online_toggle"));
        assertThrows(IllegalArgumentException.class, () -> McpServerManager.normalizeInjectMode("x"));

        assertEquals(McpServerManager.AUTH_NONE, McpServerManager.normalizeAuthType(null));
    }

    // ==================== 脱敏 ====================

    @Test
    void maskSecretKeepsHeadAndTail() {
        assertEquals("", McpServerManager.maskSecret(null));
        assertEquals("", McpServerManager.maskSecret("  "));
        assertEquals("***", McpServerManager.maskSecret("short"));
        assertEquals("sk-***b4ef", McpServerManager.maskSecret("sk-1234567890b4ef"));
        assertTrue(McpServerManager.maskSecret("sk-1234567890b4ef").contains("***"));
    }

    @Test
    void looksSecretMatchesCommonKeyNames() {
        assertTrue(McpServerManager.looksSecret("API_KEY"));
        assertTrue(McpServerManager.looksSecret("accessToken"));
        assertTrue(McpServerManager.looksSecret("client_secret"));
        assertTrue(McpServerManager.looksSecret("DB_PASSWORD"));
        assertTrue(!McpServerManager.looksSecret("LOG_LEVEL"));
        assertTrue(!McpServerManager.looksSecret("HOME"));
    }

    // ==================== JSON 参数解析 ====================

    @Test
    void parseArgsAndEnv() {
        assertTrue(McpServerManager.parseArgs(null).isEmpty());
        assertTrue(McpServerManager.parseArgs("  ").isEmpty());
        assertEquals(2, McpServerManager.parseArgs("[\"-y\",\"pkg\"]").size());
        assertThrows(IllegalArgumentException.class, () -> McpServerManager.parseArgs("-y pkg"));

        assertTrue(McpServerManager.parseEnv(null).isEmpty());
        Map<String, String> env = McpServerManager.parseEnv("{\"A\":\"1\",\"B\":\"2\"}");
        assertEquals(2, env.size());
        assertEquals("1", env.get("A"));
        assertThrows(IllegalArgumentException.class, () -> McpServerManager.parseEnv("A=1"));
    }

    // ==================== "回传含 *** = 未修改"合并契约 ====================

    @Test
    void mergeSecretsKeepsMaskedTokenFromExisting() {
        McpServerManager manager = manager();

        AgentxMcpServer existing = row(McpServerManager.TRANSPORT_STREAMABLE);
        existing.setAuthType(McpServerManager.AUTH_BEARER);
        existing.setAuthToken("sk-real-secret-9999");

        // 编辑回传脱敏值（未修改）
        AgentxMcpServer target = row(McpServerManager.TRANSPORT_STREAMABLE);
        target.setAuthType(McpServerManager.AUTH_BEARER);
        McpServerUpsertRequest req = new McpServerUpsertRequest(
                null, null, null, null, null, null, null, null, "sk-***9999", null, null, null);
        manager.mergeSecrets(target, existing, req);
        assertEquals("sk-real-secret-9999", target.getAuthToken(), "含 *** 回传 = 保持库中原值");

        // 回传 null（未修改）
        req = new McpServerUpsertRequest(
                null, null, null, null, null, null, null, null, null, null, null, null);
        target.setAuthToken("should-be-replaced");
        manager.mergeSecrets(target, existing, req);
        assertEquals("sk-real-secret-9999", target.getAuthToken());

        // 回传新明文（覆盖）
        req = new McpServerUpsertRequest(
                null, null, null, null, null, null, null, null, "sk-new", null, null, null);
        manager.mergeSecrets(target, existing, req);
        assertEquals("sk-new", target.getAuthToken());
    }

    @Test
    void mergeSecretsReplacesMaskedEnvValuesPerKey() {
        McpServerManager manager = manager();
        AgentxMcpServer existing = row(McpServerManager.TRANSPORT_STDIO);
        existing.setEnv("{\"API_KEY\":\"real-key-111\",\"LOG_LEVEL\":\"DEBUG\"}");

        AgentxMcpServer target = row(McpServerManager.TRANSPORT_STDIO);
        // UI 场景：LOG_LEVEL 明文可改，API_KEY 保持脱敏回显
        McpServerUpsertRequest req = new McpServerUpsertRequest(
                null, null, null, null, null, null,
                "{\"API_KEY\":\"***\",\"LOG_LEVEL\":\"INFO\"}",
                null, null, null, null, null);
        manager.mergeSecrets(target, existing, req);

        Map<String, String> merged = McpServerManager.parseEnv(target.getEnv());
        assertEquals("real-key-111", merged.get("API_KEY"), "掩码值回退库中原值");
        assertEquals("INFO", merged.get("LOG_LEVEL"), "明文值正常覆盖");
    }

    @Test
    void mergeSecretsOnCreateIgnoresMaskedGarbage() {
        McpServerManager manager = manager();
        AgentxMcpServer target = row(McpServerManager.TRANSPORT_STDIO);
        target.setEnv("{\"API_KEY\":\"***\"}");
        McpServerUpsertRequest req = new McpServerUpsertRequest(
                null, null, null, null, null, null, target.getEnv(), null, null, null, null, null);
        manager.mergeSecrets(target, null, req);
        assertEquals("***", McpServerManager.parseEnv(target.getEnv()).get("API_KEY"),
                "新建无原值可回退，掩码值原样落库（连接失败由 last_error 暴露）");
    }
}
