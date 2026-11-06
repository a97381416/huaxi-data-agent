package com.llmetor.dataagent.dataagent.skill;

/**
 * 技能信息 VO（/api/skills 列表与上传/新建/编辑返回体）。
 *
 * @param name        技能名（唯一）
 * @param path        存储位置（standalone 下为本地目录绝对路径）
 * @param description 技能描述（SKILL.md frontmatter）
 * @param enabled     是否启用（启用才进 SkillsTool 可见清单）
 * @param source      来源：builtin=仓库目录同步入库 / upload=zip 上传 / manual=管理界面手动创建（仅 manual 可编辑）
 */
public record SkillInfo(String name, String path, String description, boolean enabled, String source) {
}
