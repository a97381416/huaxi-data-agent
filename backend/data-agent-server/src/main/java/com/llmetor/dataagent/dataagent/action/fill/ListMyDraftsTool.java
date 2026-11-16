package com.llmetor.dataagent.dataagent.action.fill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmetor.dataagent.dataagent.action.ActionAuditLogger;
import com.llmetor.dataagent.dataagent.action.RestActionClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 我的草稿盘点工具（读）：跨子阶段查看资料填报草稿。
 *
 * <p>此前只有 {@link ListFillNodesTool}（单子阶段合并视图）——用户必须先知道子阶段名
 * 才能看到草稿；"我名下哪些子阶段有草稿"无处可查（/doc/ledger/page 的 phaseId 是
 * 必填等值条件，天然只支持单阶段）。本工具补齐盘点维度：
 *
 * <p>总览：项目（可选过滤）→ 阶段树扫描 → 逐子阶段 get-draft（草稿唯一检测）→
 * 命中即 loadLedgerDetail 取批次号/行数/状态统计/创建时间。子阶段量级为个位~二十，
 * 每阶段一次轻量 get-draft 调用可接受。
 *
 * <p>详情：传 phaseIdOrNameOrCode 直接展开该子阶段草稿的逐行内容
 * （节点/类别/状态/文件/摘要），复用 {@link FillContextSupport#loadLedgerDetail}。
 * 改草稿走 listFillNodes + fillFileEntry，本工具只读。
 */
@Slf4j
@Component
public class ListMyDraftsTool {

    private final RestActionClient restActionClient;
    private final ActionAuditLogger auditLogger;
    private final FillContextSupport contextSupport;

    public ListMyDraftsTool(RestActionClient restActionClient,
                            ActionAuditLogger auditLogger,
                            FillContextSupport contextSupport) {
        this.restActionClient = restActionClient;
        this.auditLogger = auditLogger;
        this.contextSupport = contextSupport;
    }

    @Tool(name = "listMyDrafts",
            description = "查看我的资料填报草稿：哪些项目/大阶段/子阶段下有未提交草稿，各草稿的批次号、" +
                    "内容行数、四态统计、创建时间。不传参数=盘点名下全部进行中项目；传 projectIdOrName=只看该项目；" +
                    "再传 phaseIdOrNameOrCode=展开该子阶段草稿的逐行内容（节点/类别/状态/文件/摘要）。" +
                    "要修改草稿内容时，按返回的子阶段调 listFillNodes 进入续填。")
    public String listMyDrafts(
            @ToolParam(description = "可选：项目ID/名称/编码（不传=名下全部进行中项目）", required = false)
            String projectIdOrName,
            @ToolParam(description = "可选：子阶段ID/编码/名称（传入时展开该子阶段草稿的逐行内容详情）", required = false)
            String phaseIdOrNameOrCode,
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
            if (phaseIdOrNameOrCode != null && !phaseIdOrNameOrCode.isBlank()) {
                if (projectIdOrName == null || projectIdOrName.isBlank()) {
                    return "❌ 查看单个子阶段草稿详情时必须同时指定项目（projectIdOrName）——" +
                            "同名子阶段可能存在于多个项目。可先不带参数调用本工具盘点全部草稿。";
                }
                return phaseDraftDetail(projectIdOrName.trim(), phaseIdOrNameOrCode.trim(),
                        uid, token, tenant, conversationId);
            }
            return overview(isBlank(projectIdOrName) ? null : projectIdOrName.trim(),
                    uid, token, tenant, conversationId);
        } catch (FillContextSupport.TaskFlowException e) {
            return e.getMessage();
        }
    }

    // ── 总览：跨子阶段盘点 ────────────────────────────────────────────────────

    private String overview(String projectIdOrName, Long uid, String token, Long tenant,
                            String conversationId) throws FillContextSupport.TaskFlowException {
        List<FillContextSupport.ProjectRef> projects = new ArrayList<>();
        if (projectIdOrName != null) {
            projects.add(contextSupport.findProject(projectIdOrName, token, tenant));
        } else {
            RestActionClient.ActionResult r = restActionClient.get(
                    "/doc/workbench/my-projects", FillContextSupport.query("pageSize", "99"), token, tenant);
            requireOk(r, "查询我的项目");
            for (JsonNode p : r.data().path("list")) {
                String pid = p.path("projectId").asText("");
                if (!pid.isEmpty()) {
                    projects.add(new FillContextSupport.ProjectRef(pid, p.path("projectName").asText(""),
                            p.path("projectCode").asText(""), p.path("type").asText(""), null, p.deepCopy()));
                }
            }
        }
        if (projects.isEmpty()) {
            return "❌ 您名下没有进行中的项目，暂无资料填报草稿。";
        }

        List<DraftEntry> drafts = new ArrayList<>();
        for (FillContextSupport.ProjectRef project : projects) {
            RestActionClient.ActionResult detail = restActionClient.get("/doc/project/detail",
                    FillContextSupport.query("projectId", project.projectId()), token, tenant);
            requireOk(detail, "查询项目阶段树");
            for (JsonNode major : detail.data().path("majorPhases")) {
                String majorId = major.path("phaseId").asText("");
                String majorName = major.path("phaseName").asText("");
                RestActionClient.ActionResult cr = restActionClient.get("/doc/project/phase-children",
                        FillContextSupport.query("projectId", project.projectId(), "majorPhaseId", majorId),
                        token, tenant);
                if (!cr.success()) {
                    continue;
                }
                for (JsonNode sub : cr.data().path("list")) {
                    String phaseId = sub.path("id").asText("");
                    if (phaseId.isEmpty()) {
                        continue;
                    }
                    String draftLedgerId = contextSupport.findDraftLedgerId(
                            phaseId, project.projectId(), token, tenant);
                    if (draftLedgerId == null) {
                        continue;
                    }
                    FillContextSupport.LedgerView ledger =
                            contextSupport.loadLedgerDetail(draftLedgerId, token, tenant);
                    drafts.add(new DraftEntry(project.projectName(), majorName,
                            sub.path("name").asText(""), ledger));
                }
            }
        }

        auditLogger.log(ActionAuditLogger.entry()
                .conversationId(conversationId).userId(uid)
                .tool("listMyDrafts").action("list")
                .businessObject("drafts:" + drafts.size()
                        + (projectIdOrName != null ? "/project:" + projectIdOrName : ""))
                .success(true)
                .build());

        if (drafts.isEmpty()) {
            return "📝 您名下" + (projectIdOrName != null ? "该项目" : "所有项目") + "暂无资料填报草稿。\n" +
                    "草稿在首次暂存后生成：说「填报 <项目> 的 <子阶段>」进入 listFillNodes 开始填报。";
        }
        StringBuilder sb = new StringBuilder("## 我的资料填报草稿（").append(drafts.size()).append(" 份）\n");
        String lastProject = null;
        for (DraftEntry d : drafts) {
            if (!d.projectName().equals(lastProject)) {
                lastProject = d.projectName();
                sb.append("\n### 项目：").append(lastProject).append('\n');
            }
            sb.append("- **").append(d.majorName()).append(" / ").append(d.subName()).append("**：批次 ")
                    .append(d.ledger().docNumber()).append("（").append(d.ledger().rows().size())
                    .append(" 行：").append(ListFillNodesTool.statsLine(d.ledger().rows()))
                    .append("；创建 ").append(timeOf(d.ledger())).append("）\n");
        }
        sb.append("\n查看某份草稿的具体内容：说「看 <项目> 的 <子阶段> 草稿」或带上 projectIdOrName + phaseIdOrNameOrCode 再调本工具；")
                .append("要继续填报/修改：对该子阶段调 listFillNodes。");
        return sb.toString();
    }

    // ── 详情：单子阶段草稿逐行展开 ────────────────────────────────────────────

    private String phaseDraftDetail(String projectIdOrName, String phaseIdOrNameOrCode, Long uid,
                                    String token, Long tenant, String conversationId)
            throws FillContextSupport.TaskFlowException {
        FillContextSupport.ProjectRef project = contextSupport.findProject(projectIdOrName, token, tenant);
        FillContextSupport.PhaseTarget phase =
                contextSupport.resolvePhase(project, phaseIdOrNameOrCode, token, tenant);
        String draftLedgerId = contextSupport.findDraftLedgerId(
                phase.phaseId(), project.projectId(), token, tenant);
        if (draftLedgerId == null) {
            return "📝 项目「" + project.projectName() + "」的子阶段「" + phase.phaseName()
                    + "」暂无草稿。说「填报 " + project.projectName() + " 的 " + phase.phaseName()
                    + "」进入 listFillNodes 开始填报。";
        }
        FillContextSupport.LedgerView ledger = contextSupport.loadLedgerDetail(draftLedgerId, token, tenant);

        auditLogger.log(ActionAuditLogger.entry()
                .conversationId(conversationId).userId(uid)
                .tool("listMyDrafts").action("detail")
                .businessObject("project:" + project.projectId() + "(" + project.projectName()
                        + ")/phase:" + phase.phaseId() + "(" + phase.phaseName() + ")/" + ledger.docNumber())
                .success(true)
                .build());

        StringBuilder sb = new StringBuilder("## 草稿详情：")
                .append(ledger.docNumber()).append('\n');
        sb.append("- 项目：").append(project.projectName())
                .append("（").append(phase.phaseName()).append("）\n");
        sb.append("- 创建：").append(timeOf(ledger))
                .append("；").append(ledger.rows().size()).append(" 行：")
                .append(ListFillNodesTool.statsLine(ledger.rows())).append('\n');
        sb.append("\n| 节点 | 类别 | 状态 | 文件 | 摘要 |\n|---|---|---|---|---|\n");
        int shown = 0;
        for (ObjectNode row : ledger.rows()) {
            if (shown++ >= 40) {
                sb.append("| … | … | … | … | 共 ").append(ledger.rows().size())
                        .append(" 行，其余略 |\n");
                break;
            }
            sb.append("| ").append(ledger.nodeNames().getOrDefault(row.path("nodeId").asText(""), "—"))
                    .append(" | ").append(row.path("category").asText(""))
                    .append(" | ").append(FillLabels.notApplicable(FillPatchMerger.intOrNull(row.path("notApplicable"))))
                    .append(" | ").append(orDash(row.path("fileName").asText("")))
                    .append(" | ").append(orDash(row.path("summary").asText("")))
                    .append(" |\n");
        }
        sb.append("\n要继续填报/修改该草稿：对该子阶段调 listFillNodes（会自动续填本批次）。");
        return sb.toString();
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────────

    /** 草稿盘点条目（项目名/大阶段/子阶段 + 台账合并视图） */
    private record DraftEntry(String projectName, String majorName, String subName,
                              FillContextSupport.LedgerView ledger) {
    }

    /** 创建时间展示（ISO/带 T 的时间串截到分钟，空值兜底 —） */
    private static String timeOf(FillContextSupport.LedgerView ledger) {
        String t = ledger.header().path("createTime").asText("");
        if (t.isBlank()) {
            return "—";
        }
        t = t.replace('T', ' ');
        return t.length() > 16 ? t.substring(0, 16) : t;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private void requireOk(RestActionClient.ActionResult r, String context) {
        if (!r.success()) {
            throw new FillContextSupport.TaskFlowException("❌ " + context + "失败：" + r.msg());
        }
    }
}
