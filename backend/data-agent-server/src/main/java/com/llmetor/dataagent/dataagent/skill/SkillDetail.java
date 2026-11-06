package com.llmetor.dataagent.dataagent.skill;

/**
 * 技能详情 VO（GET /api/skills/{name}/content 返回体）。
 *
 * @param name        技能名（唯一）
 * @param description 技能描述（DB 元数据，来自 frontmatter）
 * @param enabled     是否启用
 * @param source      来源：builtin=仓库目录同步入库 / upload=zip 上传 / manual=管理界面手动创建（仅 manual 可编辑）
 * @param content     SKILL.md 全文原文（含 frontmatter，前端源码视图展示）
 */
public record SkillDetail(String name, String description, boolean enabled, String source, String content) {
}
