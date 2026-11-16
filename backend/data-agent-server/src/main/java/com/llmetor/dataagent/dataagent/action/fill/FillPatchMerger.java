package com.llmetor.dataagent.dataagent.action.fill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 填报 patch 合并器（M-E2，协议与 M-E1 {@code PatchMerger} 同构，白名单按设计 §4.2 扩展）。
 *
 * <p>主后端 create/update 是表单式<b>全量</b>提交（fileEntries 全删重插），LLM 拼全量必错；
 * 让 LLM/面板只说「把类别=可研批复文件的状态改成已取得」，本组件负责
 * 拉现状→定位→校验→合并成全量 fileEntries——表单接口适配成对话工具的关键一跳。
 *
 * <p>与 M-E1 的差异：
 * <ul>
 *   <li>白名单扩展：attachmentUrl+attachmentName、isConfidential+visibleRoleIds（设计 §8 边界 5 涉密）。</li>
 *   <li>自定义行：{@code custom=true} 且类别未命中预设 → 新增 source=1 自定义行（须指明归属节点，
 *       对齐边界 6「先确认再落库」——是否新增由 LLM 先问用户）。</li>
 *   <li>组装时剥离 {@code id}（主后端 update 全删重插，业务前端同样不回传 id）。</li>
 * </ul>
 *
 * <p>设计原则沿用 M-E1 D5：**宁可响亮地歧义（追问），绝不安静地错行**——
 * 定位 category 必填、node/entryId 可选消歧；L1 精确→L2 包含唯一；歧义返回候选清单；
 * 全批 all-or-nothing；锁定闸（auditStatus=2）/冲突闸（同 entry+field 重复）/白名单闸三道闸。
 */
@Component
public class FillPatchMerger {

    // ── patch 形态（LLM/面板配置注入共用）──────────────────────────────────────

    /**
     * 增量 patch：category 主定位；node/entryId 可选消歧限定；custom=true 允许新增自定义行。
     */
    public record PatchSpec(String category, String node, String entryId, String field,
                            String value, Boolean custom) {
    }

    /** 解析成功的 patch（定位到唯一行、通过三道闸、值已强转） */
    public record ResolvedPatch(String entryId, String nodeId, String node, String category,
                                String field, String oldValue, String newValue, Object typedValue) {
    }

    /** 新增的自定义行（source=1，custom=true 且未命中预设时产出） */
    public record AddedRow(ObjectNode row, String node, String category, String field,
                           String oldValue, String newValue, Object typedValue) {
    }

    /** 歧义：命中多行，携带候选描述供追问 */
    public record Ambiguity(String category, List<String> candidates) {
    }

    /** 零命中：携带可用类别清单（LLM 自纠素材）+ 是否可走自定义行 */
    public record ZeroHit(String category, String detail, List<String> available, boolean customHint) {
    }

    /** 批量解析结果：全批 all-or-nothing */
    public record MergeResult(Status status, List<ResolvedPatch> resolved, List<AddedRow> added,
                              List<Ambiguity> ambiguities, List<ZeroHit> zeroHits,
                              List<String> rejections) {

        public enum Status {OK, FAILED}

        static MergeResult ok(List<ResolvedPatch> resolved, List<AddedRow> added) {
            return new MergeResult(Status.OK, List.copyOf(resolved), List.copyOf(added),
                    List.of(), List.of(), List.of());
        }

        static MergeResult failed(List<Ambiguity> ambiguities, List<ZeroHit> zeroHits,
                                  List<String> rejections) {
            return new MergeResult(Status.FAILED, List.of(), List.of(), List.copyOf(ambiguities),
                    List.copyOf(zeroHits), List.copyOf(rejections));
        }

        public boolean ok() {
            return status == Status.OK;
        }

        public int changeCount() {
            return resolved.size() + added.size();
        }

        /** 面向 LLM 的引导文本：一次性列出全部问题 */
        public String guidance() {
            StringBuilder sb = new StringBuilder("❌ 本批修改未通过校验，未产生任何变更。请按下列原因逐条修正后整体重试（同一批 patch 全部通过才会执行）：\n");
            int i = 1;
            for (String r : rejections) {
                sb.append(i++).append(". [拒绝] ").append(r).append('\n');
            }
            for (Ambiguity a : ambiguities) {
                sb.append(i++).append(". [歧义] 类别「").append(a.category()).append("」命中多行，请让用户指明目标行：\n");
                for (String c : a.candidates()) {
                    sb.append("    - ").append(c).append('\n');
                }
            }
            for (ZeroHit z : zeroHits) {
                sb.append(i++).append(". [未命中] ").append(z.detail())
                        .append("（关键词「").append(z.category()).append("」）。当前可用类别：\n");
                for (String c : z.available()) {
                    sb.append("    - ").append(c).append('\n');
                }
                if (z.customHint()) {
                    sb.append("    若是新增自定义类别：先向用户确认「是否新增自定义类别行？」，")
                            .append("确认后在对应 patch 上加 custom=true 并指明归属节点 node。");
                }
            }
            sb.append("提示：也可以放弃本次修改（用户说「不改了」是合法出口）。");
            return sb.toString();
        }
    }

