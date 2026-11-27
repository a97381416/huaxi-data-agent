package com.llmetor.dataagent.dataagent.action.fill;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmetor.dataagent.dataagent.action.ActionAuditLogger;
import com.llmetor.dataagent.dataagent.action.FunctionPermissionChecker;
import com.llmetor.dataagent.dataagent.action.RestActionClient;
import com.llmetor.dataagent.dataagent.hitl.HitlToolHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 填报暂存工具（M-E2，W1 写·低危：直接执行，不出确认卡——设计 §3.2/§6.2 闸2）。
 *
 * <p>批量增量暂存，自动建/续草稿：LLM 从对话收增量 patch，工具层
 * 定位项目/阶段 → get-draft 检测（无则 {@code POST /doc/ledger/create} 建账，
 * 有则 {@code GET /doc/ledger/get}→merge→{@code PUT /doc/ledger/update} 全删重插）。
 *
 * <p>暂存允许半成品（对齐业务暂存语义），软校验返回 {@code warnings[]}（如"已取得但无文件"）
 * 由 agent 转达；硬校验留给提审闸（submitLedgerForReview 首轮）。
 * 待上传预设行不进 payload（对齐业务前端 collectEntries 过滤规则）。
 */
@Slf4j
@Component
public class FillFileEntryTool {

    static final String ACTION = "fillFileEntry";

    private final FunctionPermissionChecker permissionChecker;
    private final RestActionClient restActionClient;
    private final FillContextSupport contextSupport;
    private final FillPatchMerger patchMerger;
    private final FillPanelSupport panelSupport;
    private final ListFillNodesTool listFillNodesTool;
    private final ActionAuditLogger auditLogger;
    private final HitlToolHelper hitlHelper;

    public FillFileEntryTool(FunctionPermissionChecker permissionChecker,
                             RestActionClient restActionClient,
                             FillContextSupport contextSupport,
                             FillPatchMerger patchMerger,
                             FillPanelSupport panelSupport,
                             ListFillNodesTool listFillNodesTool,
                             ActionAuditLogger auditLogger) {
        this(permissionChecker, restActionClient, contextSupport, patchMerger,
                panelSupport, listFillNodesTool, auditLogger, null);
    }

    @Autowired
    public FillFileEntryTool(FunctionPermissionChecker permissionChecker,
                             RestActionClient restActionClient,
                             FillContextSupport contextSupport,
                             FillPatchMerger patchMerger,
                             FillPanelSupport panelSupport,
                             ListFillNodesTool listFillNodesTool,
                             ActionAuditLogger auditLogger,
                             HitlToolHelper hitlHelper) {
        this.permissionChecker = permissionChecker;
        this.restActionClient = restActionClient;
        this.contextSupport = contextSupport;
        this.patchMerger = patchMerger;
        this.panelSupport = panelSupport;
        this.listFillNodesTool = listFillNodesTool;
        this.auditLogger = auditLogger;
        this.hitlHelper = hitlHelper;
    }

