package com.llmetor.dataagent.dataagent.config;

import com.llmetor.dataagent.dataagent.glossary.GlossaryCatalog;
import com.llmetor.dataagent.dataagent.tools.LookupGlossaryTool;
import com.llmetor.dataagent.dataagent.tools.TodoWriteTool;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * data-analysis 领域工具集装配——M7 恢复三层装配（docs/01 §1.1）：
 * <ul>
 *   <li><b>常驻层</b> {@code dataResidentTools}：高频/全局必需——lookupGlossary
 *       （data-analysis SOP 第一步）+ todoWrite（计划跟踪）。随每请求工具清单下发</li>
 *   <li><b>延迟层</b> {@code dataDeferredToolCandidates}：低频大批量——SQL 六工具
 *       （listTables/describeTables/validateSql/executeSql/calculate/currentTime）。
 *       进 DeferredToolRegistry，由 LLM 经 tool_search 按需发现，首轮不占上下文</li>
 *   <li><b>条件层</b>（DodoAgent.availableTools 内）：Skill/analyzeFile/Tavily——
 *       场景触发（有技能/有文件/开联网）才注入</li>
 * </ul>
 *
 * <p>lookupGlossary 编程式注册：description 需运行时拼入全部术语清单（YAML 加载），
 * 不能走 @Tool 注解的编译时常量，因此单独构建。
 */
@Configuration
public class DataAgentToolsConfig {

    /** 常驻层：每请求工具清单必带 */
    @Bean
    public List<ToolCallback> dataResidentTools(GlossaryCatalog glossaryCatalog) {
        List<ToolCallback> tools = new ArrayList<>();
        tools.add(LookupGlossaryTool.builder().terms(glossaryCatalog.listAll()).build());
        tools.add(TodoWriteTool.create());
        return List.copyOf(tools);
    }

    /** 延迟层候选（包装类型，避免与 {@code List<ToolCallback>} 常驻 bean 撞 Spring AI 的 toolCallbackResolver 单 bean 约束） */
    public record DeferredToolCandidates(List<ToolCallback> tools) {
    }

    /** 延迟层候选：SQL 六工具 + M-E0 写通道 demo 工具（myPermissions）+ M-E1 任务处理组（5 个）+ M-E2 资料填报组（7 个，含草稿盘点 listMyDrafts）+ M-E3 审核处理组（6 个），经 tool_search 发现 */
    @Bean
    public DeferredToolCandidates dataDeferredToolCandidates(
            com.llmetor.dataagent.dataagent.tools.ListTablesTool listTablesTool,
            com.llmetor.dataagent.dataagent.tools.DescribeTablesTool describeTablesTool,
            com.llmetor.dataagent.dataagent.tools.ValidateSqlTool validateSqlTool,
            com.llmetor.dataagent.dataagent.tools.ExecuteSqlTool executeSqlTool,
            com.llmetor.dataagent.dataagent.tools.CalculateTool calculateTool,
            com.llmetor.dataagent.dataagent.tools.TimeTool timeTool,
            com.llmetor.dataagent.dataagent.action.MyPermissionsTool myPermissionsTool,
            com.llmetor.dataagent.dataagent.action.task.ListMyTasksTool listMyTasksTool,
            com.llmetor.dataagent.dataagent.action.task.GetTaskDetailTool getTaskDetailTool,
            com.llmetor.dataagent.dataagent.action.task.StageDraftTool stageDraftTool,
            com.llmetor.dataagent.dataagent.action.task.ResubmitLedgerTool resubmitLedgerTool,
            com.llmetor.dataagent.dataagent.action.task.CancelConfirmTool cancelConfirmTool,
            com.llmetor.dataagent.dataagent.action.fill.ListMyProjectsTool listMyProjectsTool,
            com.llmetor.dataagent.dataagent.action.fill.ListPhaseTreeTool listPhaseTreeTool,
            com.llmetor.dataagent.dataagent.action.fill.ListFillNodesTool listFillNodesTool,
            com.llmetor.dataagent.dataagent.action.fill.ListMyDraftsTool listMyDraftsTool,
            com.llmetor.dataagent.dataagent.action.fill.ListReviewOptionsTool listReviewOptionsTool,
            com.llmetor.dataagent.dataagent.action.fill.FillFileEntryTool fillFileEntryTool,
            com.llmetor.dataagent.dataagent.action.fill.SubmitLedgerForReviewTool submitLedgerForReviewTool,
            com.llmetor.dataagent.dataagent.action.review.ListReviewTasksTool listReviewTasksTool,
            com.llmetor.dataagent.dataagent.action.review.GetReviewDetailTool getReviewDetailTool,
            com.llmetor.dataagent.dataagent.action.review.ListCoauditOptionsTool listCoauditOptionsTool,
            com.llmetor.dataagent.dataagent.action.review.ApproveTaskTool approveTaskTool,
            com.llmetor.dataagent.dataagent.action.review.RejectTaskTool rejectTaskTool,
            com.llmetor.dataagent.dataagent.action.review.PartialApproveLedgerTool partialApproveLedgerTool) {
        return new DeferredToolCandidates(List.copyOf(Arrays.asList(ToolCallbacks.from(
                listTablesTool, describeTablesTool, validateSqlTool,
                executeSqlTool, calculateTool, timeTool, myPermissionsTool,
                listMyTasksTool, getTaskDetailTool, stageDraftTool, resubmitLedgerTool, cancelConfirmTool,
                listMyProjectsTool, listPhaseTreeTool, listFillNodesTool, listMyDraftsTool,
                listReviewOptionsTool, fillFileEntryTool, submitLedgerForReviewTool,
                listReviewTasksTool, getReviewDetailTool, listCoauditOptionsTool,
                approveTaskTool, rejectTaskTool, partialApproveLedgerTool))));
    }
}
