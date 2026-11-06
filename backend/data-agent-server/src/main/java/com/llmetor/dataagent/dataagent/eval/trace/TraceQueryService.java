package com.llmetor.dataagent.dataagent.eval.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmetor.miniagentx.memory.AgentxSessionStore;
import com.llmetor.miniagentx.memory.MessageJsonSerializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * trace 查询服务：诊断 Agent 三个查询工具的底层实现（M8，复刻 dodo-agentx TraceQueryService）。
 *
 * <p>设计原则：模拟人类排查问题的操作，<b>分层给原文</b>——通读只给截断预览（省模型思考），
 * 深查才给某轮完整入参出参，反查只给关键词命中片段。服务端只取原文，不做解析/拼接/摘要。
 *
 * <p>数据源三张表：agentx_conversation（调用边界）/ agentx_session（original_messages 消息链，
 * 诊断读真相不用压缩后的 working）/ agentx_trace（每轮 LLM 调用入参出参）。
 */
@Slf4j
public class TraceQueryService {

    private static final String STATE_KEY_ORIGINAL = AgentxSessionStore.STATE_ORIGINAL;
    /** 消息段落角色：模型思考（segments 里使用，首现命中时按它给提示） */
    private static final String ROLE_REASONING = "assistant 思考";
    /** 消息预览单行截断长度 */
    private static final int PREVIEW_MAX_CHARS = 200;
    /** 首现命中时返回的关键词前后上下文长度 */
    private static final int SNIPPET_CONTEXT_CHARS = 120;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final MessageJsonSerializer serializer = new MessageJsonSerializer();

    public TraceQueryService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 轮次摘要行（roundAnomalies 输入） */
    public record TraceRoundSummary(String sessionId, int round, Integer success,
                                    String errorMessage, Long inputChars) {
    }

    /**
     * 工具一：通读全程（按 conversation_id）。
     * 返回窗口内每次对话的概要、轮次异常概要（失败轮及报错、上下文压缩点，正常轮不列）、
     * 以及消息记录的截断预览（按 item_index 顺序，assistant 行标注 round；省略模型思考）。
     * 完整原文按 session+round 用 traceRound 深查。
     */
    public String overview(String conversationId) {
        List<Map<String, Object>> convs = jdbc.queryForList(
                "SELECT session_id, user_id, question, status FROM agentx_conversation " +
                        "WHERE conversation_id = ? ORDER BY created_at ASC, id ASC", conversationId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT session_id, item_index, state_data FROM agentx_session " +
                        "WHERE conversation_id = ? AND state_key = ? ORDER BY item_index ASC",
                conversationId, STATE_KEY_ORIGINAL);
        if (convs.isEmpty() && rows.isEmpty()) {
            return "未找到 conversation_id=" + conversationId
                    + " 的任何记录（会话不存在或已被删除）。请核对 conversation_id。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# 执行过程全貌 conversation_id=").append(conversationId).append('\n');

        sb.append("\n## 窗口内对话（每次提问一行）\n");
        for (Map<String, Object> c : convs) {
            sb.append("- session ").append(c.get("session_id"))
                    .append(" | 用户 ").append(c.get("user_id"))
                    .append(" | 状态 ").append(c.get("status"))
                    .append(" | 问题：").append(oneLine(str(c.get("question")))).append('\n');
        }

        sb.append("\n## 轮次概览（只列失败轮与上下文压缩点，未列出的轮次均成功）\n");
        List<TraceRoundSummary> rounds = jdbc.query(
                "SELECT session_id, round, success, error_message, LENGTH(input_data) AS input_chars " +
                        "FROM agentx_trace WHERE conversation_id = ? ORDER BY session_id, round",
                (rs, i) -> new TraceRoundSummary(rs.getString("session_id"), rs.getInt("round"),
                        (Integer) rs.getObject("success"), rs.getString("error_message"),
                        (Long) rs.getObject("input_chars")),
                conversationId);
        Map<String, List<TraceRoundSummary>> bySession = new LinkedHashMap<>();
        for (TraceRoundSummary r : rounds) {
            bySession.computeIfAbsent(r.sessionId(), k -> new ArrayList<>()).add(r);
        }
        if (bySession.isEmpty()) {
            sb.append("（无 trace 记录）\n");
        }
        for (Map.Entry<String, List<TraceRoundSummary>> e : bySession.entrySet()) {
            String anomalies = roundAnomalies(e.getValue());
            sb.append("session ").append(e.getKey()).append(" 共 ").append(e.getValue().size()).append(" 轮：")
                    .append(anomalies.isEmpty() ? "全部成功，无上下文压缩\n" : "\n").append(anomalies);
        }

        sb.append("\n## 消息记录预览（正文截断、模型思考省略，assistant 行标注轮次；")
                .append("完整原文按 session+round 用 traceRound 深查）\n");
        String lastSession = null;
        List<Integer> okRounds = List.of();
        int[] assistantSeq = new int[1];
        for (Map<String, Object> row : rows) {
            String sid = str(row.get("session_id"));
            if (!sid.equals(lastSession)) {  // session 边界：换成功轮表，轮次计数归零
                lastSession = sid;
                okRounds = new ArrayList<>();
                for (TraceRoundSummary r : bySession.getOrDefault(sid, List.of())) {
                    if (isSuccess(r)) {
                        okRounds.add(r.round());
                    }
                }
                assistantSeq[0] = 0;
                sb.append("\n【session ").append(sid).append("】\n");
            }
            sb.append(previewMessages(str(row.get("state_data")),
                    ((Number) row.get("item_index")).intValue(), okRounds, assistantSeq));
        }
        return sb.toString();
    }

