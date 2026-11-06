package com.llmetor.miniagentx.agent;

import com.llmetor.miniagentx.memory.AgentxSessionStore;
import com.llmetor.miniagentx.memory.MessageJsonSerializer;
import com.llmetor.miniagentx.model.AgentStreamEvent;
import com.llmetor.miniagentx.model.RunnableParams;
import com.llmetor.miniagentx.model.ThinkingMode;
import com.llmetor.miniagentx.testsupport.ScriptedChatModel;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import reactor.core.publisher.Flux;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * original 真相链落库回归测试（M8 联调发现 M7 回归后补）：
 * 循环期 assistant/toolResponses 必须同步追加进 originalChain，
 * 终态落库后 original_messages = 完整过程（user → assistant(toolCalls) → tool → assistant(final)），
 * 而不是只剩 user 原话（回归症状：会话回放 answer 为空、评测 runner 取不到 pred）。
 */
class AgentLoopOriginalChainTest {

    static class EchoTools {
        @Tool(name = "echo", description = "原样返回输入文本")
        public String echo(String text) {
            return "echo: " + text;
        }
    }

    private static DataSource h2() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:original_chain_" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    private static List<AgentStreamEvent> run(ReactAgent agent, String convId, String query) {
        List<AgentStreamEvent> events = new CopyOnWriteArrayList<>();
        agent.stream(query, RunnableParams.of(convId))
                .doOnNext(events::add)
                .blockLast();
        return events;
    }

    @Test
    void originalChain含完整过程不止user原话() {
        DataSource ds = h2();
        AgentxSessionStore store = new AgentxSessionStore(ds);
        ScriptedChatModel model = new ScriptedChatModel();
        // 第 1 轮工具调用，第 2 轮最终文本
        model.enqueue(Flux.just(ScriptedChatModel.toolCallChunk("c1", "echo", "{\"text\":\"hello\"}")));
        model.enqueue(Flux.just(ScriptedChatModel.textChunk("工具说：hello，问题已解决")));

        ReactAgent agent = ReactAgent.builder()
                .chatModel(model)
                .instructions("你是测试助手")
                .tools(Arrays.asList(ToolCallbacks.from(new EchoTools())))
                .thinkingMode(ThinkingMode.THINK_TAG)
                .retryIntervalMs(50)
                .dataSource(ds)
                .sessionStore(store)
                .enableTrace(false)
                .build();
        run(agent, "conv_orig_1", "调用 echo 再总结");

        // loadState 是内部方法，测试直接查表（original_messages 状态键）
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
        List<String> rows = jdbc.queryForList(
                "SELECT state_data FROM agentx_session WHERE conversation_id = ? AND state_key = ? ORDER BY item_index",
                String.class, "conv_orig_1", AgentxSessionStore.STATE_ORIGINAL);
        MessageJsonSerializer serializer = new MessageJsonSerializer();
        List<Message> original = rows.stream()
                .flatMap(r -> serializer.fromJsonArray(r).stream())
                .toList();
        assertEquals(4, original.size(), "original 应为 user → assistant(toolCalls) → tool → assistant(final) 四条");
        assertTrue(original.get(0) instanceof UserMessage);
        AssistantMessage toolRound = (AssistantMessage) original.get(1);
        assertTrue(toolRound.getToolCalls() != null && !toolRound.getToolCalls().isEmpty(),
                "第 2 条应为带 toolCalls 的 assistant");
        assertTrue(original.get(2) instanceof ToolResponseMessage, "第 3 条应为工具结果");
        AssistantMessage finalMsg = (AssistantMessage) original.get(3);
        assertTrue(finalMsg.getText().contains("hello"), "第 4 条应为最终文本回答");
    }
}
