package com.llmetor.dataagent.dataagent.action.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmetor.dataagent.dataagent.action.RestActionClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * 任务/台账 REST 聚合支撑（M-E1，BFF 在工具层，主后端零改动——计划 §3.5 D1）。
 *
 * <ul>
 *   <li>{@link #findMyTask}：taskId → 任务定位。扫当前用户 todo/done 页（服务端按
 *       assignee 过滤，天然归属校验：不在名下＝越权/不存在），拿到 ledgerId(businessId)
 *       与 instanceId——**不让 LLM 传 ledgerId**，防幻觉错库。</li>
 *   <li>{@link #loadLedgerView}：台账详情 + STAGE 暂存草稿合并视图；status∈{3,4} 守卫；
 *       基准行 = 有暂存用暂存行（最新意图），否则主表行；锁定行 = 主表 auditStatus=2。</li>
 *   <li>卡片尾巴：工具返回文本末尾追加 ```json {"__card":...}```——LLM 与前端同一数据源
 *       （前端 TOOL_END 拦截提取渲染确认卡/上传槽位卡）。</li>
 * </ul>
 *
 * <p>REST 失败/状态不符抛 {@link TaskFlowException}（携带面向 LLM 的话术），工具层统一
 * catch 后原样返回。ID 一律按字符串处理（19 位雪花超 JS 安全整数，主后端序列化为字符串）。
 */
@Component
public class TaskLedgerSupport {

    /** 流程中断异常：消息直接面向 LLM/用户（❌ 前缀），工具层 catch 后原样返回 */
    public static class TaskFlowException extends RuntimeException {
        public TaskFlowException(String message) {
            super(message);
        }
    }

    /** 任务定位结果（来自 todo/done-page 的单条任务） */
    public record TaskRef(String taskId, String instanceId, String ledgerId, String nodeType,
                          String title, String categoryLabel, Integer approveStatus, boolean partialFlag,
                          boolean todo, String createTime) {
    }

    /** 台账合并视图：主表行（审核语义）+ STAGE 暂存行（最新草稿意图）+ 锁定行 */
    public record LedgerView(JsonNode header, List<ObjectNode> liveRows, List<ObjectNode> stageRows,
                             Set<String> lockedIds, Map<String, String> nodeNames) {

        public String docNumber() {
            return header.path("docNumber").asText("");
        }

        public String projectName() {
            return header.path("projectName").asText("");
        }

        public String stageName() {
            return header.path("stageName").asText("");
        }

        public String phaseName() {
            return header.path("phaseName").asText("");
        }

        public int status() {
            return header.path("status").asInt(-1);
        }

        public String currentInstanceId() {
            return header.path("currentInstanceId").asText("");
        }

        public boolean hasStage() {
            return !stageRows.isEmpty();
        }

        /** 修改基准行：有暂存草稿以暂存为准（最新意图），否则主表行 */
        public List<ObjectNode> baseRows() {
            return hasStage() ? stageRows : liveRows;
        }

        /** 锁定行（主表已通过行）：基准=暂存行时不在基准内，组装全量时需原样追加 */
        public List<ObjectNode> lockedRows() {
            List<ObjectNode> out = new ArrayList<>();
            for (ObjectNode row : liveRows) {
                if (lockedIds.contains(row.path("id").asText(""))) {
                    out.add(row);
                }
            }
            return out;
        }
    }

    private final RestActionClient restActionClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public TaskLedgerSupport(RestActionClient restActionClient) {
        this.restActionClient = restActionClient;
    }

    // ── 任务定位 ──────────────────────────────────────────────────────────────

    /** todo-page 优先、done-page 兜底扫描定位任务；不在名下返回 null（越权/不存在同口径） */
    public TaskRef findMyTask(String taskId, String token, Long tenantId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        String id = taskId.trim();
        TaskRef ref = scanTaskPage(true, id, token, tenantId);
        if (ref == null) {
            ref = scanTaskPage(false, id, token, tenantId);
        }
        return ref;
    }

    private TaskRef scanTaskPage(boolean todo, String taskId, String token, Long tenantId) {
        RestActionClient.ActionResult r = restActionClient.get(
                "/task-center/task/" + (todo ? "todo-page" : "done-page"),
                query("pageNo", "1", "pageSize", "200"), token, tenantId);
        requireOk(r, "查询任务列表");
        for (JsonNode t : r.data().path("list")) {
            if (!taskId.equals(t.path("id").asText())) {
                continue;
            }
            JsonNode approveStatus = t.path("approveStatus");
            return new TaskRef(taskId,
                    t.path("instanceId").asText(""),
                    t.path("businessId").asText(""),
                    t.path("nodeType").asText(""),
                    t.path("title").asText(""),
                    t.path("categoryLabel").asText(""),
                    approveStatus.isNumber() ? approveStatus.asInt() : null,
                    t.path("partialFlag").asBoolean(false),
                    todo,
                    t.path("createTime").asText(""));
        }
        return null;
    }

    // ── 台账视图 ──────────────────────────────────────────────────────────────

    /** 台账详情 + 暂存草稿合并视图（两次 GET） */
    public LedgerView loadLedgerView(String ledgerId, String token, Long tenantId) {
        RestActionClient.ActionResult r = restActionClient.get(
                "/doc/ledger/get", Map.of("id", ledgerId), token, tenantId);
        requireOk(r, "查询台账详情");

        JsonNode header = r.data();
        List<ObjectNode> live = new ArrayList<>();
        Map<String, String> nodeNames = new LinkedHashMap<>();
        for (JsonNode group : header.path("nodeGroups")) {
            String nodeId = group.path("nodeId").asText("");
            String nodeName = group.path("nodeName").asText("");
            String nodeCode = group.path("nodeCode").asText("");
            if (!nodeId.isEmpty() && !nodeName.isEmpty()) {
                nodeNames.put(nodeId, nodeName);
            } else if (!nodeId.isEmpty() && !nodeCode.isEmpty()) {
                nodeNames.put(nodeId, nodeCode);
            }
            for (JsonNode entry : group.path("fileEntries")) {
                if (entry instanceof ObjectNode o) {
                    live.add(o.deepCopy());
                }
            }
        }

        Set<String> locked = new HashSet<>();
        for (ObjectNode row : live) {
            if (row.path("auditStatus").asInt(-1) == 2) {
                locked.add(row.path("id").asText(""));
            }
        }

        List<ObjectNode> stage = new ArrayList<>();
        RestActionClient.ActionResult sr = restActionClient.get(
                "/doc/ledger/get-stage-draft", Map.of("ledgerId", ledgerId), token, tenantId);
        requireOk(sr, "查询暂存草稿");
        if (sr.data().isArray()) {
            for (JsonNode entry : sr.data()) {
                if (entry instanceof ObjectNode o) {
                    stage.add(o.deepCopy());
                }
            }
        }
        return new LedgerView(header, live, stage, locked, nodeNames);
    }

    /** 写前守卫：只有已驳回(3)/部分通过(4)状态才能暂存/重提 */
    public void requireRejected(LedgerView view) {
        int st = view.status();
        if (st != 3 && st != 4) {
            throw new TaskFlowException("❌ 台账 " + view.docNumber() + " 当前状态是「"
                    + DocLabels.ledgerStatus(st) + "」，只有「已驳回/部分通过」状态才能修改或重新提交。");
        }
    }

    /** 写前守卫：任务必须在本人待办且为申报人填报节点（驳回后 reactivated 的任务） */
    public void requireSubmitterTodoTask(TaskRef task) {
        if (task == null) {
            throw new TaskFlowException("❌ 未在您的待办/已办中找到该任务（任务 ID 无效、已办结或不属于您），无法执行该操作。请先用 listMyTasks 确认任务 ID。");
        }
        if (!task.todo()) {
            throw new TaskFlowException("❌ 该任务已不在您的待办中（可能已办结或被撤回），无需重复处理。可用 listMyTasks(status=\"done\") 查看已办。");
        }
        if (!"submitter".equals(task.nodeType())) {
            throw new TaskFlowException("❌ 该任务不是申报人填报节点（当前节点类型：" + task.nodeType() + "），申报人无法执行该操作。");
        }
    }

    // ── REST 结果与卡片辅助 ───────────────────────────────────────────────────

    /** 有序 query 构造（固定参数顺序，测试可精确断言 URI） */
    static Map<String, String> query(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    /** REST 失败 → 中断流程（话术透传给 LLM） */
    public void requireOk(RestActionClient.ActionResult r, String context) {
        if (!r.success()) {
            throw new TaskFlowException("❌ " + context + "失败：" + r.msg());
        }
    }

    /** 工具返回文本末尾追加机器可读卡片（前端 TOOL_END 拦截提取渲染） */
    public void appendCard(StringBuilder sb, Object card) {
        try {
            sb.append("\n```json\n").append(mapper.writeValueAsString(card)).append("\n```");
        } catch (Exception e) {
            // 卡片失败不影响 LLM 文本路径（前端无卡时用户仍可手打确认）
        }
    }

    /** 组装 JSON 请求体片段（Map → JSON 字符串复用值） */
    public String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
