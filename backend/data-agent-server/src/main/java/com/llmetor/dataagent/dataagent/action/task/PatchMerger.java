package com.llmetor.dataagent.dataagent.action.task;

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
 * 增量 patch 合并器（M-E1，规则见计划 §3.5 D5 定稿）。
 *
 * <p>主后端 resubmit/stage-draft 是表单式<b>全量</b>提交（14 条 × 25 字段），LLM 拼全量必错；
 * 让 LLM 只说「把类别=项目建议书的 notApplicable 改成 2」，本组件在工具层负责
 * 拉现状→定位→校验→合并成全量 fileEntries——表单接口适配成对话工具的关键一跳。
 *
 * <p>设计原则（D5）：**宁可响亮地歧义（追问），绝不安静地错行**。
 * <ul>
 *   <li>定位双轨：{@code category} 必填做主定位；{@code node}/{@code entryId}（字符串）可选做消歧限定。
 *       排除行号定位（会漂移→静默错行）与纯 entryId 定位（19 位雪花 ID 转写不可靠）。</li>
 *   <li>匹配阶梯：L1 精确/归一化匹配 → L2 包含匹配（仅"唯一命中"才接受）。</li>
 *   <li>歧义协议：返回候选清单（节点+类别+文件+状态）供追问；**"不改了"是合法出口**。</li>
 *   <li>零命中协议：返回当前可用类别清单（含处理状态），LLM 自纠，不打扰用户。</li>
 *   <li>三道闸：G1 锁定闸（auditStatus=2 已通过行拒绝）；G2 冲突闸（同 entryId+field 重复 patch 拒整批）；
 *       G3 白名单闸（白名单外字段拒绝并给人话提示，防 LLM 反复重试）。</li>
 *   <li>全批 all-or-nothing：任一 patch 歧义/零命中/闸拒绝 → 整批不执行，一次性给出全部原因。</li>
 * </ul>
 *
 * <p>纯逻辑组件（不碰 REST/DB），单测直接构造行数据覆盖 D5 场景模拟的 9 个场景。
 */
@Component
public class PatchMerger {

    // ── 对话侧 patch 形态（LLM 产出）────────────────────────────────────────────

    /**
     * LLM 产出的增量 patch：category 主定位，node/entryId 可选消歧限定词。
     *
     * @param category 类别名（必填，主定位）
     * @param node     节点名/编码（可选，歧义时消歧）
     * @param entryId  文件条目 ID（可选，字符串；仅作消歧限定，不作主定位）
     * @param field    字段名（必填，须在白名单内）
     * @param value    新值（必填，字符串形式，按字段类型强转）
     */
    public record PatchSpec(String category, String node, String entryId, String field, String value) {
    }

    /**
     * 解析成功的 patch：定位到唯一行、通过三道闸、值已完成类型强转。
     * typedValue 供组装全量时写入（Integer/Long 走数值节点，其余字符串）。
     */
    public record ResolvedPatch(String entryId, String nodeId, String node, String category,
                                String field, String oldValue, String newValue, Object typedValue) {
    }

    /** 歧义：命中多行，携带候选描述（节点+类别+文件+审核状态）供追问 */
    public record Ambiguity(String category, List<String> candidates) {
    }

    /** 零命中：携带当前可用类别清单（LLM 自纠素材，不打扰用户） */
    public record ZeroHit(String category, String detail, List<String> available) {
    }

