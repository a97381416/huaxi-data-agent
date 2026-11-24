package com.llmetor.dataagent.dataagent.monitor.detect;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则注册表（V2 规格 §4）：15 条规则按固定顺序执行——
 * REACT 5 → TOOL 7 → ANSWER 2（ANSWER-001 依赖 TOOL-001/REACT-002 的命中，必须排后）。
 * V2 新增：REACT-005（无有效进展）/ TOOL-006（参数异常）/ TOOL-007（API 业务失败）/ SKILL-001（技能加载失败）。
 */
@Component
public class DetectRuleRegistry {

    /** 引擎版本（V2-D8：升 v2；历史风险不回刷，靠批量重诊断按版本刷新） */
    public static final String VERSION = "v2";

    private final List<DetectRule> rules;

    public DetectRuleRegistry() {
        List<DetectRule> all = new ArrayList<>();
        all.addAll(ReactRules.all());
        all.addAll(ToolRules.all());
        all.addAll(SkillRules.all());
        all.addAll(AnswerRules.all());
        this.rules = List.copyOf(all);
    }

    /** 注册顺序 = 执行顺序 */
    public List<DetectRule> rules() {
        return rules;
    }

    public DetectRule byId(String ruleId) {
        for (DetectRule rule : rules) {
            if (rule.id().equals(ruleId)) {
                return rule;
            }
        }
        return null;
    }

    public String titleOf(String ruleId) {
        DetectRule rule = byId(ruleId);
        return rule == null ? ruleId : rule.title();
    }
}
