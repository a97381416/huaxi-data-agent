package com.llmetor.miniagentx.agent;

import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;

import java.util.List;

/**
 * 执行计划同步提醒（TodoWrite 节奏护栏，2026-11-16 滞后攒批问题）。
 *
 * <p>实测（多模型复现）：模型在长流程中段普遍"忘记"调 TodoWrite——静态提示词纪律压不住，
 * 常见形态是连做两三大步的活后一次性补发状态更新，前端进度面板一格跳多格。
 * 本类每轮工具调用时跟踪"距上次 TodoWrite 的工具轮数"，每满
 * {@link #REMIND_EVERY_ROUNDS} 轮产出一句提醒文本，由调用方（AgentLoopExecutor）
 * 追加到该轮最后一个工具结果末尾（复用 LoopGuard 的回灌通道），让模型在当下自我纠正。
 *
 * <p>规则：本轮含 TodoWrite（大小写不敏感）→ 计数清零；未见过 TodoWrite 之前不提醒
 * （尚无计划可同步）；其余轮计数 +1，每达到倍数阈值提醒一次（若模型持续忽略则每窗口再提醒）。
 */
public class PlanSyncReminder {

    /** 被跟踪的执行计划工具名（data-agent M3 常驻工具，大小写不敏感匹配） */
    private static final String PLAN_TOOL = "TodoWrite";

    /** 每隔多少个未同步的工具轮提醒一次 */
    static final int REMIND_EVERY_ROUNDS = 5;

    private boolean planSeen = false;
    private int roundsSinceSync = 0;

    /** 对一轮工具调用做同步评估；返回非空文本时调用方应把它追加到本轮最后一个工具结果末尾 */
    public String evaluate(List<ToolCall> merged) {
        if (merged == null || merged.isEmpty()) {
            return null;
        }
        boolean hasPlanCall = merged.stream()
                .anyMatch(tc -> PLAN_TOOL.equalsIgnoreCase(tc.name()));
        if (hasPlanCall) {
            planSeen = true;
            roundsSinceSync = 0;
            return null;
        }
        if (!planSeen) {
            return null;   // 计划还没建立：无状态可同步
        }
        roundsSinceSync++;
        if (roundsSinceSync % REMIND_EVERY_ROUNDS == 0) {
            return reminderText(roundsSinceSync);
        }
        return null;
    }

    /** 追加到工具结果末尾的提醒文案 */
    static String reminderText(int rounds) {
        return "\n\n[系统提醒] 已连续 " + rounds + " 轮工具调用未同步执行计划（todoWrite）。"
                + "若当前工作已推进（某步骤已完成或新步骤已开始），请立即调用 todoWrite 更新状态——"
                + "状态更新要与步骤推进同轮同步，滞后补发会让前端进度面板跳格；"
                + "若确实仍处于同一步骤内连续作业，忽略本提醒继续工作。";
    }
}
