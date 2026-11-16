package com.llmetor.dataagent.dataagent.action.fill;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmetor.dataagent.dataagent.action.ActionAuditLogger;
import com.llmetor.dataagent.dataagent.action.FunctionPermissionChecker;
import com.llmetor.dataagent.dataagent.action.RestActionClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 填报清单合并视图工具（M-E2，读）：资料填报「拉清单」必须执行（交互全走对话）。
 *
 * <p><b>节点确认门槛</b>：不带 {@code nodeNameOrId} → 只列节点清单，追问用户确认填报节点；
 * 带 {@code nodeNameOrId} → 解析节点后返回聚焦该节点的填报清单，并记录会话节点选择
 * （{@link FillNodeSelectionCache}，供暂存后保持聚焦）。
 *
 * <p>聚合四个主后端接口：resolve-phase/get-nodes + get-draft（草稿唯一检测）+
 * get（草稿台账合并视图）。合并出「节点 × 预设类别 × 已填值 × 草稿检测」：
 * <ul>
 *   <li>无草稿 → 合并基准 = 预设种子行（对齐业务前端 seed），进入全新填报；</li>
 *   <li>有草稿 → 基准 = 草稿行，标「续填草稿(批次DOC-xxx)」；</li>
 *   <li>台账状态 ≠ 草稿 → 走边界分支：审批中阻断 / 已通过只读 / 已驳回·部分通过引导 M-E1。</li>
 * </ul>
 */
@Slf4j
@Component
public class ListFillNodesTool {

    /** 权限点：资料填报（与主后端按钮权限对齐，同 M-E1） */
    static final String PERMISSION = "doc:project:file:fill";

    private final FunctionPermissionChecker permissionChecker;
    private final RestActionClient restActionClient;
    private final FillContextSupport contextSupport;
    private final FillNodeSelectionCache nodeSelectionCache;
    private final ActionAuditLogger auditLogger;

    public ListFillNodesTool(FunctionPermissionChecker permissionChecker,
                             RestActionClient restActionClient,
                             FillContextSupport contextSupport,
                             FillNodeSelectionCache nodeSelectionCache,
                             ActionAuditLogger auditLogger) {
        this.permissionChecker = permissionChecker;
        this.restActionClient = restActionClient;
        this.contextSupport = contextSupport;
        this.nodeSelectionCache = nodeSelectionCache;
        this.auditLogger = auditLogger;
    }