    /**
     * 消息预览：每条消息一行（行首标 item 序号），只渲染 content 截断行与
     * tool_call/tool_result 截断行，省略模型思考。assistant 行标注对应轮次：
     * 第 N 个 assistant 消息取成功轮列表里的第 N 个 round（一轮 LLM 调用恰好产生一条 assistant 消息）。
     */
    private String previewMessages(String stateData, int itemIndex,
                                   List<Integer> successRounds, int[] assistantSeq) {
        List<Message> messages;
        try {
            messages = serializer.fromJsonArray(stateData);
        } catch (Exception e) {
            return "item " + itemIndex + " | " + oneLine(stateData) + '\n';  // 解析失败退化为单行截断，不中断通读
        }
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            if (msg instanceof AssistantMessage am) {
                int seq = assistantSeq[0]++;
                String roundTag = seq < successRounds.size()
                        ? "（round " + successRounds.get(seq) + "）" : "";
                String text = am.getText();
                List<AssistantMessage.ToolCall> calls = am.getToolCalls();
                // 标签行始终输出（纯工具调用轮正文为空，也要能看到轮次），正文有才追加
                sb.append("item ").append(itemIndex).append(" | assistant").append(roundTag).append("：");
                if (text != null && !text.isBlank()) {
                    sb.append(oneLine(text));
                }
                sb.append('\n');
                if (calls != null) {
                    for (AssistantMessage.ToolCall tc : calls) {
                        sb.append("        调用 ").append(tc.name())
                                .append("(").append(oneLine(tc.arguments())).append(")\n");
                    }
                }
            } else if (msg instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                    sb.append("item ").append(itemIndex).append(" |   结果 ").append(tr.name()).append("：")
                            .append(oneLine(String.valueOf(tr.responseData()))).append('\n');
                }
            } else if (msg.getText() != null && !msg.getText().isBlank()) {
                sb.append("item ").append(itemIndex).append(" | ")
                        .append(String.valueOf(msg.getMessageType()).toLowerCase())
                        .append("：").append(oneLine(msg.getText())).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 工具二：查一轮（按 session_id + round 查 agentx_trace）。
     * 返回该轮 input_data/output_data 原文。input_data 默认省略 tools 工具定义
     * （本内核 input_data 只存消息序列、天然无 tools 字段，此渲染逻辑保留作契约兼容），
     * includeTools=true 才附带。失败轮的详情只有这里能看到。
     */
    public String roundDetail(String sessionId, Integer round, Boolean includeTools) {
        if (sessionId == null || sessionId.isBlank() || round == null || round < 1) {
            return "参数错误：sessionId 必须是会话概览里的 session 编号，round 从 1 开始。";
        }
        List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT duration_ms, success, error_message, input_data, output_data " +
                        "FROM agentx_trace WHERE session_id = ? AND round = ? LIMIT 1",
                sessionId, round);
        if (list.isEmpty()) {
            return "未找到 session_id=" + sessionId + " round=" + round
                    + " 的 trace 记录。请核对轮次概览里的 session 编号与轮次。";
        }
        Map<String, Object> t = list.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("# 轮次详情 session_id=").append(sessionId).append(" round=").append(round).append('\n');
        sb.append("耗时 ").append(t.get("duration_ms")).append("ms");
        Integer success = (Integer) t.get("success");
        if (success != null && success == 0) {
            sb.append(" | 失败 | 错误：").append(oneLine(str(t.get("error_message"))));
        } else {
            sb.append(" | 成功");
        }
        sb.append("\n\n## input_data（本轮发给模型的完整请求，tools 定义默认省略）\n")
                .append(renderInputData(str(t.get("input_data")), Boolean.TRUE.equals(includeTools)))
                .append("\n\n## output_data（模型本轮输出原文）\n")
                .append(str(t.get("output_data")))
                .append('\n');
        return sb.toString();
    }

