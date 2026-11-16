package com.llmetor.dataagent.dataagent.action.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmetor.dataagent.dataagent.action.RestActionClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 审核侧 REST 聚合支撑（M-E3，BFF 在工具层，主后端零改动——申报侧同款 D1 策略）。
 *
 * <ul>
 *   <li>{@link #findMyReviewTask}：taskId → 任务定位。扫当前用户 todo/done 页（服务端按
 *       assignee 过滤，天然归属校验），businessId 兼作台账ID（doc 类任务）——**不让 LLM 传
 *       ledgerId**，防幻觉错库；非 doc 类任务 ledgerHeader=null（走通用 task-center 通道）。</li>
 *   <li>{@link #loadDictValues} + {@link #loadMyRoles}：角色门控数据源——
 *       task_pm_opinion_roles（可逐条填 PM 意见）/ task_coaudit_roles（特殊流程可终结）
 *       两个字典 + get-permission-info 的角色编码集，对齐前端 usePmOpinionGate/useCoauditGate。</li>
 *   <li>{@link #resolveRejectTarget}：驳回目标三选一（originator/parent/specified）→
 *       approve-chain 链上 nodeId 解析（链序：发起人在首位、当前节点在末尾，与前端 RejectDialog 同口径）。</li>
 * </ul>
 *
 * <p>REST 失败/状态不符抛 {@link TaskFlowException}（携带面向 LLM 的话术），工具层统一
 * catch 后原样返回。ID 一律按字符串处理（19 位雪花超 JS 安全整数）。
 */
@Component
public class ReviewSupport {

    /** 流程中断异常：消息直接面向 LLM/用户（❌ 前缀），工具层 catch 后原样返回 */
    public static class TaskFlowException extends RuntimeException {
        public TaskFlowException(String message) {
            super(message);
        }
    }

    /** 审核任务定位结果（todo/done-page 单条 + 台账头探测） */
    public record ReviewTaskRef(String taskId, String instanceId, String ledgerId, String nodeType,
                                String title, String category, String categoryLabel,
                                Integer approveStatus, boolean partialFlag, String priority,
                                String initiatorName, String createTime, boolean todo,
                                boolean docLedger, JsonNode ledgerHeader) {

        /** 特殊流程（紧急/特殊文件，需逐级协同审批）；非 doc 任务恒 false */
        public boolean isSpecial() {
            if (ledgerHeader == null) {
                return false;
            }
            JsonNode v = ledgerHeader.path("isSpecial");
            return v.isBoolean() ? v.asBoolean() : v.asInt(0) == 1;
        }
    }

    /** 审核角色门控（对齐前端 usePmOpinionGate / useCoauditGate，super_admin 全放行） */
    public record RoleGates(boolean pmOpinion, boolean canFinish,
                            Set<String> myRoles, Set<String> pmOpinionRoleCodes,
                            Set<String> coauditRoleCodes, String obtainedPmOpinionDefault) {
    }

    /** 驳回目标解析结果 */
    public record RejectTarget(String targetNodeId, String targetLabel) {
    }

    private final RestActionClient restActionClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReviewSupport(RestActionClient restActionClient) {
        this.restActionClient = restActionClient;
    }

    // ── 身份/ID 解析 ──────────────────────────────────────────────────────────

    /** userId 字符串 → Long；空/非法返回 null */
    public static Long parseUserId(String userId) {
        return parseLong(userId);
    }

    /** 通用 Long 解析：空/非法返回 null */
    public static Long parseLong(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** JsonNode → Integer：非数值（缺失/null）返回 null（asInt 只收原生 int 默认值） */
    public static Integer intOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.asInt() : null;
    }

    // ── 任务定位 ──────────────────────────────────────────────────────────────

    /** todo-page 优先、done-page 兜底扫描定位任务；不在名下返回 null（越权/不存在同口径） */
    public ReviewTaskRef findMyReviewTask(String taskId, String token, Long tenantId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        String id = taskId.trim();
        ReviewTaskRef ref = scanTaskPage(true, id, token, tenantId);
        if (ref == null) {
            ref = scanTaskPage(false, id, token, tenantId);
        }
        return ref;
    }

    private ReviewTaskRef scanTaskPage(boolean todo, String taskId, String token, Long tenantId) {
        RestActionClient.ActionResult r = restActionClient.get(
                "/task-center/task/" + (todo ? "todo-page" : "done-page"),
                query("pageNo", "1", "pageSize", "200"), token, tenantId);
        requireOk(r, "查询任务列表");
        for (JsonNode t : r.data().path("list")) {
            if (!taskId.equals(t.path("id").asText())) {
                continue;
            }
            String businessId = t.path("businessId").asText("");
            JsonNode header = loadLedgerHeader(businessId, token, tenantId);
            JsonNode approveStatus = t.path("approveStatus");
            return new ReviewTaskRef(taskId,
                    t.path("instanceId").asText(""),
                    businessId,
                    t.path("nodeType").asText(""),
                    t.path("title").asText(""),
                    t.path("category").asText(""),
                    t.path("categoryLabel").asText(""),
                    approveStatus.isNumber() ? approveStatus.asInt() : null,
                    t.path("partialFlag").asBoolean(false),
                    t.path("priority").asText(""),
                    t.path("initiatorName").asText(""),
                    t.path("createTime").asText(""),
                    todo,
                    header != null,
                    header);
        }
        return null;
    }

    /** 台账头探测：doc 类任务 businessId 即 ledgerId；查询失败视作非 doc 任务（返回 null 不中断） */
    private JsonNode loadLedgerHeader(String ledgerId, String token, Long tenantId) {
        if (ledgerId == null || ledgerId.isBlank()) {
            return null;
        }
        RestActionClient.ActionResult r = restActionClient.get(
                "/doc/ledger/get", Map.of("id", ledgerId), token, tenantId);
        return r.success() ? r.data() : null;
    }

    /** 写前守卫：任务必须在本人待办且为审批节点（nodeType=approval） */
    public void requireApprovalTodoTask(ReviewTaskRef task) {
        if (task == null) {
            throw new TaskFlowException("❌ 未在您的待办/已办中找到该任务（任务 ID 无效、已办结或不属于您）。请先用 listReviewTasks 确认任务 ID。");
        }
        if (!task.todo()) {
            throw new TaskFlowException("❌ 该任务已不在您的待办中（可能已办结或已被其他审核人处理）。可用 listReviewTasks(status=\"done\") 查看已办。");
        }
        if (!"approval".equals(task.nodeType())) {
            throw new TaskFlowException("❌ 该任务不是审核节点（当前节点类型：" + task.nodeType() + "），无法执行审核操作。");
        }
    }

    /** 台账必须处于审批中（status=1）才能审核；非 doc 任务跳过 */
    public void requireLedgerInReview(ReviewTaskRef task) {
        if (task.ledgerHeader() == null) {
            return;
        }
        int status = task.ledgerHeader().path("status").asInt(-1);
        if (status != 1) {
            throw new TaskFlowException("❌ 台账「" + task.ledgerHeader().path("docNumber").asText("")
                    + "」当前状态是「" + com.llmetor.dataagent.dataagent.action.task.DocLabels.ledgerStatus(status)
                    + "」，只有「审批中」状态才能审核（现状可能已被其他审核人改变，原计划作废）。");
        }
    }

    // ── 角色门控 ──────────────────────────────────────────────────────────────

    /**
     * 角色门控 + 已取得行 PM 意见默认文案（2 次 GET：permission-info + dict-data）。
     * 字典：task_pm_opinion_roles（PM 逐条意见）/ task_coaudit_roles（特殊流程可终结）；
     * doc_obtained_pm_opinion（value=obtained 行的 label）。
     */
    public RoleGates loadRoleGates(String token, Long tenantId) {
        Set<String> roles = loadMyRoles(token, tenantId);
        Map<String, Set<String>> dict = new LinkedHashMap<>();
        String obtainedDefault = null;
        RestActionClient.ActionResult r = restActionClient.get(
                "/system/dict-data/simple-list", null, token, tenantId);
        if (r.success() && r.data().isArray()) {
            for (JsonNode d : r.data()) {
                String type = d.path("dictType").asText("");
                String value = d.path("value").asText("");
                if (type.isEmpty() || value.isEmpty()) {
                    continue;
                }
                if ("task_pm_opinion_roles".equals(type) || "task_coaudit_roles".equals(type)) {
                    dict.computeIfAbsent(type, k -> new LinkedHashSet<>()).add(value);
                }
                if ("doc_obtained_pm_opinion".equals(type) && "obtained".equals(value)) {
                    String label = d.path("label").asText("");
                    if (!label.isBlank()) {
                        obtainedDefault = label;
                    }
                }
            }
        }
        if (obtainedDefault == null) {
            obtainedDefault = "资料已复核，通过";
        }
        boolean superAdmin = roles.contains("super_admin");
        Set<String> pmRoles = dict.getOrDefault("task_pm_opinion_roles", Set.of());
        Set<String> coRoles = dict.getOrDefault("task_coaudit_roles", Set.of());
        return new RoleGates(superAdmin || intersects(roles, pmRoles),
                superAdmin || intersects(roles, coRoles),
                roles, pmRoles, coRoles, obtainedDefault);
    }

    /** get-permission-info → 角色编码集合；查询失败返回空集（门控收紧，执行侧主后端兜底） */
    public Set<String> loadMyRoles(String token, Long tenantId) {
        Set<String> roles = new HashSet<>();
        RestActionClient.ActionResult r = restActionClient.get(
                "/system/auth/get-permission-info", Map.of(), token, tenantId);
        if (r.success() && r.data().path("roles").isArray()) {
            r.data().path("roles").forEach(n -> {
                String code = n.asText("");
                if (!code.isBlank()) {
                    roles.add(code);
                }
            });
        }
        return roles;
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        for (String s : a) {
            if (b.contains(s)) {
                return true;
            }
        }
        return false;
    }

    // ── 驳回目标解析（链序：发起人首位、当前节点末尾，与前端 RejectDialog 同口径）──

    /**
     * 三选一：originator=链首（发起人，默认）/ parent=倒数第二（上级审核节点）/
     * specified=按节点名匹配 targetNodeName（唯一命中才接受，歧义抛候选清单）。
     */
    public RejectTarget resolveRejectTarget(String target, String targetNodeName,
                                            JsonNode chain, String initiatorName) {
        if (chain == null || !chain.isArray() || chain.isEmpty()) {
            throw new TaskFlowException("❌ 未获取到审批链（approve-chain 为空），无法确定驳回目标。请稍后重试或前往主平台操作。");
        }
        int size = chain.size();
        String t = target == null || target.isBlank() ? "originator" : target.trim().toLowerCase(java.util.Locale.ROOT);
        if ("originator".equals(t)) {
            JsonNode first = chain.get(0);
            return new RejectTarget(first.path("nodeId").asText(""),
                    "发起人" + (initiatorName == null || initiatorName.isBlank() ? "" : "（" + initiatorName + "）")
                            + "节点「" + first.path("nodeName").asText("") + "」");
        }
        if ("parent".equals(t)) {
            if (size < 2) {
                throw new TaskFlowException("❌ 当前节点已是审批链首位，没有上级节点可驳回。可改用 target=originator 退回发起人。");
            }
            JsonNode parent = chain.get(size - 2);
            return new RejectTarget(parent.path("nodeId").asText(""),
                    "上级节点「" + parent.path("nodeName").asText("") + "」（审批人："
                            + parent.path("assigneeNames").asText("—") + "）");
        }
        if ("specified".equals(t)) {
            if (targetNodeName == null || targetNodeName.isBlank()) {
                throw new TaskFlowException(chainCandidates(chain, "❌ target=specified 需要提供 targetNodeName（要退回到的节点名）。审批链节点："));
            }
            String q = targetNodeName.trim();
            JsonNode current = chain.get(size - 1);
            List<JsonNode> hits = new ArrayList<>();
            for (JsonNode n : chain) {
                String name = n.path("nodeName").asText("");
                if (!name.isEmpty() && name.contains(q) && !n.path("nodeId").asText("").equals(current.path("nodeId").asText(""))) {
                    hits.add(n);
                }
            }
            if (hits.size() == 1) {
                JsonNode hit = hits.get(0);
                return new RejectTarget(hit.path("nodeId").asText(""),
                        "指定节点「" + hit.path("nodeName").asText("") + "」（审批人："
                                + hit.path("assigneeNames").asText("—") + "）");
            }
            throw new TaskFlowException(chainCandidates(chain, "❌ 驳回目标节点「" + q + "」"
                    + (hits.isEmpty() ? "未找到" : "命中多节点") + "。请让用户指认审批链节点："));
        }
        throw new TaskFlowException("❌ target 取值不合法（仅支持 originator/parent/specified）。");
    }

    /** 审批链候选清单（LLM 展示给用户挑） */
    public String chainCandidates(JsonNode chain, String lead) {
        StringBuilder sb = new StringBuilder(lead).append('\n');
        int size = chain.size();
        for (int i = 0; i < size; i++) {
            JsonNode n = chain.get(i);
            sb.append("- ").append(n.path("nodeName").asText(""))
                    .append("（审批人：").append(n.path("assigneeNames").asText("—"))
                    .append("，状态：").append(chainNodeStatus(n.path("status").asText("")));
            if (i == 0) {
                sb.append("；发起人节点");
            }
            if (i == size - 1) {
                sb.append("；当前节点");
            }
            sb.append("）\n");
        }
        return sb.toString();
    }

    // ── 标签 ─────────────────────────────────────────────────────────────────

    /** 审批链节点状态（hi_task_node 口径：running/completed/rejected/cancelled） */
    public static String chainNodeStatus(String v) {
        if (v == null || v.isBlank()) {
            return "未知";
        }
        return switch (v) {
            case "running" -> "待审";
            case "completed" -> "已通过";
            case "rejected" -> "已驳回";
            case "cancelled" -> "已取消";
            default -> v;
        };
    }

    /** 审批策略标签 */
    public static String strategyLabel(String v) {
        if (v == null || v.isBlank()) {
            return "会签";
        }
        return switch (v) {
            case "sign" -> "或签";
            case "countersign" -> "会签";
            case "sequential" -> "依次审批";
            default -> v;
        };
    }

    // ── REST 结果辅助 ─────────────────────────────────────────────────────────

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

    public ObjectMapper mapper() {
        return mapper;
    }
}
