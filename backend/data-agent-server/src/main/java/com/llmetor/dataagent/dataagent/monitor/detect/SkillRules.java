package com.llmetor.dataagent.dataagent.monitor.detect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.llmetor.dataagent.dataagent.monitor.detect.RuleSupport.MAPPER;
import static com.llmetor.dataagent.dataagent.monitor.detect.RuleSupport.abbreviate;
import static com.llmetor.dataagent.dataagent.monitor.detect.RuleSupport.evidence;

/**
 * Skill 加载规则（V2 规格 §4.13，V2-D6）：技能加载就是工具调用（工具名 Skill），
 * 加载失败返回「未找到技能: x。当前可用技能: …」文本——按工具结果检测，零采集改造。
 * 「应该调 Skill 而没调」不在检测范围（04 文档明确不查）。
 */
final class SkillRules {

    private SkillRules() {
    }

    static List<DetectRule> all() {
        return List.of(new SkillLoadFailed());
    }

    /** Skill 工具 Observation 匹配失败特征模式（未找到技能 / 可用技能 清单引导）→ 命中 */
    static final class SkillLoadFailed implements DetectRule {

        static final List<String> DEFAULT_FAIL_PATTERNS = List.of("未找到技能", "可用技能");

        @Override
        public String id() {
            return "SKILL-001";
        }

        @Override
        public String title() {
            return "Skill 加载失败";
        }

        @Override
        public String severity() {
            return "medium";
        }

        @Override
        public String description() {
            return "Skill 工具返回「未找到技能/可用技能」引导文本（技能名拼错或技能未安装；正常加载返回技能工作目录不判）";
        }

        @Override
        public Map<String, Object> defaultParams() {
            Map<String, Object> params = new java.util.LinkedHashMap<>();
            params.put("skillToolName", "Skill");
            params.put("failPatterns", DEFAULT_FAIL_PATTERNS);
            return params;
        }

        @Override
        public boolean enabled(DetectConfig cfg) {
            return cfg.enabled(id(), this);
        }

        @Override
        public List<RiskHit> eval(DetectContext ctx) {
            try {
                DetectConfig cfg = ctx.config();
                String skillTool = cfg.stringParam(id(), "skillToolName", "Skill");
                List<String> failPatterns = cfg.listParam(id(), "failPatterns", DEFAULT_FAIL_PATTERNS);
                List<RiskHit> hits = new ArrayList<>();
                for (DetectContext.TraceRow r : ctx.rounds()) {
                    // 本轮决策是否调用了 Skill 工具
                    String skillArgs = null;
                    for (DetectContext.ToolCallView call : ctx.toolCalls(r.round())) {
                        if (skillTool.equalsIgnoreCase(call.name())) {
                            skillArgs = call.arguments();
                            break;
                        }
                    }
                    if (skillArgs == null) {
                        continue;
                    }
                    // Skill 工具的观察（次轮 input 尾段 name 匹配的 role=tool 消息）
                    String content = null;
                    for (DetectContext.ToolObservation o : ctx.observationsAfter(r.round())) {
                        if (o.name() != null && skillTool.equalsIgnoreCase(o.name())) {
                            content = o.content() == null ? "" : o.content();
                            break;
                        }
                    }
                    if (content == null || content.isBlank()) {
                        continue;
                    }
                    String matched = null;
                    for (String p : failPatterns) {
                        if (p != null && !p.isBlank() && content.contains(p)) {
                            matched = p;
                            break;
                        }
                    }
                    if (matched == null) {
                        continue; // 正常加载（技能工作目录 + SKILL.md 正文）不判
                    }
                    String name = skillNameOf(skillArgs);
                    hits.add(new RiskHit(id(), severity(), r.round(), r.traceId(), null,
                            "第 " + r.round() + " 轮加载技能" + (name.isBlank() ? "" : "「" + name + "」")
                                    + "失败：" + abbreviate(content, 100),
                            evidence(Map.of("round", r.round(), "skill", name,
                                    "matchedPattern", matched,
                                    "excerpt", abbreviate(content, 160)))));
                }
                return hits;
            } catch (Exception e) {
                return List.of();
            }
        }

        /** 从 arguments 提取技能名（name 字段；解析失败给空串） */
        private static String skillNameOf(String arguments) {
            try {
                com.fasterxml.jackson.databind.JsonNode node = MAPPER.readTree(arguments);
                if (node.isObject() && node.has("name") && node.get("name").isTextual()) {
                    return node.get("name").textValue();
                }
            } catch (Exception ignored) {
                // fallthrough
            }
            return "";
        }
    }
}
