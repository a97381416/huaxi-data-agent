package com.llmetor.dataagent.dataagent.skill;

/**
 * 聊天侧技能 VO（GET /agent/skills 返回体，斜杠命令选择技能用）。
 *
 * <p>与管理端 {@link SkillInfo} 的区别：仅暴露 name/description 两个最小字段，
 * 不含存储路径（登录用户可见，非 admin）。
 *
 * @param name        技能名（唯一，斜杠 token 用）
 * @param description 技能描述（SKILL.md frontmatter，面板副标题展示）
 */
public record ChatSkillInfo(String name, String description) {
}