    @Tool(name = "fillFileEntry",
            description = "资料填报【暂存】（低危，直接执行，台账保持草稿状态）。入参为增量 patch 列表，" +
                    "自动建/续草稿台账。patch 字段白名单：notApplicable(-1待上传/0已取得/1空缺/2不涉及/3其他)、" +
                    "summary(文件摘要)、description(备注)、fileUrl+fileName(文件)、attachmentUrl+attachmentName(附件)、" +
                    "provider(提供人)、providerTime(yyyy-MM-dd)、isConfidential(0/1)+visibleRoleIds(逗号分隔角色ID)。" +
                    "新增自定义类别行需先问用户，patch 加 custom=true 并指明 node。适用：用户在对话里说明修改内容、攒批暂存。")
    public String fillFileEntry(
            @ToolParam(description = "项目ID/名称/编码", required = true) String projectIdOrName,
            @ToolParam(description = "子阶段ID/编码/名称", required = true) String phaseIdOrNameOrCode,
            @ToolParam(description = "增量修改列表。每条：category(必填)、field(必填)、value(必填)、" +
                    "node(可选消歧/自定义行归属)、entryId(可选消歧)、custom(可选,新增自定义行)", required = true)
            List<FillPatchMerger.PatchSpec> patches,
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
            if (hitlHelper != null) {
                String verifyDone = hitlHelper.checkVerifyOutcomeShortCircuit(ACTION);
                if (verifyDone != null) {
                    return verifyDone;
                }
            }
            String denied = permissionChecker.checkPermission(uid, ListFillNodesTool.PERMISSION);
            if (denied != null) {
                return denied;
            }
            FillContextSupport.FillContext ctx = listFillNodesTool.loadContext(
                    projectIdOrName, phaseIdOrNameOrCode, token, tenant);
            FillContextSupport.LedgerView ledger = ctx.ledger();

            // 边界：非草稿台账阻断（与 listFillNodes 同口径）
            if (ctx.hasDraft() && ledger.status() != 0) {
                return "❌ 台账 " + ledger.docNumber() + " 当前状态为「" + FillLabels.ledgerStatus(ledger.status())
                        + "」，不能暂存修改。审批中/已通过请等待或只读浏览；已驳回/部分通过请走 declarant-workflow 重提闭环。";
            }

            List<ObjectNode> baseRows = ledger != null ? ledger.rows()
                    : FillContextSupport.seedRowsFromNodes(ctx.nodes());
            Map<String, String> nodeNames = FillContextSupport.nodeNamesOf(ctx.nodes());
            java.util.Set<String> lockedIds = new java.util.HashSet<>();
            if (ledger != null) {
                for (ObjectNode row : ledger.rows()) {
                    if (row.path("auditStatus").asInt(0) == 2) {
                        lockedIds.add(row.path("id").asText(""));
                    }
                }
            }

            FillPatchMerger.MergeResult merged = patchMerger.resolve(patches, baseRows, nodeNames, lockedIds);
            if (!merged.ok()) {
                return merged.guidance();
            }

            // 全量组装（剥离 id/流程字段，补 uploader）→ 过滤待上传预设行（业务同款规则）
            List<ObjectNode> fullRows = patchMerger.assembleFullRows(baseRows, merged);
            List<ObjectNode> payload = new java.util.ArrayList<>();
            for (ObjectNode row : fullRows) {
                if (FillPanelSupport.isPresetPending(row)) {
                    continue;
                }
                row.put("uploader", String.valueOf(uid));
                payload.add(row);
            }

            // 建/续草稿
            String bizId = ctx.hasDraft() ? ledger.docNumber()
                    : ctx.project().projectName() + "/" + ctx.phase().phaseName();
            if (hitlHelper != null) {
                return hitlHelper.executeWithVerifyOutcome(
                        ACTION,
                        ctx.hasDraft() ? "更新台账草稿" : "新建台账草稿",
                        bizId,
                        uid,
                        conversationId,
                        () -> saveDraftRemote(ctx, payload, token, tenant),
                        ledgerId -> formatFillSuccess(uid, conversationId, ctx, ledgerId, merged, payload, token, tenant)
                );
            }
            String ledgerId = saveDraftRemote(ctx, payload, token, tenant);
            return formatFillSuccess(uid, conversationId, ctx, ledgerId, merged, payload, token, tenant);
        } catch (FillContextSupport.TaskFlowException e) {
            return e.getMessage();
        }
    }

    private String saveDraftRemote(FillContextSupport.FillContext ctx, List<ObjectNode> payload,
                                   String token, Long tenant) {
        if (ctx.hasDraft()) {
            String ledgerId = ctx.draftLedgerId();
            ObjectNode body = supportBody(ctx, payload);
            body.put("id", Long.parseLong(ledgerId));
            RestActionClient.ActionResult r = restActionClient.put("/doc/ledger/update", body, token, tenant);
            contextSupport.requireOk(r, "更新台账草稿");
            return ledgerId;
        }
        ObjectNode body = supportBody(ctx, payload);
        RestActionClient.ActionResult r = restActionClient.post("/doc/ledger/create", body, token, tenant);
        contextSupport.requireOk(r, "创建台账草稿");
        return r.data().asText("");
    }

    private String formatFillSuccess(Long uid, String conversationId, FillContextSupport.FillContext ctx,
                                     String ledgerId, FillPatchMerger.MergeResult merged,
                                     List<ObjectNode> payload, String token, Long tenant) {
        // 重拉最新草稿（批次号口径一致）
        FillContextSupport.LedgerView fresh = contextSupport.loadLedgerDetail(ledgerId, token, tenant);
        String docNumber = fresh.docNumber();

        auditLogger.log(ActionAuditLogger.entry()
                .conversationId(conversationId).userId(uid)
                .tool("fillFileEntry").action("stage-draft")
                .businessObject(docNumber)
                .success(true)
                .detail("changes=" + merged.changeCount() + ", rows=" + payload.size()
                        + (ctx.hasDraft() ? ", update" : ", create"))
                .build());

        StringBuilder sb = new StringBuilder("✅ 已暂存 ").append(merged.changeCount())
                .append(" 项修改（台账仍为草稿，未提审）：\n");
        for (FillPatchMerger.ResolvedPatch rp : merged.resolved()) {
            sb.append("- ").append(rp.node().isBlank() ? "" : "[" + rp.node() + "] ")
                    .append(rp.category()).append(" · ").append(FillLabels.fieldLabel(rp.field()))
                    .append("：").append(rp.oldValue()).append(" → ").append(rp.newValue()).append('\n');
        }
        for (FillPatchMerger.AddedRow ar : merged.added()) {
            sb.append("- 新增自定义行 [").append(ar.node()).append("] ").append(ar.category())
                    .append(" · ").append(FillLabels.fieldLabel(ar.field()))
                    .append("：").append(ar.newValue()).append('\n');
        }
        sb.append("- 台账批次：**").append(docNumber.isEmpty() ? ledgerId : docNumber)
                .append("**（草稿").append(ctx.hasDraft() ? "续填" : "新建").append("）\n");

        // 闸2 软校验：半成品允许，warnings 转达
        List<String> warnings = panelSupport.softWarnings(payload);
        if (!warnings.isEmpty()) {
            sb.append("\n⚠️ 暂存成功，但有以下提醒（提审前需补齐）：\n");
            for (String w : warnings) {
                sb.append("- ").append(w).append('\n');
            }
        }
        sb.append("\n台账仍为草稿，可继续攒批修改；用户明确说「提交审核」时再走提审确认流程。");
        return sb.toString();
    }

    /** create/update 请求体（phaseId/projectId/fileEntries，对齐业务前端 payload） */
    private ObjectNode supportBody(FillContextSupport.FillContext ctx, List<ObjectNode> payload) {
        ObjectNode body = contextSupport.mapper().createObjectNode();
        body.put("phaseId", FillContextSupport.parseLong(ctx.phase().phaseId()));
        body.put("projectId", FillContextSupport.parseLong(ctx.project().projectId()));
        body.put("isSpecial", 0);
        ArrayNode arr = body.putArray("fileEntries");
        payload.forEach(arr::add);
        return body;
    }
}
