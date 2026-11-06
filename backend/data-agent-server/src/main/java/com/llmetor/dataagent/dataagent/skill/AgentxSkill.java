package com.llmetor.dataagent.dataagent.skill;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * agentx_skill 表实体（M6，启动时自动建表，见 {@link SkillManager#ensureTableExists}）。
 *
 * <p>standalone 部署下本地 skills 目录是权威源：目录新增→入库（enabled=1）、
 * 目录删除→清孤儿记录、覆盖上传→保留 enabled 状态。
 */
@Data
@TableName("agentx_skill")
public class AgentxSkill implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 技能名（唯一；SKILL.md frontmatter 的 name，缺省用目录名） */
    @TableField("name")
    private String name;

    /** 存储位置标识：standalone 下为本地目录绝对路径（cluster 版存 MinIO objectKey） */
    @TableField("skill_path")
    private String skillPath;

    /** 技能描述（SKILL.md frontmatter 的 description，渲染进 SkillsTool 工具描述） */
    @TableField("description")
    private String description;

    /** 1=启用（进 getEnabledSkillDirs → SkillsTool 每请求重建可见） 0=停用 */
    @TableField("enabled")
    private Integer enabled;

    /** 上传时的原始 zip 文件名（目录直放为 null） */
    @TableField("file_name")
    private String fileName;

    /** 来源：builtin=仓库目录同步入库 / upload=zip 上传 / manual=管理界面手动创建（仅 manual 可编辑内容） */
    @TableField("source")
    private String source;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
