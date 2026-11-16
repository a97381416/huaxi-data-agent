package com.llmetor.miniagentx.agent;

import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 循环熔断器（2026-11-12 TodoWrite 死循环事故修复 Fix A）——每请求一个实例，纯内存状态。
 *
 * <p>三级动作（复用主循环既有通道，不新增终止路径）：
 * <ul>
 *   <li>WARN（默认第 3 次）：工具结果末尾追加系统提示（调用方注入回灌文本）</li>
 *   <li>ESCALATE（默认第 5 次）：强警告 + 明示再犯将熔断</li>
 *   <li>BREAK（默认第 7 次）：跳过执行并注入熔断响应，主循环随即 forceFinal 逼出结论</li>
 * </ul>
 *
 * <p>计数规则：仅"纯单工具轮"（该轮全部 tool call 同名）对工具名累计，且**参数与上次相同**
 * 才计数——参数变化是真实进展（TodoWrite 更新计划、executeSql 换 SQL），不应计入复读
 * （2026-11-16：计划同步节奏变勤后，合法的边界更新纯轮会被误报"未产生新进展"）；
 * 混合轮不计数也不重置——其他工具穿插不应打断对复读的识别（事故实证：
 * context_reload 穿插后又回到 TodoWrite）。豁免名单（ignoreTools）内的工具不计数。
 */
public class LoopGuard {

    public enum Action { NONE, WARN, ESCALATE, BREAK }

    /** 评估结论：动作 + 触发工具名 + 累计次数（NONE 时 toolName 为 null） */
    public record Decision(Action action, String toolName, int count) {
        public static final Decision NONE = new Decision(Action.NONE, null, 0);

        /** 是否需要在工具结果末尾注入提示文本（WARN/ESCALATE） */
        public boolean hasNote() {
            return action == Action.WARN || action == Action.ESCALATE;
        }
    }

    private final LoopGuardConfig config;
    private final Map<String, Integer> pureRoundCounts = new HashMap<>();
    /** 同名纯单工具轮上次的参数基线（与本次相同才计复读） */
    private final Map<String, String> lastPureArgs = new HashMap<>();

    public LoopGuard(LoopGuardConfig config) {
        this.config = config == null ? LoopGuardConfig.defaults() : config;
    }

    /** 对一轮合并完成的工具调用做熔断评估；返回 NONE 表示照常执行 */
    public Decision evaluate(List<ToolCall> merged) {
        if (!config.enabled() || merged == null || merged.isEmpty()) {
            return Decision.NONE;
        }
        String name = merged.get(0).name();
        if (name == null || name.isBlank()) {
            return Decision.NONE;
        }
        for (ToolCall tc : merged) {
            if (!Objects.equals(tc.name(), name)) {
                return Decision.NONE;   // 混合轮：不计数、不重置
            }
        }
        if (config.ignoreTools().contains(name)) {
            return Decision.NONE;
        }
        // 首次观察计为第 1 次（阈值时序与旧版一致）；仅当参数相对上次纯轮"发生变化"才视为
        // 真实进展（换 SQL / 更新计划）跳过计数——参数没变的同名纯轮才是复读
        String args = merged.get(0).arguments();
        String prev = lastPureArgs.put(name, args);
        if (prev != null && !Objects.equals(prev, args)) {
            return Decision.NONE;
        }
        int count = pureRoundCounts.merge(name, 1, Integer::sum);
        if (count >= config.breakAfter()) {
            return new Decision(Action.BREAK, name, count);
        }
        if (count >= config.escalateAfter()) {
            return new Decision(Action.ESCALATE, name, count);
        }
        if (count >= config.warnAfter()) {
            return new Decision(Action.WARN, name, count);
        }
        return Decision.NONE;
    }

    /** WARN/ESCALATE 注入到工具结果末尾的提示文案 */
    public static String noteText(Decision d) {
        return switch (d.action()) {
            case WARN -> "\n\n[系统提示] 工具" + d.toolName() + "已连续第 " + d.count()
                    + " 次作为唯一工具被调用且未产生新进展。请检查：①是否陷入循环；"
                    + "②想用的工具不在当前清单时先调用 tool_search 发现；③无法推进时直接基于已有信息作答。";
            case ESCALATE -> "\n\n[系统警告] 工具" + d.toolName() + "已连续第 " + d.count()
                    + " 次被重复调用。再无进展将强制终止循环并要求直接给出结论，请立即改用其他工具或直接作答。";
            default -> "";
        };
    }

    /** BREAK 时注入的工具响应文案（替代真实执行结果） */
    public static String breakText(Decision d) {
        return "[系统熔断] 工具" + d.toolName() + "已连续第 " + d.count()
                + " 次被重复调用且无进展，本轮调用不再执行。请基于已有信息直接给出最终结论，不要再调用该工具。";
    }
}