    // ── 字段白名单（设计 §4.2 fill 定稿）──────────────────────────────────────

    /** 白名单外常见字段的拒绝话术（人话，指明出路，防 LLM 反复重试） */
    private static final Map<String, String> FIELD_DENY_HINTS = Map.of(
            "auditStatus", "审核状态由审批流程自动维护，不能手工修改。",
            "uploader", "上传人由系统自动记录，不能手工修改。",
            "uploadTime", "上传时间由系统自动记录，不能手工修改。",
            "pmOpinion", "项目经理意见由 PM 审核时填写，申报人不能修改。",
            "sort", "排序由系统维护，不能手工修改。",
            "source", "条目来源由系统维护，不能手工修改。",
            "nodeId", "所属节点由系统维护，不能手工修改（新增自定义行请在 patch 上带 node 指明归属）。",
            "id", "条目 ID 不能修改。",
            "categoryCode", "类别编码由系统维护，不能手工修改。");

    /** 组装全量时剔除的字段：响应专属回显 + 流程字段 + id（update 全删重插不回传 id） */
    private static final Set<String> STRIP_FIELDS = Set.of(
            "id", "fullUrl", "attachmentFullUrl", "uploaderName", "uploadTime",
            "auditStatus", "pmOpinion", "defaultSummary");

    /** 值强转器：失败抛 IllegalArgumentException（消息直接进拒绝理由） */
    private interface FieldCodec {
        Object coerce(String raw);
    }

    private static final Map<String, FieldCodec> WHITELIST = buildWhitelist();