    @Tool(name = "listFillNodes",
            description = "拉取某项目某子阶段的资料填报清单（合并视图：文件节点×文件类别×已填值×草稿检测）。" +
                    "两段用法：不带 nodeNameOrId → 返回填报总览+节点清单，让用户确认要填报的节点；" +
                    "带 nodeNameOrId（用户说「填报X节点」后传入）→ 返回聚焦该节点的填报清单。" +
                    "phaseIdOrNameOrCode 可为阶段ID/编码/名称（来自 listPhaseTree 或用户点名）；也可用于查看当前填报进度。")
    public String listFillNodes(
            @ToolParam(description = "项目ID/名称/编码", required = true) String projectIdOrName,
            @ToolParam(description = "子阶段ID/编码/名称", required = true) String phaseIdOrNameOrCode,
            @ToolParam(description = "填报节点ID/名称/编码；不传=只出总览并让用户先确认填报节点",
                    required = false) String nodeNameOrId,
            @ToolParam(description = "用户身份标识，由框架自动注入真实值", required = false) String userId,
            @ToolParam(description = "访问令牌，由框架自动注入真实值", required = false) String token,
            @ToolParam(description = "租户标识，由框架自动注入真实值", required = false) String tenantId,
            @ToolParam(description = "会话标识，由框架自动注入真实值", required = false) String conversationId) {

        Long uid = FillContextSupport.parseUserId(userId);
        if (uid == null) {
            return "❌ 未获取到当前用户身份，请让用户重新登录后再试。";
        }
        Long tenant = FillContextSupport.parseLong(tenantId);

        try {
            String denied = permissionChecker.checkPermission(uid, PERMISSION);
            if (denied != null) {
                return denied;
            }
            FillContextSupport.FillContext ctx = loadContext(projectIdOrName, phaseIdOrNameOrCode, token, tenant);
            FillContextSupport.LedgerView ledger = ctx.ledger();
            int status = ledger == null ? -1 : ledger.status();

            auditLogger.log(ActionAuditLogger.entry()
                    .conversationId(conversationId).userId(uid)
                    .tool("listFillNodes").action("list")
                    .businessObject(ctx.project().projectName() + "/" + ctx.phase().phaseName())
                    .success(true)
                    .detail("draftLedgerId=" + (ctx.hasDraft() ? ctx.draftLedgerId() : "无") + ", status=" + status)
                    .build());

            // ── 边界分支：非草稿台账（设计 §8 场景 2/3）──────────────────────────
            if (ledger != null && status != 0) {
                return blockedMessage(ctx, status);
            }

            List<ObjectNode> rows = ledger != null ? ledger.rows()
                    : FillContextSupport.seedRowsFromNodes(ctx.nodes());
            String ledgerId = ctx.hasDraft() ? ctx.draftLedgerId() : null;
            String docNumber = ledger != null ? ledger.docNumber() : "";

            // ── 节点确认门槛：不带 nodeNameOrId → 只出总览+节点清单，追问用户填哪个 ──
            boolean nodeConfirmed = nodeNameOrId != null && !nodeNameOrId.isBlank();
            ObjectNode selectedNode = nodeConfirmed
                    ? FillContextSupport.resolveNode(ctx.nodes(), nodeNameOrId) : null;
            String selNodeId = selectedNode == null ? null : selectedNode.path("nodeId").asText("");
            String selNodeName = selectedNode == null ? "" : selectedNode.path("nodeName").asText("");
            if (selectedNode != null) {
                nodeSelectionCache.put(conversationId, ctx.project().projectId(),
                        ctx.phase().phaseId(), selNodeId, selNodeName);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## 资料填报清单 · ").append(ctx.phase().phaseName());
            if (ctx.hasDraft()) {
                sb.append("\n- 📝 检测到草稿台账：**续填草稿（批次 ").append(docNumber).append("）**，修改将合并进现有草稿");
            } else {
                sb.append("\n- 本子阶段暂无草稿，进入全新填报（首次暂存自动建账）");
            }
            sb.append("\n- 项目：").append(ctx.project().projectName())
                    .append("（节点 ").append(ctx.nodes().size()).append(" 个，类别 ")
                    .append(rows.size()).append(" 项）\n");
            sb.append("- 状态统计：").append(statsLine(rows)).append('\n');
            if (nodeConfirmed) {
                sb.append("- 当前填报节点：**").append(selNodeName)
                        .append("**（面板已聚焦该节点；换节点直接说「填报X节点」）\n");
            }
            sb.append("\n### 节点清单\n");
            for (ObjectNode node : ctx.nodes()) {
                sb.append(nodeProgressLine(node, rows));
            }
            if (nodeConfirmed) {
                sb.append("\n用户可逐类填报：状态（已取得/空缺/不涉及/其他）+ 文件 + 摘要；")
                        .append("在对话里逐项说明即可，攒批后暂存（fillFileEntry）；明确说「提交审核」才发起提审。");
            } else {
                sb.append("\n请用户确认要填报的节点（说「填报X节点」即可），")
                        .append("确认后带 nodeNameOrId 重新调用本工具聚焦该节点。");
            }

            return sb.toString();
        } catch (FillContextSupport.TaskFlowException e) {
            return e.getMessage();
        }
    }

    /** 加载 合并上下文（项目/阶段/节点/草稿/台账） */
    FillContextSupport.FillContext loadContext(String projectIdOrName, String phaseIdOrNameOrCode,
                                               String token, Long tenantId) {
        FillContextSupport.ProjectRef project = contextSupport.findProject(projectIdOrName, token, tenantId);
        FillContextSupport.PhaseTarget phase = contextSupport.resolvePhase(project, phaseIdOrNameOrCode, token, tenantId);
        List<ObjectNode> nodes = contextSupport.loadNodeConfigs(phase.phaseId(), token, tenantId);
        String draftLedgerId = contextSupport.findDraftLedgerId(phase.phaseId(), project.projectId(), token, tenantId);
        FillContextSupport.LedgerView ledger = null;
        if (draftLedgerId != null) {
            ledger = contextSupport.loadLedgerDetail(draftLedgerId, token, tenantId);
        }
        return new FillContextSupport.FillContext(project, phase, nodes, draftLedgerId, ledger);
    }

    /** 台账状态 ≠ 草稿的边界话术（阻断/只读/引导 M-E1） */
    private String blockedMessage(FillContextSupport.FillContext ctx, int status) {
        FillContextSupport.LedgerView ledger = ctx.ledger();
        StringBuilder sb = new StringBuilder("⚠️ 台账 ").append(ledger.docNumber())
                .append("（").append(ctx.phase().phaseName()).append("）当前状态为「")
                .append(FillLabels.ledgerStatus(status)).append("」，");
        switch (status) {
            case 1 -> sb.append("正在审批中，不能填报或修改。可关注审批进度或到待办里查看；")
                    .append("审批完成后（通过/驳回）再继续。");
            case 2 -> sb.append("已全部通过，进入只读浏览模式：\n").append(readonlyTable(ledger));
            case 3, 4 -> sb.append("处于驳回/部分通过状态，走「驳回后修改→重提」闭环（declarant-workflow 技能），")
                    .append("不要在本填报入口重复操作，避免双入口写冲突。可引导用户：listMyTasks → getTaskDetail → stageDraft → resubmitLedger。")
                    .append("\n").append(readonlyTable(ledger));
            default -> sb.append("暂不支持在本入口操作。");
        }
        return sb.toString();
    }

    /** 节点行进度（done 口径同面板：已取得须文件+摘要；空缺/不涉及/其他须摘要；已通过锁定行算已办） */
    private static String nodeProgressLine(ObjectNode node, List<ObjectNode> rows) {
        String nodeId = node.path("nodeId").asText("");
        int total = 0;
        int done = 0;
        for (ObjectNode row : rows) {
            if (!nodeId.equals(row.path("nodeId").asText(""))) {
                continue;
            }
            total++;
            if (row.path("auditStatus").asInt(0) == 2) {
                done++;
                continue;
            }
            int st = row.path("notApplicable").asInt(-1);
            boolean d;
            if (st == 0) {
                d = !row.path("fileName").asText("").isBlank() && !row.path("summary").asText("").isBlank();
            } else if (st >= 1) {
                d = !row.path("summary").asText("").isBlank();
            } else {
                d = false;
            }
            if (d) {
                done++;
            }
        }
        return "- " + node.path("nodeName").asText("")
                + (node.path("required").asBoolean(false) ? "（必填节点）" : "（选填节点）")
                + "：" + node.path("presetCategories").size() + " 个预设类别，已办 " + done + "/" + total + "\n";
    }

    private String readonlyTable(FillContextSupport.LedgerView ledger) {
        StringBuilder sb = new StringBuilder("\n| 节点 | 类别 | 状态 | 文件 | 摘要 | 审核状态 |\n|---|---|---|---|---|---|\n");
        for (ObjectNode row : ledger.rows()) {
            sb.append("| ").append(ledger.nodeNames().getOrDefault(row.path("nodeId").asText(""), "—"))
                    .append(" | ").append(row.path("category").asText(""))
                    .append(" | ").append(FillLabels.notApplicable(FillPatchMerger.intOrNull(row.path("notApplicable"))))
                    .append(" | ").append(orDash(row.path("fileName").asText("")))
                    .append(" | ").append(orDash(row.path("summary").asText("")))
                    .append(" | ").append(FillLabels.auditStatus(FillPatchMerger.intOrNull(row.path("auditStatus"))))
                    .append(" |\n");
        }
        return sb.toString();
    }

    /** 四态统计行（已取得/空缺/不涉及/待上传+其他） */
    static String statsLine(List<ObjectNode> rows) {
        int obtained = 0;
        int vacant = 0;
        int notInvolved = 0;
        int other = 0;
        int pending = 0;
        for (ObjectNode row : rows) {
            switch (row.path("notApplicable").asInt(-1)) {
                case 0 -> obtained++;
                case 1 -> vacant++;
                case 2 -> notInvolved++;
                case 3 -> other++;
                default -> pending++;
            }
        }
        List<String> parts = new ArrayList<>();
        parts.add("✅已取得 " + obtained);
        parts.add("⬜空缺 " + vacant);
        parts.add("⊘不涉及 " + notInvolved);
        if (other > 0) {
            parts.add("③其他 " + other);
        }
        parts.add("⏫待上传 " + pending);
        return String.join("，", parts);
    }

    private static String orDash(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }
}