    /** 批量解析结果：全批 all-or-nothing */
    public record MergeResult(Status status, List<ResolvedPatch> resolved,
                              List<Ambiguity> ambiguities, List<ZeroHit> zeroHits,
                              List<String> rejections) {

        public enum Status { OK, FAILED }

        static MergeResult ok(List<ResolvedPatch> resolved) {
            return new MergeResult(Status.OK, List.copyOf(resolved), List.of(), List.of(), List.of());
        }

        static MergeResult failed(List<Ambiguity> ambiguities, List<ZeroHit> zeroHits, List<String> rejections) {
            return new MergeResult(Status.FAILED, List.of(), List.copyOf(ambiguities),
                    List.copyOf(zeroHits), List.copyOf(rejections));
        }

        public boolean ok() {
            return status == Status.OK;
        }

        /** 面向 LLM 的引导文本：一次性列出全部问题（拒绝原因/歧义候选/零命中可用清单） */
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
            }
            sb.append("提示：也可以放弃本次修改（用户说「不改了」是合法出口）。");
            return sb.toString();
        }
    }

    // ── 字段白名单（M-E1 定稿：LLM 可改 vs 服务端/流程/策略字段）────────────────

    /** 白名单外常见字段的拒绝话术（人话，指明出路，防 LLM 反复重试） */
    private static final Map<String, String> FIELD_DENY_HINTS = Map.of(
            "auditStatus", "审核状态由审批流程自动维护，不能手工修改。",
            "uploader", "上传人由系统自动记录，不能手工修改。",
            "uploadTime", "上传时间由系统自动记录，不能手工修改。",
            "pmOpinion", "项目经理意见由 PM 审核时填写，申报人不能修改。",
            "visibleRoleIds", "可见角色设置涉及权限策略，请在主平台操作。",
            "isConfidential", "涉密标识请在主平台操作。",
            "sort", "排序由系统维护，不能手工修改。",
            "source", "条目来源由系统维护，不能手工修改。",
            "nodeId", "所属节点由系统维护，不能手工修改。",
            "id", "条目 ID 不能修改。");

    /** 组装全量时剔除的字段：响应专属回显 + 流程字段（全程不碰，由主后端按自身规则处理） */
    private static final Set<String> STRIP_FIELDS = Set.of(
            "fullUrl", "attachmentFullUrl", "uploaderName", "pmOpinion",
            "auditStatus", "uploader", "uploadTime");

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
        m.put("version", text(100));
        m.put("provider", text(100));
        m.put("providerTime", raw -> {
            try {
                return LocalDate.parse(raw.trim()).toString();
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("providerTime 需为 yyyy-MM-dd 格式日期，收到：" + raw);
            }
        });
        m.put("docPeriod", text(200));
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
     * 批量解析 patch：全部通过返回 {@link MergeResult#ok}；任一失败整批拒绝（all-or-nothing）。
     *
     * @param patches   LLM 产出的增量 patch 批
     * @param baseRows  基准行（有 STAGE 暂存用暂存行，否则用台账主表行）
     * @param nodeNames nodeId → 节点名（消歧与候选描述用）
     * @param lockedIds 已通过锁定行 id 集（auditStatus=2 的主表行）
     */
    public MergeResult resolve(List<PatchSpec> patches, List<ObjectNode> baseRows,
                               Map<String, String> nodeNames, Set<String> lockedIds) {
        List<ResolvedPatch> resolved = new ArrayList<>();
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
            PatchSpec patch = new PatchSpec(p.category(), p.node(), p.entryId(), field, p.value());

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

            // 定位：L1 精确/归一化 → L2 包含唯一命中
            if (isBlank(patch.category())) {
                rejections.add("有一条 patch 缺少 category（类别名），无法定位目标行。");
                continue;
            }
            List<ObjectNode> hits = matchByCategory(baseRows, patch.category());
            String missNote = hits.isEmpty() ? "L1/L2 均未命中" : null;
            if (hits.isEmpty()) {
                zeroHits.add(new ZeroHit(patch.category(), "类别未命中", availableCategories(baseRows, nodeNames)));
                continue;
            }

            // 消歧限定词：node（节点名/编码）与 entryId（字符串精确）
            List<ObjectNode> narrowed = narrow(hits, patch, nodeNames);
            if (narrowed.isEmpty()) {
                zeroHits.add(new ZeroHit(patch.category(),
                        "类别命中但限定条件（node=" + safe(patch.node()) + " / entryId=" + safe(patch.entryId()) + "）未命中任何行",
                        availableCategories(hits, nodeNames)));
                continue;
            }
            if (narrowed.size() > 1) {
                ambiguities.add(new Ambiguity(patch.category(), describe(narrowed, nodeNames)));
                continue;
            }

            ObjectNode row = narrowed.get(0);
            String entryId = idOf(row);

            // G1 锁定闸：已通过行不可修改
            if (lockedIds.contains(entryId)) {
                rejections.add("类别「" + rowCategory(row) + "」本轮已通过审核（🔒锁定行），不能再修改。");
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
        return MergeResult.ok(resolved);
    }

    // ── 组装：基准行 + 解析结果 → 全量 fileEntries ─────────────────────────────

    /**
     * 全量组装：未修改行原样回传、patch 行应用修改、extra 行（锁定行等不在基准内的）追加；
     * 所有行剔除响应专属/流程字段（fullUrl/uploaderName/pmOpinion/auditStatus/uploader/uploadTime），
     * 流程字段全程不碰，由主后端按自身规则处理。
     *
     * @param extraRows 需原样追加的行（锁定行；基准=STAGE 暂存行时主表锁定行不在其中）
     */
    public List<ObjectNode> assembleFullRows(List<ObjectNode> baseRows, List<ResolvedPatch> resolved,
                                             List<ObjectNode> extraRows) {
        Map<String, List<ResolvedPatch>> byEntry = new HashMap<>();
        for (ResolvedPatch rp : resolved) {
            byEntry.computeIfAbsent(rp.entryId(), k -> new ArrayList<>()).add(rp);
        }
        List<ObjectNode> full = new ArrayList<>(baseRows.size() + (extraRows == null ? 0 : extraRows.size()));
        Set<String> present = new HashSet<>();
        for (ObjectNode row : baseRows) {
            ObjectNode copy = row.deepCopy();
            String id = idOf(copy);
            present.add(id);
            List<ResolvedPatch> rps = byEntry.get(id);
            if (rps != null) {
                for (ResolvedPatch rp : rps) {
                    applyPatch(copy, rp);
                }
            }
            strip(copy);
            full.add(copy);
        }
        if (extraRows != null) {
            for (ObjectNode row : extraRows) {
                if (present.contains(idOf(row))) {
                    continue;
                }
                ObjectNode copy = row.deepCopy();
                strip(copy);
                full.add(copy);
            }
        }
        return full;
    }

    private static void applyPatch(ObjectNode row, ResolvedPatch rp) {
        if (rp.typedValue() instanceof Integer i) {
            row.put(rp.field(), i);
        } else if (rp.typedValue() instanceof Long l) {
            row.put(rp.field(), l);
        } else {
            row.put(rp.field(), String.valueOf(rp.typedValue()));
        }
    }

    private static void strip(ObjectNode row) {
        STRIP_FIELDS.forEach(row::remove);
    }

    // ── 定位辅助 ──────────────────────────────────────────────────────────────

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

    /** 限定词收窄：node（节点名/编码，等值或包含）→ entryId（字符串精确） */
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
                if (q.equals(idOf(row))) {
                    next.add(row);
                }
            }
            cur = next;
        }
        return cur;
    }

    /** 候选行描述：[节点] 类别（文件:xx，审核:xx，id:xx）——供歧义追问 */
    private static List<String> describe(List<ObjectNode> rows, Map<String, String> nodeNames) {
        List<String> out = new ArrayList<>();
        int cap = Math.min(rows.size(), 6);
        for (int i = 0; i < cap; i++) {
            ObjectNode row = rows.get(i);
            out.add("[" + nodeNameOf(row, nodeNames) + "] " + rowCategory(row)
                    + "（文件:" + orDash(row.path("fileName").asText(""))
                    + "，审核:" + DocLabels.auditStatus(TaskIds.intOrNull(row.path("auditStatus")))
                    + "，entryId:" + idOf(row) + "）");
        }
        if (rows.size() > cap) {
            out.add("…等共 " + rows.size() + " 行");
        }
        return out;
    }

    /** 可用类别清单（零命中自纠素材）：[节点] 类别（审核:xx） */
    private static List<String> availableCategories(List<ObjectNode> rows, Map<String, String> nodeNames) {
        List<String> out = new ArrayList<>();
        int cap = Math.min(rows.size(), 30);
        for (int i = 0; i < cap; i++) {
            ObjectNode row = rows.get(i);
            out.add("[" + nodeNameOf(row, nodeNames) + "] " + rowCategory(row)
                    + "（审核:" + DocLabels.auditStatus(TaskIds.intOrNull(row.path("auditStatus")))
                    + "，取得:" + DocLabels.notApplicable(TaskIds.intOrNull(row.path("notApplicable"))) + "）");
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
            return DocLabels.notApplicable(TaskIds.intOrNull(row.path("notApplicable")));
        }
        String v = row.path(field).asText("");
        return v.isEmpty() ? "（空）" : v;
    }

    private static String displayValue(String field, Object typed) {
        if ("notApplicable".equals(field) && typed instanceof Integer i) {
            return DocLabels.notApplicable(i);
        }
        return String.valueOf(typed);
    }

    private static String rowCategory(ObjectNode row) {
        return row.path("category").asText("");
    }

    private static String idOf(ObjectNode row) {
        return row.path("id").asText("");
    }

    private static String nodeNameOf(ObjectNode row, Map<String, String> nodeNames) {
        String name = nodeNames.getOrDefault(row.path("nodeId").asText(""), "");
        if (name.isEmpty()) {
            name = row.path("nodeCode").asText("");
        }
        return name.isEmpty() ? "未分组" : name;
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
