package com.llmetor.miniagentx.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Spring AI Message ⇄ OpenAI 风格 JSON。
 *
 * <p>行格式：
 * <pre>
 * {"role":"system","content":"..."}
 * {"role":"user","content":"..."}
 * {"role":"assistant","content":"...","tool_calls":[{"id","name","arguments"}],"reasoning_content":"..."}
 * {"role":"tool","tool_call_id":"..","name":"..","content":"result"}
 * </pre>
 *
 * 会话落库、快照、trace 全靠它。序列化失败不抛异常（退化为 user 行兜底）；
 * tool 消息序列化时展开为多行、反序列化时连续行合并回 ToolResponseMessage。
 */
public class MessageJsonSerializer {

    private static final Logger log = LoggerFactory.getLogger(MessageJsonSerializer.class);

    public static final String KEY_REASONING = "reasoningContent";

    private final ObjectMapper mapper = new ObjectMapper();

    /** 消息链 → JSON 数组字符串 */
    public String toJsonArray(List<Message> messages) {
        ArrayNode arr = mapper.createArrayNode();
        if (messages == null) {
            return arr.toString();
        }
        for (Message msg : messages) {
            try {
                if (msg instanceof SystemMessage sm) {
                    arr.add(row("system", sm.getText()));
                } else if (msg instanceof UserMessage um) {
                    arr.add(row("user", um.getText()));
                } else if (msg instanceof AssistantMessage am) {
                    ObjectNode node = row("assistant", am.getText());
                    Object reasoning = am.getMetadata() == null ? null : am.getMetadata().get(KEY_REASONING);
                    if (reasoning instanceof String rs && !rs.isBlank()) {
                        node.put("reasoning_content", rs);
                    }
                    if (am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
                        ArrayNode calls = node.putArray("tool_calls");
                        for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                            ObjectNode c = calls.addObject();
                            c.put("id", tc.id());
                            c.put("name", tc.name());
                            c.put("arguments", tc.arguments() == null ? "{}" : tc.arguments());
                        }
                    }
                    arr.add(node);
                } else if (msg instanceof ToolResponseMessage trm) {
                    for (ToolResponseMessage.ToolResponse resp : trm.getResponses()) {
                        ObjectNode node = arr.addObject();
                        node.put("role", "tool");
                        node.put("tool_call_id", resp.id());
                        node.put("name", resp.name());
                        node.put("content", resp.responseData() == null ? "" : resp.responseData());
                    }
                } else {
                    arr.add(row("user", safeText(msg)));
                }
            } catch (Exception e) {
                log.warn("消息序列化失败，退化为 user 行: type={}", msg.getMessageType(), e);
                arr.add(row("user", safeText(msg)));
            }
        }
        return arr.toString();
    }

    /** JSON 数组字符串 → 消息链（连续 tool 行合并；失败行跳过不抛异常） */
    public List<Message> fromJsonArray(String json) {
        List<Message> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        JsonNode arr;
        try {
            arr = mapper.readTree(json);
        } catch (Exception e) {
            log.warn("消息链 JSON 解析失败，返回空链", e);
            return result;
        }
        if (!arr.isArray()) {
            return result;
        }
        List<ToolResponseMessage.ToolResponse> toolRowBuffer = null;
        for (JsonNode node : arr) {
            String role = node.path("role").asText("");
            switch (role) {
                case "system" -> {
                    toolRowBuffer = flushToolRows(result, toolRowBuffer);
                    result.add(new SystemMessage(node.path("content").asText("")));
                }
                case "user" -> {
                    toolRowBuffer = flushToolRows(result, toolRowBuffer);
                    result.add(new UserMessage(node.path("content").asText("")));
                }
                case "assistant" -> {
                    toolRowBuffer = flushToolRows(result, toolRowBuffer);
                    String content = node.path("content").asText(null);
                    List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
                    if (node.has("tool_calls") && node.get("tool_calls").isArray()) {
                        for (JsonNode c : node.get("tool_calls")) {
                            toolCalls.add(new AssistantMessage.ToolCall(
                                    c.path("id").asText(""),
                                    "function",
                                    c.path("name").asText(""),
                                    c.path("arguments").asText("{}")));
                        }
                    }
                    String reasoning = node.path("reasoning_content").asText(null);
                    Map<String, Object> props = reasoning == null ? Map.of() : Map.of(KEY_REASONING, reasoning);
                    result.add(AssistantMessage.builder()
                            .content(content == null ? "" : content)
                            .properties(props)
                            .toolCalls(toolCalls)
                            .build());
                }
                case "tool" -> {
                    if (toolRowBuffer == null) {
                        toolRowBuffer = new ArrayList<>();
                    }
                    toolRowBuffer.add(new ToolResponseMessage.ToolResponse(
                            node.path("tool_call_id").asText(""),
                            node.path("name").asText(""),
                            node.path("content").asText("")));
                }
                default -> log.warn("未知 role 行，跳过: {}", role);
            }
        }
        flushToolRows(result, toolRowBuffer);
        return result;
    }

    private List<ToolResponseMessage.ToolResponse> flushToolRows(List<Message> target,
                                                                 List<ToolResponseMessage.ToolResponse> rows) {
        if (rows != null && !rows.isEmpty()) {
            target.add(ToolResponseMessage.builder().responses(new ArrayList<>(rows)).build());
        }
        return null;
    }

    private ObjectNode row(String role, String content) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", role);
        node.put("content", content == null ? "" : content);
        return node;
    }

    private String safeText(Message msg) {
        try {
            return msg.getText();
        } catch (Exception e) {
            return "";
        }
    }
}