    /**
     * 渲染 input_data：messages 与顶层参数始终保留，tools 工具定义默认省略并附提示行，
     * includeTools=true 时单独附在末尾。解析失败退回原文。
     */
    private static String renderInputData(String inputData, boolean includeTools) {
        try {
            JsonNode root = JSON.readTree(inputData);
            ObjectNode out = (ObjectNode) root;
            JsonNode tools = out.remove("tools");
            String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out);
            if (tools == null) {
                return json;
            }
            return includeTools
                    ? json + "\n\n## tools（工具定义）\n"
                            + JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tools)
                    : json + "\n（tools 工具定义已省略：各轮相同且篇幅大；需核对工具 schema 时传 includeTools=true 重查）";
        } catch (Exception e) {
            return inputData;
        }
    }

    /**
     * 工具三：查首现（按 conversation_id + keyword 扫 original_messages）。
     * 逐条消息按 item_index 顺序扫四类段落（模型思考/正文/工具调用参数/工具结果），命中即停，
     * 返回第一次出现的位置（item_index、session、段落角色）和前后各约 120 字符的原文片段。
     */
    public String firstAppear(String conversationId, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "参数错误：keyword 不能为空。";
        }
        String kw = keyword.trim();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT session_id, item_index, state_data FROM agentx_session " +
                        "WHERE conversation_id = ? AND state_key = ? ORDER BY item_index ASC",
                conversationId, STATE_KEY_ORIGINAL);
        for (Map<String, Object> row : rows) {
            String stateData = str(row.get("state_data"));
            if (stateData == null || stateData.isBlank()) {
                continue;
            }
            List<Message> messages;
            try {
                messages = serializer.fromJsonArray(stateData);
            } catch (Exception e) {
                log.warn("[trace-diag] 首现检索跳过解析失败的行 item={} err={}",
                        row.get("item_index"), e.getMessage());
                continue;
            }
            for (Message msg : messages) {
                for (Segment seg : segments(msg)) {
                    int idx = seg.text().indexOf(kw);
                    if (idx >= 0) {
                        String hit = "关键词「" + kw + "」首次出现在 item_index=" + row.get("item_index")
                                + " | session " + row.get("session_id")
                                + " | " + seg.role()
                                + "\n原文片段（首现位置前后）：\n" + snippetAround(seg.text(), idx, kw.length());
                        if (ROLE_REASONING.equals(seg.role())) {
                            hit += "\n（提示：命中模型思考，说明该措辞是模型推导出的；"
                                    + "若要定位数据源头，可换工具返回的原始特征值如字段名、原始数值、人名再查）";
                        }
                        return hit;
                    }
                }
            }
        }
        return "关键词「" + kw + "」在 conversation_id=" + conversationId
                + " 的消息记录中未出现。可换一个更短或更关键的特征值再试。";
    }

    // ── 消息可检索段落：正文 / 思考 / 工具调用 / 工具结果 ──

    private record Segment(String role, String text) {
    }

    private static List<Segment> segments(Message msg) {
        List<Segment> out = new ArrayList<>();
        if (msg instanceof AssistantMessage am) {
            String reasoning = extractReasoning(am);
            if (reasoning != null && !reasoning.isBlank()) {
                out.add(new Segment(ROLE_REASONING, reasoning));
            }
            String text = am.getText();
            if (text != null && !text.isBlank()) {
                out.add(new Segment("assistant 正文", text));
            }
            List<AssistantMessage.ToolCall> toolCalls = am.getToolCalls();
            if (toolCalls != null) {
                for (AssistantMessage.ToolCall tc : toolCalls) {
                    out.add(new Segment("assistant 工具调用 " + tc.name(), tc.arguments()));
                }
            }
        } else if (msg instanceof ToolResponseMessage trm) {
            for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                out.add(new Segment("工具结果 " + tr.name(), String.valueOf(tr.responseData())));
            }
        } else if (msg.getText() != null && !msg.getText().isBlank()) {
            out.add(new Segment(String.valueOf(msg.getMessageType()).toLowerCase(), msg.getText()));
        }
        return out;
    }

    /** 从 AssistantMessage metadata 提取模型思考（兼容两种 key）。 */
    private static String extractReasoning(AssistantMessage am) {
        Map<String, Object> metadata = am.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object rc = metadata.get(MessageJsonSerializer.KEY_REASONING);
        if (rc == null) {
            rc = metadata.get("reasoning_content");
        }
        return rc instanceof String s ? s : null;
    }

    // ── 小工具 ──

    /**
     * 异常轮清单：失败轮（附报错）与压缩点（input 字符量低于上一轮 = 之前发生过上下文压缩）。
     * 正常轮不列，通读时看消息预览即可。
     */
    private static String roundAnomalies(List<TraceRoundSummary> rounds) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rounds.size(); i++) {
            TraceRoundSummary r = rounds.get(i);
            if (!isSuccess(r)) {
                sb.append("- round ").append(r.round()).append(" | 失败");
                if (r.errorMessage() != null && !r.errorMessage().isBlank()) {
                    sb.append(" | 错误：").append(oneLine(r.errorMessage()));
                }
                sb.append('\n');
            } else if (i > 0 && isCharDrop(rounds.get(i - 1), r)) {
                sb.append("- round ").append(r.round()).append(" | input 字符量 ")
                        .append(rounds.get(i - 1).inputChars()).append("→").append(r.inputChars())
                        .append(" 骤降，之前发生过上下文压缩\n");
            }
        }
        return sb.toString();
    }

    private static boolean isCharDrop(TraceRoundSummary prev, TraceRoundSummary cur) {
        return prev.inputChars() != null && cur.inputChars() != null
                && cur.inputChars() < prev.inputChars();
    }

    private static boolean isSuccess(TraceRoundSummary r) {
        return r.success() != null && r.success() == 1;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replace("\r", " ").replace("\n", " ").trim();
        return s.length() > PREVIEW_MAX_CHARS ? s.substring(0, PREVIEW_MAX_CHARS) + "..." : s;
    }

    private static String snippetAround(String text, int idx, int kwLen) {
        int start = Math.max(0, idx - SNIPPET_CONTEXT_CHARS);
        int end = Math.min(text.length(), idx + kwLen + SNIPPET_CONTEXT_CHARS);
        StringBuilder sb = new StringBuilder();
        if (start > 0) {
            sb.append("...");
        }
        sb.append(text, start, end);
        if (end < text.length()) {
            sb.append("...");
        }
        return sb.toString().replace("\r", " ").replace("\n", " ");
    }
}