    private static Map<String, FieldCodec> buildWhitelist() {
        Map<String, FieldCodec> m = new LinkedHashMap<>();
        m.put("notApplicable", raw -> {
            int v;
            try {
                v = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("notApplicable 需为整数（-1待上传/0已取得/1空缺/2不涉及/3其他），收到：" + raw);
            }
            if (v < -1 || v > 3) {
                throw new IllegalArgumentException("notApplicable 只能取 -1/0/1/2/3，收到：" + v);
            }
            return v;
        });
        m.put("summary", text(2000));
        m.put("description", text(2000));
        m.put("fileUrl", text(1000));
        m.put("fileName", text(300));
        m.put("attachmentUrl", text(1000));
        m.put("attachmentName", text(300));
        m.put("provider", text(100));
        m.put("providerTime", raw -> {
            try {
                return LocalDate.parse(raw.trim()).toString();
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("providerTime 需为 yyyy-MM-dd 格式日期，收到：" + raw);
            }
        });
        m.put("isConfidential", raw -> {
            String v = raw.trim().toLowerCase(Locale.ROOT);
            if (v.equals("1") || v.equals("true") || raw.trim().equals("是")) {
                return Boolean.TRUE;
            }
            if (v.equals("0") || v.equals("false") || raw.trim().equals("否")) {
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("isConfidential 需为 0/1（或 是/否），收到：" + raw);
        });
        m.put("visibleRoleIds", raw -> {
            List<Long> ids = new ArrayList<>();
            for (String part : raw.split("[,，;；\\s]+")) {
                if (part.isBlank()) {
                    continue;
                }
                try {
                    ids.add(Long.parseLong(part.trim()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("visibleRoleIds 需为逗号分隔的角色ID数字列表，收到：" + raw);
                }
            }
            if (ids.isEmpty()) {
                throw new IllegalArgumentException("visibleRoleIds 为空，如需清空可见角色请设置 isConfidential=0。");
            }
            return ids;
        });
        return m;
    }

    private static FieldCodec text(int maxLen) {
        return raw -> {
            String v = raw == null ? "" : raw.trim();
            if (v.length() > maxLen) {
                throw new IllegalArgumentException("内容超长（限 " + maxLen + " 字）");
            }
            return v;
        };
    }

    // ── 解析：patch 批 → 定位 + 三道闸 ────────────────────────────────────────

    /**
     * 批量解析 patch：全部通过返回 ok；任一失败整批拒绝（all-or-nothing）。
     *
     * @param patches   增量 patch 批
     * @param baseRows  基准行（有草稿用草稿行，否则用预设种子行）
     * @param nodeNames nodeId → 节点名（消歧与候选描述用）
     * @param lockedIds 已通过锁定行 id 集（草稿态通常为空）
     */
    public MergeResult resolve(List<PatchSpec> patches, List<ObjectNode> baseRows,
                               Map<String, String> nodeNames, Set<String> lockedIds) {
        List<ResolvedPatch> resolved = new ArrayList<>();
        List<AddedRow> added = new ArrayList<>();
        List<Ambiguity> ambiguities = new ArrayList<>();
        List<ZeroHit> zeroHits = new ArrayList<>();
        List<String> rejections = new ArrayList<>();
        Set<String> seenEntryField = new HashSet<>();

        if (patches == null || patches.isEmpty()) {
            rejections.add("patch 列表为空，没有要执行的修改。");
            return MergeResult.failed(ambiguities, zeroHits, rejections);
        }

        for (PatchSpec p : patches) {
            // 字段规范化：精确名优先，大小写不敏感兜底（schema 正确时不触发）
            String field = canonicalField(p == null ? null : p.field());
            if (field == null) {
                String f = p == null ? null : p.field();
                String hint = FIELD_DENY_HINTS.get(f == null ? "" : f.trim());
                rejections.add("字段「" + safe(f) + "」不允许通过助手修改"
                        + (hint == null ? "，请在主平台操作。" : "：" + hint));
                continue;
            }
            PatchSpec patch = new PatchSpec(p.category(), p.node(), p.entryId(), field, p.value(), p.custom());

            // 值强转（G3 的类型面）
            Object typed;
            try {
                typed = WHITELIST.get(field).coerce(patch.value() == null ? "" : patch.value());
            } catch (IllegalArgumentException e) {
                rejections.add("字段 " + field + " 的值不合法：" + e.getMessage());
                continue;
            }
            if (typed instanceof String s && s.isEmpty()) {
                rejections.add("字段 " + field + " 的新值为空，如需清空请说明具体占位内容。");
                continue;
            }
            if (patch.category() == null || patch.category().isBlank()) {
                rejections.add("有一条 patch 缺少 category（类别名），无法定位目标行。");
                continue;
            }

            List<ObjectNode> hits = matchByCategory(baseRows, patch.category());
            if (hits.isEmpty()) {
                boolean custom = Boolean.TRUE.equals(patch.custom());
                if (custom) {
                    // 自定义行：source=1，必须指明归属节点
                    String nodeId = resolveNodeIdForCustom(patch.node(), nodeNames);
                    if (nodeId == null) {
                        rejections.add("新增自定义类别「" + patch.category() + "」需要指明归属节点（patch.node），"
                                + "可选节点：" + joinNodeNames(nodeNames) + "。");
                        continue;
                    }
                    ObjectNode row = newCustomRow(nodeId, patch.category());
                    added.add(new AddedRow(row, nodeNameOfId(nodeId, nodeNames), patch.category(),
                            field, "（新增行）", displayValue(field, typed), typed));
                    continue;
                }
                zeroHits.add(new ZeroHit(patch.category(), "类别未命中",
                        availableCategories(baseRows, nodeNames), true));
                continue;
            }

            // 消歧限定词：node（节点名/编码）与 entryId（字符串精确）
            List<ObjectNode> narrowed = narrow(hits, patch, nodeNames);
            if (narrowed.isEmpty()) {
                zeroHits.add(new ZeroHit(patch.category(),
                        "类别命中但限定条件（node=" + safe(patch.node()) + " / entryId=" + safe(patch.entryId())
                                + "）未命中任何行",
                        availableCategories(hits, nodeNames), false));
                continue;
            }
            if (narrowed.size() > 1) {
                ambiguities.add(new Ambiguity(patch.category(), describe(narrowed, nodeNames)));
                continue;
            }

            ObjectNode row = narrowed.get(0);
            String entryId = keyOf(row);

            // G1 锁定闸：已通过行不可修改
            if (lockedIds.contains(entryId)) {
                rejections.add("类别「" + rowCategory(row) + "」已通过审核（🔒锁定行），不能再修改。");
                continue;
            }
            // G2 冲突闸：同 entryId+field 重复 patch → 拒整批
            if (!seenEntryField.add(entryId + "#" + field.toLowerCase(Locale.ROOT))) {
                rejections.add("字段 " + field + "（类别「" + rowCategory(row) + "」）在同一批里出现多个修改值，请合并为一条。");
                continue;
            }

            resolved.add(new ResolvedPatch(entryId, row.path("nodeId").asText(""),
                    nodeNameOf(row, nodeNames), rowCategory(row), field,
                    currentDisplay(row, field), displayValue(field, typed), typed));
        }

        if (!rejections.isEmpty() || !ambiguities.isEmpty() || !zeroHits.isEmpty()) {
            return MergeResult.failed(ambiguities, zeroHits, rejections);
        }
        return MergeResult.ok(resolved, added);
    }

    // ── 组装：基准行 + 解析结果 → 全量 fileEntries ─────────────────────────────

    /**
     * 全量组装：未修改行原样回传、patch 行应用修改、自定义新行追加；
     * 所有行剔除响应专属/流程字段与 id（对齐业务前端 collectEntries 不回传 id）。
     */
    public List<ObjectNode> assembleFullRows(List<ObjectNode> baseRows, MergeResult merged) {
        Map<String, List<ResolvedPatch>> byEntry = new HashMap<>();
        for (ResolvedPatch rp : merged.resolved()) {
            byEntry.computeIfAbsent(rp.entryId(), k -> new ArrayList<>()).add(rp);
        }
        List<ObjectNode> full = new ArrayList<>(baseRows.size() + merged.added().size());
        for (ObjectNode row : baseRows) {
            ObjectNode copy = row.deepCopy();
            List<ResolvedPatch> rps = byEntry.get(keyOf(copy));
            if (rps != null) {
                for (ResolvedPatch rp : rps) {
                    applyPatch(copy, rp.field(), rp.typedValue());
                }
            }
            strip(copy);
            full.add(copy);
        }
        for (AddedRow ar : merged.added()) {
            ObjectNode copy = ar.row().deepCopy();
            applyPatch(copy, ar.field(), ar.typedValue());
            strip(copy);
            full.add(copy);
        }
        return full;
    }

    static void applyPatch(ObjectNode row, String field, Object typed) {
        if (typed instanceof Integer i) {
            row.put(field, i);
        } else if (typed instanceof Long l) {
            row.put(field, l);
        } else if (typed instanceof Boolean b) {
            row.put(field, b);
        } else if (typed instanceof List<?> list) {
            ArrayNode arr = row.putArray(field);
            for (Object o : list) {
                if (o instanceof Long l) {
                    arr.add(l);
                } else if (o instanceof Number n) {
                    arr.add(n.longValue());
                } else {
                    arr.add(String.valueOf(o));
                }
            }
        } else {
            row.put(field, String.valueOf(typed));
        }
    }

    private static void strip(ObjectNode row) {
        STRIP_FIELDS.forEach(row::remove);
    }

    /** 自定义新行（对齐业务前端 seed 字段面，source=1，默认待上传） */
    private static ObjectNode newCustomRow(String nodeId, String category) {
        ObjectNode row = new ObjectMapper().createObjectNode();
        // 独立小对象构造，不共享实例状态；频率低（仅新增自定义行）
        row.put("nodeId", nodeId);
        row.put("category", category);
        row.put("categoryCode", "");
        row.put("source", 1);
        row.put("notApplicable", -1);
        row.put("fileUrl", "");
        row.put("fileName", "");
        row.put("attachmentUrl", "");
        row.put("attachmentName", "");
        row.put("description", "");
        row.put("summary", "");
        row.put("version", "");
        row.put("provider", "");
        row.put("providerTime", "");
        row.put("docPeriod", "");
        row.put("isConfidential", false);
        return row;
    }

    /** 自定义行归属节点解析：node（节点名/编码）→ nodeId；未指明/未命中返回 null */
    private static String resolveNodeIdForCustom(String node, Map<String, String> nodeNames) {
        if (node == null || node.isBlank()) {
            return null;
        }
        String q = norm(node);
        for (Map.Entry<String, String> e : nodeNames.entrySet()) {
            if (norm(e.getValue()).equals(q)) {
                return e.getKey();
            }
        }
        for (Map.Entry<String, String> e : nodeNames.entrySet()) {
            if (!e.getValue().isEmpty() && norm(e.getValue()).contains(q)) {
                return e.getKey();
            }
        }
        return null;
    }

    private static String joinNodeNames(Map<String, String> nodeNames) {
        return String.join(" / ", nodeNames.values());
    }

    // ── 定位辅助（与 M-E1 PatchMerger 同构）────────────────────────────────────

    /** L1 归一化精确匹配；空则 L2 包含匹配（大小写不敏感） */
    private static List<ObjectNode> matchByCategory(List<ObjectNode> rows, String category) {
        List<ObjectNode> exact = new ArrayList<>();
        String target = norm(category);
        for (ObjectNode row : rows) {
            if (norm(rowCategory(row)).equals(target)) {
                exact.add(row);
            }
        }
        if (!exact.isEmpty()) {
            return exact;
        }
        List<ObjectNode> contains = new ArrayList<>();
        for (ObjectNode row : rows) {
            String c = norm(rowCategory(row));
            if (!c.isEmpty() && c.contains(target)) {
                contains.add(row);
            }
        }
        return contains;
    }

    /** 限定词收窄：node（节点名/编码，等值或包含）→ entryId（字符串精确；种子新行无 id 时按 nodeId+类别唯一） */
    private static List<ObjectNode> narrow(List<ObjectNode> hits, PatchSpec patch, Map<String, String> nodeNames) {
        List<ObjectNode> cur = hits;
        if (!isBlank(patch.node())) {
            String q = norm(patch.node());
            List<ObjectNode> next = new ArrayList<>();
            for (ObjectNode row : cur) {
                String nodeName = norm(nodeNameOf(row, nodeNames));
                String nodeCode = norm(row.path("nodeCode").asText(""));
                if (nodeName.equals(q) || nodeCode.equals(q)
                        || (!nodeName.isEmpty() && nodeName.contains(q))
                        || (!nodeCode.isEmpty() && nodeCode.contains(q))) {
                    next.add(row);
                }
            }
            cur = next;
        }
        if (!isBlank(patch.entryId())) {
            String q = patch.entryId().trim();
            List<ObjectNode> next = new ArrayList<>();
            for (ObjectNode row : cur) {
                if (q.equals(idOf(row)) || (idOf(row).isEmpty() && q.equals(row.path("nodeId").asText("")))) {
                    next.add(row);
                }
            }
            cur = next;
        }
        return cur;
    }

    /** 候选行描述：[节点] 类别（文件:xx，状态:xx，id:xx）——供歧义追问 */
    private static List<String> describe(List<ObjectNode> rows, Map<String, String> nodeNames) {
        List<String> out = new ArrayList<>();
        int cap = Math.min(rows.size(), 6);
        for (int i = 0; i < cap; i++) {
            ObjectNode row = rows.get(i);
            out.add("[" + nodeNameOf(row, nodeNames) + "] " + rowCategory(row)
                    + "（文件:" + orDash(row.path("fileName").asText(""))
                    + "，状态:" + FillLabels.notApplicable(intOrNull(row.path("notApplicable")))
                    + "，entryId:" + orDash(idOf(row)) + "）");
        }
        if (rows.size() > cap) {
            out.add("…等共 " + rows.size() + " 行");
        }
        return out;
    }

    /** 可用类别清单（零命中自纠素材）：[节点] 类别（状态:xx） */
    private static List<String> availableCategories(List<ObjectNode> rows, Map<String, String> nodeNames) {
        List<String> out = new ArrayList<>();
        int cap = Math.min(rows.size(), 30);
        for (int i = 0; i < cap; i++) {
            ObjectNode row = rows.get(i);
            out.add("[" + nodeNameOf(row, nodeNames) + "] " + rowCategory(row)
                    + "（状态:" + FillLabels.notApplicable(intOrNull(row.path("notApplicable"))) + "）");
        }
        if (rows.size() > cap) {
            out.add("…等共 " + rows.size() + " 行");
        }
        return out;
    }

    // ── 小工具 ────────────────────────────────────────────────────────────────

    /** 字段名规范化：白名单精确名优先，大小写不敏感兜底；不在白名单返回 null */
    static String canonicalField(String field) {
        if (field == null || field.isBlank()) {
            return null;
        }
        String f = field.trim();
        if (WHITELIST.containsKey(f)) {
            return f;
        }
        for (String key : WHITELIST.keySet()) {
            if (key.equalsIgnoreCase(f)) {
                return key;
            }
        }
        return null;
    }

    /** 白名单只读视图（工具描述/校验提示复用） */
    static Set<String> whitelistFields() {
        return WHITELIST.keySet();
    }

    private static String currentDisplay(ObjectNode row, String field) {
        if ("notApplicable".equals(field)) {
            return FillLabels.notApplicable(intOrNull(row.path("notApplicable")));
        }
        if ("isConfidential".equals(field)) {
            return Boolean.TRUE.equals(row.path("isConfidential").asBoolean(false)) ? "是" : "否";
        }
        String v = row.path(field).asText("");
        return v.isEmpty() ? "（空）" : v;
    }

    private static String displayValue(String field, Object typed) {
        if ("notApplicable".equals(field) && typed instanceof Integer i) {
            return FillLabels.notApplicable(i);
        }
        if ("isConfidential".equals(field) && typed instanceof Boolean b) {
            return b ? "是" : "否";
        }
        return String.valueOf(typed);
    }

    private static String rowCategory(ObjectNode row) {
        return row.path("category").asText("");
    }

    private static String idOf(ObjectNode row) {
        JsonNode id = row.path("id");
        return id.isMissingNode() || id.isNull() ? "" : id.asText("");
    }

    /**
     * 行唯一 key：落库行用 id；草稿合并视图中主后端补的预设虚拟行无 id，
     * 用 nodeId|categoryCode|category 合成（G2 冲突闸与全量组装分组共用）。
     */
    private static String keyOf(ObjectNode row) {
        String id = idOf(row);
        if (!id.isEmpty()) {
            return id;
        }
        return row.path("nodeId").asText("") + "|" + row.path("categoryCode").asText("")
                + "|" + row.path("category").asText("");
    }

    private static String nodeNameOf(ObjectNode row, Map<String, String> nodeNames) {
        return nodeNameOfId(row.path("nodeId").asText(""), nodeNames);
    }

    private static String nodeNameOfId(String nodeId, Map<String, String> nodeNames) {
        String name = nodeNames.getOrDefault(nodeId, "");
        return name.isEmpty() ? "未分组" : name;
    }

    static Integer intOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.asInt() : null;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String orDash(String s) {
        return (s == null || s.isBlank()) ? "无" : s;
    }
}
