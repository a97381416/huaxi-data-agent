package com.llmetor.dataagent.agentdemo;

import com.llmetor.miniagentx.agent.AgentTaskManager;
import com.llmetor.miniagentx.agent.ReactAgent;
import com.llmetor.miniagentx.model.ThinkingMode;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Arrays;

/**
 * M1 内核演示装配：MiniMax M3.1-Flash + echo/boom 两个演示工具。
 *
 * <p>M2 交付 DodoAgent（真实领域工具集）后本配置保留作为内核联调入口。
 */
@Configuration
public class KernelDemoConfig {

    @Bean
    public AgentTaskManager agentTaskManager() {
        return new AgentTaskManager();
    }

    @Bean
    public ReactAgent demoAgent(ChatModel chatModel,
                                DataSource dataSource,
                                AgentTaskManager taskManager,
                                @Value("${demo.agent.max-rounds:20}") int maxRounds,
                                @Value("${demo.agent.retry-interval-ms:3000}") long retryIntervalMs) {
        return ReactAgent.builder()
                .chatModel(chatModel)
                .instructions("""
                        你是 mini-agentx 内核的演示助手。你可以：
                        1. 调用 echo 工具原样返回任意文本（当用户要求"回声/复读/echo"时必须使用）；
                        2. 调用 boom 工具演示失败回灌（当用户要求"测试失败/boom"时必须使用）。
                        其他问题直接回答。回答保持简洁。""")
                .tools(Arrays.asList(ToolCallbacks.from(new DemoTools())))
                .thinkingMode(ThinkingMode.REASONING_CONTENT)
                .maxRounds(maxRounds)
                .maxRetries(3)
                .retryIntervalMs(retryIntervalMs)
                .taskManager(taskManager)
                .dataSource(dataSource)
                .enableTrace(true)
                .build();
    }

    /**
     * M1 演示工具：echo（成功路径）+ boom（必失败，验证错误 JSON 回灌不终止循环）。
     */
    static class DemoTools {
        @org.springframework.ai.tool.annotation.Tool(description = "原样返回输入的文本（回声/复读）")
        public String echo(String text) {
            return "echo: " + text;
        }

        @org.springframework.ai.tool.annotation.Tool(description = "测试用：必然执行失败的工具")
        public String boom() {
            throw new RuntimeException("boom 工具必然失败（预期内）");
        }
    }
}
