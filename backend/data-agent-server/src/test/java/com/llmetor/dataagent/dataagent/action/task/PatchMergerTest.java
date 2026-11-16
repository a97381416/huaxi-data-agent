package com.llmetor.dataagent.dataagent.action.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PatchMerger 单测（M-E1）：直接采用计划 §M-E1 / D5 场景模拟的 9 个场景——
 * 单条精确 / 模糊唯一命中 / 零命中 / 歧义放弃 / 批量展开 / 锁定闸 / 冲突闸 /
 * 白名单闸 / 值校验 + 全量组装。
 */
class PatchMergerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PatchMerger merger = new PatchMerger();

    private static final Map<String, String> NODES = Map.of(
            "7", "立项文件",
            "8", "合同文件");
    private static final String LOCKED_ID = "9003";

    private List<ObjectNode> rows;

    @BeforeEach
    void setUp() {
        rows = List.of(
                row("9001", "7", "项目建议书", "jianyi.pdf", 3, -1, "需盖章版"),
                row("9002", "7", "可行性研究", "kexing.pdf", 3, 0, ""),
                row(LOCKED_ID, "8", "其他合同", null, 2, 0, ""),
                row("9004", "8", "项目建议书", "jianyi2.pdf", 3, 0, ""));
    }

    private ObjectNode row(String id, String nodeId, String category, String fileName,
                           Integer auditStatus, Integer notApplicable, String pmOpinion) {
        ObjectNode o = mapper.createObjectNode();
        o.put("id", id);
        o.put("nodeId", nodeId);
        o.put("nodeCode", "N" + nodeId);
        o.put("category", category);
        if (fileName != null) {
            o.put("fileName", fileName);
        }
        if (auditStatus != null) {
            o.put("auditStatus", auditStatus);
        }
        if (notApplicable != null) {
            o.put("notApplicable", notApplicable);
        }
        o.put("pmOpinion", pmOpinion);
        o.put("fileUrl", "/file/x.pdf");
        o.put("configId", 5);
        o.put("source", 1);
        return o;
    }

    // ── 场景 1：单条精确命中 ───────────────────────────────────────────────────

    @Test
    @DisplayName("S1 精确匹配唯一行：解析成功，值完成类型强转")
    void exactMatchSingle() {
        PatchMerger.MergeResult r = merger.resolve(
                List.of(new PatchMerger.PatchSpec("项目建议书", null, null, "notApplicable", "2")),
                rows, NODES, Set.of(LOCKED_ID));

        assertThat(r.ok()).isFalse(); // 9001 与 9004 同类别 → 歧义，先验证歧义分支
        assertThat(r.ambiguities()).hasSize(1);

        // 带 node 消歧后精确命中
        r = merger.resolve(
                List.of(new PatchMerger.PatchSpec("项目建议书", "立项文件", null, "notApplicable", "2")),
                rows, NODES, Set.of(LOCKED_ID));
        assertThat(r.ok()).isTrue();
        assertThat(r.resolved()).hasSize(1);
        PatchMerger.ResolvedPatch rp = r.resolved().get(0);
        assertThat(rp.entryId()).isEqualTo("9001");
        assertThat(rp.oldValue()).isEqualTo("待上传");
        assertThat(rp.newValue()).isEqualTo("不涉及");
        assertThat(rp.typedValue()).isEqualTo(2);
    }

    // ── 场景 2：模糊包含唯一命中 ───────────────────────────────────────────────

    @Test
    @DisplayName("S2 模糊匹配：关键词「可行性」包含唯一命中即接受")
    void fuzzyUniqueMatch() {
        PatchMerger.MergeResult r = merger.resolve(
                List.of(new PatchMerger.PatchSpec("可行性", null, null, "summary", "初稿已过会")),
                rows, NODES, Set.of(LOCKED_ID));

        assertThat(r.ok()).isTrue();
        assertThat(r.resolved()).hasSize(1);
        assertThat(r.resolved().get(0).entryId()).isEqualTo("9002");
        assertThat(r.resolved().get(0).oldValue()).isEqualTo("（空）");
    }

    // ── 场景 3：零命中协议 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("S3 零命中：返回可用类别清单（LLM 自纠素材），整批拒绝")
    void zeroHit() {
        PatchMerger.MergeResult r = merger.resolve(
                List.of(new PatchMerger.PatchSpec("立项批文", null, null, "summary", "xx")),
                rows, NODES, Set.of(LOCKED_ID));

        assertThat(r.ok()).isFalse();
        assertThat(r.zeroHits()).hasSize(1);
        assertThat(r.zeroHits().get(0).available())
                .anySatisfy(s -> assertThat(s).contains("项目建议书").contains("立项文件"))
                .anySatisfy(s -> assertThat(s).contains("其他合同").contains("已通过"));
        assertThat(r.guidance()).contains("未命中", "当前可用类别");
    }

    // ── 场景 4：歧义协议 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("S4 歧义：多命中无限定词 → 候选清单；node/entryId 消歧后可命中；entryId 限定不中→零命中")
    void ambiguityProtocols() {
        // 无限定词：两行「项目建议书」→ 歧义
        PatchMerger.MergeResult r = merger.resolve(
                List.of(new PatchMerger.PatchSpec("项目建议书", null, null, "summary", "x")),
                rows, NODES, Set.of(LOCKED_ID));
        assertThat(r.ok()).isFalse();
        assertThat(r.ambiguities()).hasSize(1);
        assertThat(r.ambiguities().get(0).candidates()).hasSize(2);
        assertThat(r.guidance()).contains("歧义");

        // node 限定合同文件 → 唯一命中 9004
        r = merger.resolve(
                List.of(new PatchMerger.PatchSpec("项目建议书", "合同文件", null, "summary", "x")),
                rows, NODES, Set.of(LOCKED_ID));
        assertThat(r.ok()).isTrue();
        assertThat(r.resolved().get(0).entryId()).isEqualTo("9004");

        // entryId 限定 → 唯一命中
        r = merger.resolve(
                List.of(new PatchMerger.PatchSpec("项目建议书", null, "9004", "summary", "x")),
                rows, NODES, Set.of(LOCKED_ID));
        assertThat(r.ok()).isTrue();
        assertThat(r.resolved().get(0).entryId()).isEqualTo("9004");

        // entryId 与类别冲突（类别命中行里没有该 id）→ 零命中
        r = merger.resolve(
                List.of(new PatchMerger.PatchSpec("可行性研究", null, "9004", "summary", "x")),
                rows, NODES, Set.of(LOCKED_ID));
        assertThat(r.ok()).isFalse();
        assertThat(r.zeroHits()).hasSize(1);
    }

    // ── 场景 5：批量展开 + 全批 all-or-nothing ─────────────────────────────────

    @Test
    @DisplayName("S5 批量：多 patch 全部通过才 OK；任一失败整批拒绝")
    void batchAllOrNothing() {
        PatchMerger.MergeResult r = merger.resolve(
                List.of(
                        new PatchMerger.PatchSpec("可行性研究", null, null, "summary", "初稿已过会"),
                        new PatchMerger.PatchSpec("可行性研究", null, "9002", "docPeriod", "2026"),
                        new PatchMerger.PatchSpec("项目建议书", "立项文件", null, "notApplicable", "2")),
                rows, NODES, Set.of(LOCKED_ID));
        assertThat(r.ok()).isTrue();
        assertThat(r.resolved()).hasSize(3);

        // 第二条零命中 → 整批失败（第一条也不返回）
        r = merger.resolve(
                List.of(
                        new PatchMerger.PatchSpec("可行性研究", null, null, "summary", "x"),
                        new PatchMerger.PatchSpec("不存在", null, null, "summary", "x")),
                rows, NODES, Set.of(LOCKED_ID));
        assertThat(r.ok()).isFalse();
        assertThat(r.resolved()).isEmpty();
    }

    // ── 场景 6：G1 锁定闸 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("S6 锁定闸：auditStatus=2 已通过行拒绝（人话提示）")
    void lockedGate() {
        PatchMerger.MergeResult r = merger.resolve(
                List.of(new PatchMerger.PatchSpec("其他合同", null, null, "summary", "x")),
                rows, NODES, Set.of(LOCKED_ID));

        assertThat(r.ok()).isFalse();
        assertThat(r.rejections()).singleElement()
                .satisfies(s -> assertThat(s).contains("已通过").contains("🔒"));
    }

    // ── 场景 7：G2 冲突闸 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("S7 冲突闸：同 entryId+field 重复 patch 拒整批")
    void conflictGate() {
        PatchMerger.MergeResult r = merger.resolve(
                List.of(
                        new PatchMerger.PatchSpec("可行性研究", null, null, "summary", "甲"),
                        new PatchMerger.PatchSpec("可行性研究", null, "9002", "summary", "乙")),
                rows, NODES, Set.of(LOCKED_ID));

        assertThat(r.ok()).isFalse();
        assertThat(r.rejections()).singleElement()
                .satisfies(s -> assertThat(s).contains("多个修改值"));
        // 同行不同字段不冲突
        r = merger.resolve(
                List.of(
                        new PatchMerger.PatchSpec("可行性研究", null, null, "summary", "甲"),
                        new PatchMerger.PatchSpec("可行性研究", null, "9002", "version", "v2")),
                rows, NODES, Set.of(LOCKED_ID));
        assertThat(r.ok()).isTrue();
    }

    // ── 场景 8：G3 白名单闸 + 值校验 ──────────────────────────────────────────

    @Test
    @DisplayName("S8 白名单闸：pmOpinion/auditStatus 拒绝并给人话提示；白名单外通用提示")
    void whitelistGate() {
        PatchMerger.MergeResult r = merger.resolve(
                List.of(new PatchMerger.PatchSpec("可行性研究", null, null, "pmOpinion", "好")),
                rows, NODES, Set.of(LOCKED_ID));
        assertThat(r.ok()).isFalse();
        assertThat(r.rejections()).singleElement()
                .satisfies(s -> assertThat(s).contains("项目经理意见").contains("不能修改"));

        r = merger.resolve(
                List.of(new PatchMerger.PatchSpec("可行性研究", null, null, "isConfidential", "true")),
                rows, NODES, Set.of(LOCKED_ID));
        assertThat(r.ok()).isFalse();
        assertThat(r.rejections()).singleElement()
                .satisfies(s -> assertThat(s).contains("涉密").contains("主平台"));

        r = merger.resolve(
                List.of(new PatchMerger.PatchSpec("可行性研究", null, null, "auditStatus", "1")),
                rows, NODES, Set.of(LOCKED_ID));
        assertThat(r.ok()).isFalse();
        assertThat(r.rejections()).singleElement()
                .satisfies(s -> assertThat(s).contains("审批流程"));
    }

    @Test
    @DisplayName("S8b 值校验：notApplicable 越界 / providerTime 非法格式 / 空值 拒绝；字段名大小写容错")
    void valueCoercion() {
        assertThat(merger.resolve(
                        List.of(new PatchMerger.PatchSpec("可行性研究", null, null, "notApplicable", "5")),
                        rows, NODES, Set.of(LOCKED_ID)).ok())
                .isFalse();
        assertThat(merger.resolve(
                        List.of(new PatchMerger.PatchSpec("可行性研究", null, null, "providerTime", "2026/11/09")),
                        rows, NODES, Set.of(LOCKED_ID)).ok())
                .isFalse();
        assertThat(merger.resolve(
                        List.of(new PatchMerger.PatchSpec("可行性研究", null, null, "summary", "  ")),
                        rows, NODES, Set.of(LOCKED_ID)).ok())
                .isFalse();
        assertThat(merger.resolve(
                        List.of(new PatchMerger.PatchSpec("可行性研究", null, null, "providerTime", "2026-11-09")),
                        rows, NODES, Set.of(LOCKED_ID)).ok())
                .isTrue();
        // 字段名大小写容错（schema 正确时不触发，兜底 LLM 手滑）
        assertThat(merger.resolve(
                        List.of(new PatchMerger.PatchSpec("可行性研究", null, null, "NOTAPPLICABLE", "2")),
                        rows, NODES, Set.of(LOCKED_ID)).ok())
                .isTrue();
    }

    // ── 场景 9：全量组装 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("S9 全量组装：patch 应用 + 未改行原样 + 锁定行追加 + 流程/回显字段剔除")
    void assembleFull() {
        // 基准 = 两条（模拟 STAGE 视角不含锁定行），锁定行从主表追加
        List<ObjectNode> base = List.of(rows.get(0), rows.get(1));
        PatchMerger.MergeResult r = merger.resolve(
                List.of(
                        new PatchMerger.PatchSpec("项目建议书", "立项文件", null, "notApplicable", "2"),
                        new PatchMerger.PatchSpec("可行性研究", null, null, "summary", "初稿已过会")),
                base, NODES, Set.of(LOCKED_ID));
        assertThat(r.ok()).isTrue();

        List<ObjectNode> full = merger.assembleFullRows(base, r.resolved(), List.of(rows.get(2), rows.get(3)));

        assertThat(full).hasSize(4);
        // patch 应用
        assertThat(full.get(0).path("notApplicable").asInt()).isEqualTo(2);
        assertThat(full.get(1).path("summary").asText()).isEqualTo("初稿已过会");
        // 未改行原样
        assertThat(full.get(1).path("fileName").asText()).isEqualTo("kexing.pdf");
        // 锁定行/额外行追加且未修改
        assertThat(full.get(2).path("id").asText()).isEqualTo(LOCKED_ID);
        assertThat(full.get(2).path("category").asText()).isEqualTo("其他合同");
        assertThat(full.get(3).path("id").asText()).isEqualTo("9004");
        // 流程/回显字段全量剔除（由主后端处理）
        for (ObjectNode row : full) {
            assertThat(row.has("auditStatus")).isFalse();
            assertThat(row.has("pmOpinion")).isFalse();
            assertThat(row.has("uploader")).isFalse();
            assertThat(row.has("uploadTime")).isFalse();
            assertThat(row.has("fullUrl")).isFalse();
            assertThat(row.has("uploaderName")).isFalse();
        }
        // 业务字段保留
        assertThat(full.get(0).path("fileUrl").asText()).isEqualTo("/file/x.pdf");
        assertThat(full.get(0).path("configId").asInt()).isEqualTo(5);
        assertThat(full.get(0).path("nodeId").asText()).isEqualTo("7");
    }

    @Test
    @DisplayName("S9b 空批/空行防御：patch 列表为空直接拒绝")
    void emptyPatches() {
        assertThat(merger.resolve(List.of(), rows, NODES, Set.of()).ok()).isFalse();
        assertThat(merger.resolve(null, rows, NODES, Set.of()).ok()).isFalse();
    }
}
