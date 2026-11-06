package com.llmetor.dataagent.dataagent.skill;

/**
 * 技能手动新建/编辑请求体（POST /api/skills、PUT /api/skills/{name}/content，内置技能拒绝编辑）。
 *
 * @param name        技能名（仅新建用；编辑时以 path variable 为准）
 * @param description 技能描述（写入 frontmatter description）
 * @param content     Markdown 正文（自带 frontmatter 会被防御性剥离后重新拼装）
 */
public record SkillUpsertRequest(String name, String description, String content) {
}
