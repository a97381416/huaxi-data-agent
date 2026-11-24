package com.llmetor.miniagentx.utils;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 最终回答提取公共工具（监控中心自有表方案 §4.3）：从消息链倒序找第一个
 * "实质性" AssistantMessage（≥100 字符或含 markdown 结构 #/|/http，跳过
 * "好的/明白了"类收尾短回复），找不到回退最后一个非空回答。
 *
 * <p>原 SessionService 私有逻辑下沉于此，聊天回放与监控中心共用同一口径，
 * 保证两边展示的最终答案永远一致；换口径只改这一处。
 */
public final class AnswerExtractor {

    private AnswerExtractor() {
    }

    /**
     * 从消息链提取最终回答（倒序第一个实质性回答；空链返回空串）。
     * 消息链建议传 original 视图（含历史），倒序扫描天然优先命中本轮回答。
     */
    public static String extract(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        String lastNonBlank = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg instanceof AssistantMessage am) {
                String text = am.getText();
                if (text != null && !text.isBlank()) {
                    if (lastNonBlank.isEmpty()) {
                        lastNonBlank = text;
                    }
                    if (isSubstantial(text)) {
                        return text;
                    }
                }
            }
        }
        return lastNonBlank;
    }

    /** 实质性报告判定：够长，或带 markdown 结构（标题/表格/链接） */
    public static boolean isSubstantial(String text) {
        if (text.length() >= 100) {
            return true;
        }
        return text.contains("#") || text.contains("|") || text.contains("http");
    }
}
