package com.llmetor.dataagent.dataagent.action.fill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmetor.dataagent.dataagent.action.RestActionClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 填报上下文定位与聚合支撑（M-E2，BFF 在工具层，主后端零改动——M-E1 同款 D1 策略）。
 *
 * <ul>
 *   <li>{@link #findProject}：项目关键词（ID/名称/编码）→ my-projects 精确/唯一命中定位，
 *       不让 LLM 猜 projectId（19 位雪花防幻觉错库）。</li>
 *   <li>{@link #resolvePhase}：阶段关键词（ID/编码/名称）→ resolve-phase + 阶段树扫描，
 *       命中大阶段（父）时返回其子阶段候选清单让用户挑。</li>
 *   <li>{@link #loadNodeConfigs}：get-nodes 节点+预设类别；{@link #seedRowsFromNodes}
 *       生成预设种子行（对齐业务前端 edit.vue seed：summary 默认值/涉密默认/待上传）。</li>
 *   <li>{@link #findDraftLedgerId} + {@link #loadLedgerDetail}：同项目同子阶段草稿唯一检测
 *       与台账合并视图（nodeGroups 平铺行 + nodeId→节点名）。</li>
 * </ul>
 *
 * <p>REST 失败/状态不符抛 {@link TaskFlowException}（携带面向 LLM 的话术），工具层统一
 * catch 后原样返回。ID 一律按字符串处理（19 位雪花超 JS 安全整数）。
 */
@Component
public class FillContextSupport {

    /** 流程中断异常：消息直接面向 LLM/用户（❌ 前缀），工具层 catch 后原样返回 */
    public static class TaskFlowException extends RuntimeException {
        public TaskFlowException(String message) {
            super(message);
        }
    }

    /** 项目定位结果（my-projects 单条 + 阶段统计） */
    public record ProjectRef(String projectId, String projectName, String projectCode,
                             String type, Boolean hasWarning, JsonNode raw) {
    }

    /** 子阶段定位结果 */
    public record PhaseTarget(String projectId, String phaseId, String phaseCode, String phaseName,
                              String majorPhaseName) {
    }

    /** 台账视图：头（docNumber/status/isSpecial...）+ 平铺行 + nodeId→节点名 */
    public record LedgerView(JsonNode header, List<ObjectNode> rows, Map<String, String> nodeNames) {

        public String ledgerId() {
            return header.path("id").asText("");
        }

        public String docNumber() {
            return header.path("docNumber").asText("");
        }

        public String projectName() {
            return header.path("projectName").asText("");
        }

        public String phaseName() {
            return header.path("phaseName").asText("");
        }

        public int status() {
            return header.path("status").asInt(-1);
        }

        public boolean isSpecial() {
            JsonNode v = header.path("isSpecial");
            return v.isBoolean() ? v.asBoolean() : v.asInt(0) == 1;
        }
    }

    /** 子阶段+节点配置+草稿 的合并定位结果（listFillNodes / W1 / W2 共用上下文） */
    public record FillContext(ProjectRef project, PhaseTarget phase, List<ObjectNode> nodes,
                              String draftLedgerId, LedgerView ledger) {

        public boolean hasDraft() {
            return draftLedgerId != null && !draftLedgerId.isEmpty();
        }
    }

    private final RestActionClient restActionClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public FillContextSupport(RestActionClient restActionClient) {
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

    // ── 项目定位 ──────────────────────────────────────────────────────────────

    /**
     * 项目定位：关键词可为 项目ID / 项目编码 / 项目名称。
     * L1 精确（ID/编码/名称）→ L2 名称包含（唯一命中才接受）。
     * 未命中/多命中抛 TaskFlowException 并附候选清单（LLM 自纠/追问素材）。
     */
    public ProjectRef findProject(String projectIdOrName, String token, Long tenantId) {
        if (projectIdOrName == null || projectIdOrName.isBlank()) {
            throw new TaskFlowException("❌ 缺少项目标识（projectIdOrName）。请先调用 listMyProjects 让用户选择项目。");
        }
        RestActionClient.ActionResult r = restActionClient.get(
                "/doc/workbench/my-projects", query("pageSize", "99"), token, tenantId);
        requireOk(r, "查询我的项目");
        List<ObjectNode> projects = new ArrayList<>();
        for (JsonNode p : r.data().path("list")) {
            if (p instanceof ObjectNode o) {
                projects.add(o);
            }
        }
        if (projects.isEmpty()) {
            throw new TaskFlowException("❌ 您名下没有进行中的项目，无法填报资料。");
        }
        String q = projectIdOrName.trim();
        // L1 精确：ID / 编码 / 名称
        for (ObjectNode p : projects) {
            if (q.equals(p.path("projectId").asText("")) || q.equals(p.path("projectCode").asText(""))) {
                return toProjectRef(p);
            }
        }
        for (ObjectNode p : projects) {
            if (q.equals(p.path("projectName").asText(""))) {
                return toProjectRef(p);
            }
        }
        // L2 名称包含（唯一命中才接受）
        List<ObjectNode> contains = new ArrayList<>();
        for (ObjectNode p : projects) {
            String name = p.path("projectName").asText("");
            if (!name.isEmpty() && name.toLowerCase(java.util.Locale.ROOT).contains(q.toLowerCase(java.util.Locale.ROOT))) {
                contains.add(p);
            }
        }
        if (contains.size() == 1) {
            return toProjectRef(contains.get(0));
        }
        StringBuilder sb = new StringBuilder("❌ 项目「").append(projectIdOrName)
                .append("」未").append(contains.isEmpty() ? "找到" : "唯一命中")
                .append("。您名下的项目：\n");
        int cap = Math.min(projects.size(), 20);
        for (int i = 0; i < cap; i++) {
            ObjectNode p = projects.get(i);
            sb.append("- ").append(p.path("projectName").asText(""))
                    .append("（编码 ").append(p.path("projectCode").asText("")).append("）\n");
        }
        throw new TaskFlowException(sb.toString());
    }

    private static ProjectRef toProjectRef(ObjectNode p) {
        return new ProjectRef(p.path("projectId").asText(""), p.path("projectName").asText(""),
                p.path("projectCode").asText(""), p.path("type").asText(""),
                p.path("hasWarning").isBoolean() ? p.path("hasWarning").asBoolean() : null, p.deepCopy());
    }

    // ── 阶段定位 ──────────────────────────────────────────────────────────────

    /**
     * 子阶段定位：关键词可为 阶段ID / 阶段编码 / 阶段名称。
     * 编码走 resolve-phase；名称走阶段树扫描（大阶段命中→返回子阶段候选；子阶段唯一命中→定位）。
     */
    public PhaseTarget resolvePhase(ProjectRef project, String phaseIdOrNameOrCode,
                                    String token, Long tenantId) {
        if (phaseIdOrNameOrCode == null || phaseIdOrNameOrCode.isBlank()) {
            throw new TaskFlowException("❌ 缺少阶段标识（phaseIdOrNameOrCode）。请先调用 listPhaseTree 让用户选择填报阶段。");
        }
        String q = phaseIdOrNameOrCode.trim();
        // 阶段编码 → resolve-phase
        PhaseTarget byCode = tryResolveByCode(project, q, token, tenantId);
        if (byCode != null) {
            return byCode;
        }
        // 阶段树扫描（名称匹配；数字串按 ID 兜底）
        return scanPhaseTree(project, q, token, tenantId);
    }

    private PhaseTarget tryResolveByCode(ProjectRef project, String code, String token, Long tenantId) {
        RestActionClient.ActionResult r = restActionClient.get(
                "/doc/ledger/resolve-phase", query("phaseCode", code), token, tenantId);
        if (!r.success() || r.data() == null || r.data().isMissingNode() || r.data().isNull()) {
            return null;
        }
        String phaseId = r.data().path("phaseId").asText("");
        if (phaseId.isEmpty()) {
            return null;
        }
        return new PhaseTarget(project.projectId(), phaseId,
                r.data().path("phaseCode").asText(code), r.data().path("phaseName").asText(""), "");
    }

    /** 大阶段命中（父节点不能直接填报）：携带子阶段候选的中断异常 */
    private PhaseTarget scanPhaseTree(ProjectRef project, String keyword, String token, Long tenantId) {
        RestActionClient.ActionResult r = restActionClient.get(
                "/doc/project/detail", query("projectId", project.projectId()), token, tenantId);
        requireOk(r, "查询项目阶段树");

        List<String> majorCandidates = new ArrayList<>();
        List<ObjectNode> subCandidates = new ArrayList<>();
        List<PhaseTarget> subHits = new ArrayList<>();
        String kw = keyword.toLowerCase(java.util.Locale.ROOT);
        for (JsonNode major : r.data().path("majorPhases")) {
            String majorId = major.path("phaseId").asText("");
            String majorName = major.path("phaseName").asText("");
            RestActionClient.ActionResult cr = restActionClient.get(
                    "/doc/project/phase-children",
                    query("projectId", project.projectId(), "majorPhaseId", majorId), token, tenantId);
            if (!cr.success()) {
                continue;
            }
            for (JsonNode sub : cr.data().path("list")) {
                String id = sub.path("id").asText("");
                String name = sub.path("name").asText("");
                String code = sub.path("code").asText("");
                if (id.equals(keyword) || code.equals(keyword)
                        || name.equals(keyword)
                        || (!name.isEmpty() && name.toLowerCase(java.util.Locale.ROOT).contains(kw))) {
                    subHits.add(new PhaseTarget(project.projectId(), id, code, name, majorName));
                }
                subCandidates.add(sub.deepCopy());
            }
            majorCandidates.add(majorName);
        }
        if (subHits.size() == 1) {
            return subHits.get(0);
        }
        // 歧义/未命中 → 候选清单（LLM 展示给用户挑，不静默错阶段）
        StringBuilder sb = new StringBuilder("❌ 阶段「").append(keyword).append("」未")
                .append(subHits.isEmpty() ? "找到" : "唯一命中").append("。");
        if (!subHits.isEmpty()) {
            sb.append("命中多个阶段，请让用户指明：\n");
            for (PhaseTarget t : subHits) {
                sb.append("- ").append(t.majorPhaseName()).append(" / ").append(t.phaseName())
                        .append("（编码 ").append(t.phaseCode()).append("）\n");
            }
            throw new TaskFlowException(sb.toString());
        }
        sb.append("当前项目的阶段结构：\n");
        for (ObjectNode sub : subCandidates) {
            sb.append("- ").append(sub.path("name").asText(""))
                    .append("（编码 ").append(sub.path("code").asText("")).append("）\n");
        }
        if (subCandidates.isEmpty()) {
            sb.append("（该项目暂无子阶段）大阶段：").append(String.join(" / ", majorCandidates));
        }
        throw new TaskFlowException(sb.toString());
    }

    // ── 节点配置与种子行 ──────────────────────────────────────────────────────

    /** get-nodes：子阶段的文档节点配置（nodeId/nodeName/required/presetCategories[]） */
    public List<ObjectNode> loadNodeConfigs(String phaseId, String token, Long tenantId) {
        RestActionClient.ActionResult r = restActionClient.get(
                "/doc/ledger/get-nodes", query("phaseId", phaseId), token, tenantId);
        requireOk(r, "查询阶段文件节点");
        List<ObjectNode> nodes = new ArrayList<>();
        for (JsonNode n : r.data()) {
            if (n instanceof ObjectNode o) {
                nodes.add(o);
            }
        }
        if (nodes.isEmpty()) {
            throw new TaskFlowException("❌ 该子阶段未配置任何文件节点，无需填报资料。");
        }
        return nodes;
    }

    /** nodeId→节点名（消歧与面板分组用） */
    public static Map<String, String> nodeNamesOf(List<ObjectNode> nodes) {
        Map<String, String> names = new LinkedHashMap<>();
        for (ObjectNode n : nodes) {
            String id = n.path("nodeId").asText("");
            String name = n.path("nodeName").asText("");
            if (!id.isEmpty() && !name.isEmpty()) {
                names.put(id, name);
            }
        }
        return names;
    }

    /**
     * 填报节点解析（纯内存匹配，无 REST）：关键词可为 节点ID / 节点编码 / 节点名称。
     * L1 精确（ID/编码/名称）→ L2 名称包含（唯一命中才接受）。
     * 未命中/多命中抛 TaskFlowException 并附节点清单（LLM 自纠/追问素材）。
     */
    public static ObjectNode resolveNode(List<ObjectNode> nodes, String nodeIdOrNameOrCode) {
        if (nodeIdOrNameOrCode == null || nodeIdOrNameOrCode.isBlank()) {
            throw new TaskFlowException("❌ 缺少填报节点标识（nodeNameOrId）。请先让用户确认要填报的节点。");
        }
        String q = nodeIdOrNameOrCode.trim();
        // L1 精确：ID / 编码 / 名称
        for (ObjectNode n : nodes) {
            if (q.equals(n.path("nodeId").asText("")) || q.equals(n.path("nodeCode").asText(""))) {
                return n.deepCopy();
            }
        }
        for (ObjectNode n : nodes) {
            if (q.equals(n.path("nodeName").asText(""))) {
                return n.deepCopy();
            }
        }
        // L2 名称包含（唯一命中才接受）
        List<ObjectNode> contains = new ArrayList<>();
        String kw = q.toLowerCase(java.util.Locale.ROOT);
        for (ObjectNode n : nodes) {
            String name = n.path("nodeName").asText("");
            if (!name.isEmpty() && name.toLowerCase(java.util.Locale.ROOT).contains(kw)) {
                contains.add(n);
            }
        }
        if (contains.size() == 1) {
            return contains.get(0).deepCopy();
        }
        StringBuilder sb = new StringBuilder("❌ 填报节点「").append(nodeIdOrNameOrCode)
                .append("」未").append(contains.isEmpty() ? "找到" : "唯一命中").append("。本子阶段的节点：\n");
        for (ObjectNode n : nodes) {
            sb.append("- ").append(n.path("nodeName").asText(""))
                    .append("（").append(n.path("presetCategories").size()).append(" 个预设类别）\n");
        }
        throw new TaskFlowException(sb.toString());
    }

    /**
     * 预设种子行（对齐业务前端 edit.vue seed：每类别一行，待上传、summary 取类别默认值、
     * 涉密取类别默认）。首次填报无草稿时的合并基准。
     */
    public static List<ObjectNode> seedRowsFromNodes(List<ObjectNode> nodes) {
        List<ObjectNode> rows = new ArrayList<>();
        for (ObjectNode node : nodes) {
            String nodeId = node.path("nodeId").asText("");
            String nodeCode = node.path("nodeCode").asText("");
            int sort = 0;
            for (JsonNode cat : node.path("presetCategories")) {
                ObjectNode row = newMapper().createObjectNode();
                row.put("nodeId", nodeId);
                row.put("nodeCode", nodeCode);
                row.put("category", cat.path("name").asText(""));
                row.put("categoryCode", cat.path("code").asText(""));
                row.put("source", 0);
                row.put("fileUrl", "");
                row.put("fileName", "");
                row.put("attachmentUrl", "");
                row.put("attachmentName", "");
                row.put("description", "");
                row.put("summary", cat.path("summary").asText(""));
                row.put("version", "");
                row.put("provider", "");
                row.put("providerTime", "");
                row.put("docPeriod", "");
                row.put("notApplicable", -1);
                row.put("sort", sort++);
                boolean confidential = cat.path("defaultIsConfidential").asBoolean(
                        cat.path("visibleRoleIds").path("0").asInt(0) > 0);
                row.put("isConfidential", confidential);
                if (cat.path("visibleRoleIds").isArray() && cat.path("visibleRoleIds").size() > 0) {
                    row.set("visibleRoleIds", cat.path("visibleRoleIds").deepCopy());
                }
                rows.add(row);
            }
        }
        return rows;
    }

    // ── 草稿检测与台账视图 ────────────────────────────────────────────────────

    /**
     * 常用审核人推荐（按子阶段历史审批聚合，category 固定 doc_collect，取前 8）。
     * 提审/重提首轮的"默认选人"数据源：调用方取第 1 名为默认、其余为备选展示。
     * 接口失败/为空返回空列表（调用方给出人话引导，不默认乱选）。
     */
    public List<ObjectNode> recommendAssignees(String projectId, String phaseId,
                                               String token, Long tenantId) {
        List<ObjectNode> out = new ArrayList<>();
        // category 必传：主平台 SQL 对 null category 走 #{category} IS NULL，PG 无法推断参数类型直接 500
        RestActionClient.ActionResult r = restActionClient.get(
                "/doc/recommend/assignees",
                query("projectId", projectId, "subStageId", phaseId,
                        "limit", "8", "category", "doc_collect"), token, tenantId);
        if (!r.success() || r.data() == null || !r.data().isArray()) {
            return out;
        }
        for (JsonNode a : r.data()) {
            if (a instanceof ObjectNode o && !o.path("userId").asText("").isEmpty()) {
                out.add(o);
            }
        }
        return out;
    }

    /** 同用户同项目同子阶段草稿唯一检测：返回 ledgerId 字符串，无草稿返回 null */
    public String findDraftLedgerId(String phaseId, String projectId, String token, Long tenantId) {
        RestActionClient.ActionResult r = restActionClient.get(
                "/doc/ledger/get-draft", query("phaseId", phaseId, "projectId", projectId), token, tenantId);
        requireOk(r, "检测草稿台账");
        JsonNode d = r.data();
        if (d == null || d.isMissingNode() || d.isNull()) {
            return null;
        }
        long id = d.asLong(0);
        return id > 0 ? String.valueOf(id) : null;
    }

    /** 台账详情合并视图：nodeGroups 平铺行（deepCopy）+ nodeId→节点名 */
    public LedgerView loadLedgerDetail(String ledgerId, String token, Long tenantId) {
        RestActionClient.ActionResult r = restActionClient.get(
                "/doc/ledger/get", query("id", ledgerId), token, tenantId);
        requireOk(r, "查询台账详情");
        JsonNode header = r.data();
        List<ObjectNode> rows = new ArrayList<>();
        Map<String, String> nodeNames = new LinkedHashMap<>();
        for (JsonNode group : header.path("nodeGroups")) {
            String nodeId = group.path("nodeId").asText("");
            String nodeName = group.path("nodeName").asText("");
            if (!nodeId.isEmpty() && !nodeName.isEmpty()) {
                nodeNames.put(nodeId, nodeName);
            }
            for (JsonNode entry : group.path("fileEntries")) {
                if (entry instanceof ObjectNode o) {
                    rows.add(o.deepCopy());
                }
            }
        }
        return new LedgerView(header, rows, nodeNames);
    }

    // ── REST 结果辅助 ─────────────────────────────────────────────────────────

    /** 有序 query 构造（固定参数顺序） */
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

    /** 静态工具方法用的独立 mapper（不依赖实例状态） */
    private static ObjectMapper newMapper() {
        return new ObjectMapper();
    }
}
