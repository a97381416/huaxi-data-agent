package com.llmetor.dataagent.dataagent.action.task;

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

/**
 * 驳回任务暂存工具（M-E1，W1 写·低危：直接执行，不出确认卡）。
 *
 * <p>驳回后暂存修改（不推进流程）：LLM 只给增量 patch，工具层 PatchMerger
 * 拉现状→定位→闸校验→合并全量→PUT /doc/ledger/stage-draft（整单覆盖式暂存）。
 *
 * <p>攒批语义（D5）：patch 攒到用户明确说「提交」才 resubmit；期间 stageDraft
 * 随时可落库保险（暂存行成为后续重提的修改基准）。
 */
@Slf4j
@Component
public class StageDraftTool {

    static final String ACTION = "stageDraft";
    /** 权限点：资料填报（与主后端按钮权限对齐） */
    static final String PERMISSION = "doc:project:file:fill";

    private final FunctionPermissionChecker permissionChecker;
    private final RestActionClient restActionClient;
    private final TaskLedgerSupport support;
    private final PatchMerger patchMerger;
    private final ActionAuditLogger auditLogger;
    private final HitlToolHelper hitlHelper;

    public StageDraftTool(FunctionPermissionChecker permissionChecker,
                          RestActionClient restActionClient,
                          TaskLedgerSupport support,
                          PatchMerger patchMerger,
                          ActionAuditLogger auditLogger) {
        this(permissionChecker, restActionClient, support, patchMerger, auditLogger, null);
    }

    @Autowired
    public StageDraftTool(FunctionPermissionChecker permissionChecker,
                          RestActionClient restActionClient,
                          TaskLedgerSupport support,
                          PatchMerger patchMerger,
                          ActionAuditLogger auditLogger,
                          HitlToolHelper hitlHelper) {
        this.permissionChecker = permissionChecker;
        this.restActionClient = restActionClient;
        this.support = support;
        this.patchMerger = patchMerger;
        this.auditLogger = auditLogger;
        this.hitlHelper = hitlHelper;
    }

    @Tool(name = "stageDraft",
            description = "驳回任务【暂存修改】（低危，直接执行，不推进审批流）。入参为增量 patch 列表，" +
                    "系统自动拉取台账现状合并成整单暂存。patch 字段仅允许：notApplicable(-1待上传/0已取得/1空缺/2不涉及/3其他)、" +
                    "summary(摘要)、description(说明)、fileUrl+fileName(换文件)、version、provider、providerTime(yyyy-MM-dd)、docPeriod。" +
                    "适用：用户说「先存一下」「暂存」；或用户还在攒多批修改、明确说「提交」之前的落库保险。")
    public String stageDraft(
            @ToolParam(description = "任务ID（来自 listMyTasks，字符串原样传入）", required = true)
            String taskId,
            @ToolParam(description = "增量修改列表。每条：category(必填,文件类别名)、field(必填,白名单字段)、" +
                    "value(必填,新值字符串)、node(可选,节点名消歧)、entryId(可选,条目ID字符串消歧)", required = true)
            List<PatchMerger.PatchSpec> patches,
            @ToolParam(description = "用户身份标识，由框架自动注入真实值", required = false) String userId,
            @ToolParam(description = "访问令牌，由框架自动注入真实值", required = false) String token,
            @ToolParam(description = "租户标识，由框架自动注入真实值", required = false) String tenantId,
            @ToolParam(description = "会话标识，由框架自动注入真实值", required = false) String conversationId) {

        Long uid = TaskIds.parseUserId(userId);
        if (uid == null) {
            return "❌ 未获取到当前用户身份，请让用户重新登录后再试。";
        }
        Long tenant = TaskIds.parseLong(tenantId);

        try {
            if (hitlHelper != null) {
                String verifyDone = hitlHelper.checkVerifyOutcomeShortCircuit(ACTION);
                if (verifyDone != null) {
                    return verifyDone;
                }
            }
            String denied = permissionChecker.checkPermission(uid, PERMISSION);
            if (denied != null) {
                return denied;
            }
            TaskLedgerSupport.TaskRef task = support.findMyTask(taskId, token, tenant);
            support.requireSubmitterTodoTask(task);
            TaskLedgerSupport.LedgerView view = support.loadLedgerView(task.ledgerId(), token, tenant);
            support.requireRejected(view);

            PatchMerger.MergeResult merged = patchMerger.resolve(
                    patches, view.baseRows(), view.nodeNames(), view.lockedIds());
            if (!merged.ok()) {
                return merged.guidance();
            }
            List<ObjectNode> fullRows = patchMerger.assembleFullRows(
                    view.baseRows(), merged.resolved(), view.lockedRows());

            ObjectNode body = support.mapper().createObjectNode();
            body.put("ledgerId", task.ledgerId());
            body.put("taskId", task.taskId());
            ArrayNode arr = body.putArray("fileEntries");
            fullRows.forEach(arr::add);
            if (hitlHelper != null) {
                return hitlHelper.executeWithVerifyOutcome(
                        ACTION,
                        "驳回暂存草稿",
                        view.docNumber(),
                        uid,
                        conversationId,
                        () -> {
                            RestActionClient.ActionResult r = restActionClient.put(
                                    "/doc/ledger/stage-draft", body, token, tenant);
                            support.requireOk(r, "暂存修改");
                            return r;
                        },
                        r -> formatStageSuccess(uid, conversationId, task, view, merged, fullRows)
                );
            }
            RestActionClient.ActionResult r = restActionClient.put(
                    "/doc/ledger/stage-draft", body, token, tenant);
            support.requireOk(r, "暂存修改");
            return formatStageSuccess(uid, conversationId, task, view, merged, fullRows);
        } catch (TaskLedgerSupport.TaskFlowException e) {
            return e.getMessage();
        }
    }

    private String formatStageSuccess(Long uid, String conversationId, TaskLedgerSupport.TaskRef task,
                                      TaskLedgerSupport.LedgerView view, PatchMerger.MergeResult merged,
                                      List<ObjectNode> fullRows) {
        auditLogger.log(ActionAuditLogger.entry()
                .conversationId(conversationId).userId(uid)
                .tool("stageDraft").action("stage-draft")
                .businessObject(view.docNumber() + "/task:" + task.taskId())
                .success(true)
                .detail("patches=" + merged.resolved().size() + ", fullRows=" + fullRows.size())
                .build());

        StringBuilder sb = new StringBuilder("✅ 已暂存 ").append(merged.resolved().size()).append(" 处修改（未提交，流程未推进）：\n");
        for (PatchMerger.ResolvedPatch rp : merged.resolved()) {
            sb.append("- ").append(rp.category()).append(".").append(rp.field())
                    .append("：").append(rp.oldValue()).append(" → ").append(rp.newValue()).append('\n');
        }
        sb.append("台账 ").append(view.docNumber()).append(" 状态仍为「")
                .append(DocLabels.ledgerStatus(view.status()))
                .append("」。可继续攒批修改；用户明确说「提交」时再调用 resubmitLedger 发起重提确认。");
        return sb.toString();
    }
}
