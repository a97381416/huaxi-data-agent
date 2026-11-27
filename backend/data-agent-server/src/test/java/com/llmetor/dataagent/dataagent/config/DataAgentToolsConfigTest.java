package com.llmetor.dataagent.dataagent.config;

import com.llmetor.dataagent.dataagent.action.MyPermissionsTool;
import com.llmetor.dataagent.dataagent.action.fill.FillFileEntryTool;
import com.llmetor.dataagent.dataagent.action.fill.ListFillNodesTool;
import com.llmetor.dataagent.dataagent.action.fill.ListMyDraftsTool;
import com.llmetor.dataagent.dataagent.action.fill.ListMyProjectsTool;
import com.llmetor.dataagent.dataagent.action.fill.ListPhaseTreeTool;
import com.llmetor.dataagent.dataagent.action.fill.ListReviewOptionsTool;
import com.llmetor.dataagent.dataagent.action.fill.SubmitLedgerForReviewTool;
import com.llmetor.dataagent.dataagent.action.review.ApproveTaskTool;
import com.llmetor.dataagent.dataagent.action.review.GetReviewDetailTool;
import com.llmetor.dataagent.dataagent.action.review.ListCoauditOptionsTool;
import com.llmetor.dataagent.dataagent.action.review.ListReviewTasksTool;
import com.llmetor.dataagent.dataagent.action.review.PartialApproveLedgerTool;
import com.llmetor.dataagent.dataagent.action.review.RejectTaskTool;
import com.llmetor.dataagent.dataagent.action.task.CancelConfirmTool;
import com.llmetor.dataagent.dataagent.action.task.GetTaskDetailTool;
import com.llmetor.dataagent.dataagent.action.task.ListMyTasksTool;
import com.llmetor.dataagent.dataagent.action.task.ResubmitLedgerTool;
import com.llmetor.dataagent.dataagent.action.task.StageDraftTool;
import com.llmetor.dataagent.dataagent.glossary.GlossaryCatalog;
import com.llmetor.dataagent.dataagent.hitl.AskUserQuestionTool;
import com.llmetor.dataagent.dataagent.hitl.ConfirmPlanTool;
import com.llmetor.dataagent.dataagent.hitl.DecisionService;
import com.llmetor.dataagent.dataagent.tools.CalculateTool;
import com.llmetor.dataagent.dataagent.tools.DescribeTablesTool;
import com.llmetor.dataagent.dataagent.tools.ExecuteSqlTool;
import com.llmetor.dataagent.dataagent.tools.ListTablesTool;
import com.llmetor.dataagent.dataagent.tools.TimeTool;
import com.llmetor.dataagent.dataagent.tools.ValidateSqlTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 校验 4 大垂类子 Agent 工具组装配（均须接入 ask_user_question 与 confirm_plan）。
 */
class DataAgentToolsConfigTest {

    @Test
    void subAgentToolGroups_allContainAskUserQuestionAndConfirmPlan() {
        GlossaryCatalog glossaryCatalog = new GlossaryCatalog();

        DecisionService decisionService = mock(DecisionService.class);
        AskUserQuestionTool askTool = new AskUserQuestionTool(decisionService);
        ConfirmPlanTool planTool = new ConfirmPlanTool(decisionService);

        DataAgentToolsConfig config = new DataAgentToolsConfig();
        DataAgentToolsConfig.SubAgentToolGroups groups = config.subAgentToolGroups(
                glossaryCatalog,
                askTool,
                planTool,
                mock(ListTablesTool.class),
                mock(DescribeTablesTool.class),
                mock(ValidateSqlTool.class),
                mock(ExecuteSqlTool.class),
                mock(CalculateTool.class),
                mock(TimeTool.class),
                mock(MyPermissionsTool.class),
                mock(ListMyTasksTool.class),
                mock(GetTaskDetailTool.class),
                mock(StageDraftTool.class),
                mock(ResubmitLedgerTool.class),
                mock(CancelConfirmTool.class),
                mock(ListMyProjectsTool.class),
                mock(ListPhaseTreeTool.class),
                mock(ListFillNodesTool.class),
                mock(ListMyDraftsTool.class),
                mock(ListReviewOptionsTool.class),
                mock(FillFileEntryTool.class),
                mock(SubmitLedgerForReviewTool.class),
                mock(ListReviewTasksTool.class),
                mock(GetReviewDetailTool.class),
                mock(ListCoauditOptionsTool.class),
                mock(ApproveTaskTool.class),
                mock(RejectTaskTool.class),
                mock(PartialApproveLedgerTool.class));

        assertHasHitlTools("dataAnalysisTools", groups.dataAnalysisTools());
        assertHasHitlTools("declarantFillTools", groups.declarantFillTools());
        assertHasHitlTools("reviewerWorkflowTools", groups.reviewerWorkflowTools());
        assertHasHitlTools("declarantWorkflowTools", groups.declarantWorkflowTools());
    }

    private static void assertHasHitlTools(String groupName, List<ToolCallback> tools) {
        Set<String> names = tools.stream()
                .map(t -> t.getToolDefinition().name())
                .collect(Collectors.toSet());
        assertTrue(names.contains(AskUserQuestionTool.TOOL_NAME),
                groupName + " 缺少 " + AskUserQuestionTool.TOOL_NAME);
        assertTrue(names.contains(ConfirmPlanTool.TOOL_NAME),
                groupName + " 缺少 " + ConfirmPlanTool.TOOL_NAME);
    }
}
