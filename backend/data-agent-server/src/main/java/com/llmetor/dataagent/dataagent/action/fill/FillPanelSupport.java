package com.llmetor.dataagent.dataagent.action.fill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 资料填报校验支撑（M-E2）：承载 D1 三道闸的 2/3 两道，交互全走对话（卡片已下线）。
 *
 * <p>{@link #softWarnings}（W1 暂存软校验，允许半成品）与
 * {@link #validateForSubmit}（W2 首轮硬校验：全状态摘要必填、已取得必传文件、
 * 待上传预设行自动剔除、自定义行必须给定明确状态）。
 */
@Component
public class FillPanelSupport {

    private final ObjectMapper mapper = new ObjectMapper();

    public ObjectMapper mapper() {
        return mapper;
    }

    // ── 闸2：W1 暂存软校验（允许半成品，返回 warnings 由 agent 转达）───────────

    /** 软告警清单（不阻断暂存）：已取得缺文件 / 非待上传缺摘要 */
    public List<String> softWarnings(List<ObjectNode> rows) {
        List<String> warnings = new ArrayList<>();
        for (ObjectNode row : rows) {
            int status = row.path("notApplicable").asInt(-1);
            if (isPresetPending(row)) {
                continue;
            }
            String category = row.path("category").asText("");
            if (status == 0 && row.path("fileUrl").asText("").isBlank()) {
                warnings.add("「" + category + "」状态为已取得但尚未上传文件（提审前必须补传）。");
            }
            if (status >= 0 && row.path("summary").asText("").isBlank()) {
                warnings.add("「" + category + "」文件摘要未填（提审闸要求全状态必填）。");
            }
        }
        return warnings;
    }

    // ── 闸3：W2 提审硬校验（D1 口径，设计 §6.1/§6.2）─────────────────────────

    /** 校验结果：violations 非空则拒绝出计划 */
    public record SubmitValidation(List<String> violations, List<ObjectNode> toSubmit,
                                   List<String> skippedCategories) {
    }

    /**
     * 提交闸：对本轮将生效的行严格执行 §6.1 规则表。
     *
     * <ul>
     *   <li>待上传(-1) 预设行：自动剔除，进 skippedCategories（确认计划"未提交"区展示）；</li>
     *   <li>待上传(-1) 自定义行：必须给定明确状态才能提交（拒绝）；</li>
     *   <li>已取得(0)：必须已传文件 + 摘要必填（D1 全状态必填）；</li>
     *   <li>空缺(1)/不涉及(2)/其他(3)：摘要必填；</li>
     *   <li>锁定行（auditStatus=2）不参与校验与提交（原样保留由主后端处理）。</li>
     * </ul>
     */
    public SubmitValidation validateForSubmit(List<ObjectNode> rows) {
        List<String> violations = new ArrayList<>();
        List<ObjectNode> toSubmit = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (ObjectNode row : rows) {
            String category = row.path("category").asText("");
            int status = row.path("notApplicable").asInt(-1);
            boolean preset = row.path("source").asInt(0) == 0;
            if (row.path("auditStatus").asInt(0) == 2) {
                continue; // 锁定行：不进本轮 payload（组装时单独原样追加）
            }
            if (status == -1) {
                if (preset) {
                    skipped.add(category);
                    continue;
                }
                violations.add("自定义类别「" + category + "」仍为待上传状态，必须给定明确状态（已取得/空缺/不涉及/其他）才能提交。");
                continue;
            }
            String summary = row.path("summary").asText("").trim();
            if (summary.isEmpty()) {
                violations.add("「" + category + "」（" + FillLabels.notApplicable(status) + "）缺少文件摘要——摘要全状态必填。");
            }
            if (status == 0 && row.path("fileUrl").asText("").isBlank()) {
                violations.add("「" + category + "」状态为已取得但未上传文件——已取得必须附文件。");
            }
            toSubmit.add(row);
        }
        return new SubmitValidation(violations, toSubmit, skipped);
    }

    /** 待上传预设行（本轮不进 payload，对齐业务前端 collectEntries 过滤规则） */
    public static boolean isPresetPending(ObjectNode row) {
        return row.path("notApplicable").asInt(-1) == -1 && row.path("source").asInt(0) == 0;
    }
}
