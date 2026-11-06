package com.llmetor.miniagentx.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话三表持久化测试（H2 MySQL 模式）：init → persistTerminal → loadHistory 衔接、双视图。
 */
class AgentxSessionStoreTest {

    private static DataSource h2() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:session_test_" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    @Test
    void initPersistAndLoadHistory() {
        AgentxSessionStore store = new AgentxSessionStore(h2());

        // 第一次调用：user + assistant(text)
        store.initConversation(1L, "conv_1", "sess_1", "u1", "第一问");
        List<Message> round1 = List.of(
                new UserMessage("第一问"),
                new AssistantMessage("第一答"));
        store.persistTerminal("conv_1", "sess_1", "completed", round1, round1, 0);

        // 第二次调用加载历史
        List<Message> history = store.loadHistory("conv_1");
        assertEquals(2, history.size());
        assertEquals("第一问", history.get(0).getText());
        assertEquals("第一答", history.get(1).getText());

        // 第二次终态落库：original 只追加新增切片；working 全量替换
        List<Message> round2Chain = List.of(
                new UserMessage("第一问"),
                new AssistantMessage("第一答"),
                new UserMessage("第二问"),
                new AssistantMessage("第二答"));
        store.persistTerminal("conv_1", "sess_2", "completed", round2Chain, round2Chain, 2);

        List<Message> history2 = store.loadHistory("conv_1");
        assertEquals(4, history2.size());
        assertEquals("第二答", history2.get(3).getText());
    }

    @Test
    void systemMessageNeverPersisted() {
        AgentxSessionStore store = new AgentxSessionStore(h2());
        store.initConversation(2L, "conv_2", "sess_3", null, "q");
        List<Message> chain = List.of(
                new org.springframework.ai.chat.messages.SystemMessage("sys"),
                new UserMessage("q"),
                new AssistantMessage("a"));
        store.persistTerminal("conv_2", "sess_3", "completed", chain, chain, 0);

        List<Message> history = store.loadHistory("conv_2");
        assertEquals(2, history.size());
        assertTrue(history.stream().noneMatch(m -> m instanceof org.springframework.ai.chat.messages.SystemMessage));
    }

    @Test
    void emptyConversationLoadsEmptyHistory() {
        AgentxSessionStore store = new AgentxSessionStore(h2());
        assertTrue(store.loadHistory("never_exists").isEmpty());
    }

    // ==================== M7：offload_context 归档 ====================

    @Test
    void offloadSaveAndLoadRoundTrip() {
        AgentxSessionStore store = new AgentxSessionStore(h2());
        store.initConversation(7L, "conv_off", "sess_off", "u1", "q");

        List<Message> original = List.of(
                new UserMessage("历史问题"),
                new AssistantMessage("历史回答含 42 条记录"));
        store.save("conv_off", "uuid-a", original);

        List<Message> loaded = store.load("conv_off", "uuid-a");
        assertEquals(2, loaded.size());
        assertEquals("历史问题", loaded.get(0).getText());
        assertEquals("历史回答含 42 条记录", loaded.get(1).getText());
    }

    @Test
    void offloadOverwriteSameUuidAndIsolation() {
        AgentxSessionStore store = new AgentxSessionStore(h2());
        store.initConversation(8L, "conv_off2", "sess_off2", "u1", "q");

        store.save("conv_off2", "uuid-b", List.of(new UserMessage("第一版")));
        store.save("conv_off2", "uuid-b", List.of(new UserMessage("第二版")));
        assertEquals("第二版", store.load("conv_off2", "uuid-b").get(0).getText());

        // 会话隔离：别的会话取不到
        org.junit.jupiter.api.Assertions.assertNull(store.load("conv_other", "uuid-b"));
        // 未知 uuid 取不到
        org.junit.jupiter.api.Assertions.assertNull(store.load("conv_off2", "uuid-none"));
    }

    @Test
    void offloadRowsDoNotPolluteHistoryLoading() {
        AgentxSessionStore store = new AgentxSessionStore(h2());
        store.initConversation(9L, "conv_off3", "sess_off3", "u1", "q");
        store.save("conv_off3", "uuid-c", List.of(new UserMessage("归档的原文不应出现在历史里")));

        List<Message> history = store.loadHistory("conv_off3");
        assertTrue(history.isEmpty(), "loadHistory 只读 working/original 两个状态键");
    }
}
