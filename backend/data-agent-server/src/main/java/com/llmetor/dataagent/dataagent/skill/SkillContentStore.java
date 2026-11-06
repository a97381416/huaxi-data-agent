package com.llmetor.dataagent.dataagent.skill;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Skill 内容存储策略 SPI（SKILL.md + 资源文件的存取与同步）。
 *
 * <p>两套实现由 {@code deploy.mode} 切换（docs/01 §7.2）：
 * <ul>
 *   <li>standalone（默认，当前唯一实现）：本地目录为权威源，上传落本地、
 *       启动/定时把目录同步进 DB——手动往目录里放技能也能被自动发现</li>
 *   <li>cluster（M9 可选增强）：DB + MinIO 为权威源，各节点从 MinIO 拉取到本地 + pub/sub 通知</li>
 * </ul>
 * 两种模式下，Agent 运行时读的都是本节点 skills 目录下的内容。
 */
public interface SkillContentStore {

    /** 上传时存储技能内容（skillSourceDir 已解压并定位出 SKILL.md） */
    void save(String name, Path skillSourceDir) throws IOException;

    /**
     * 只重写技能的 SKILL.md 单文件（管理界面手动新建/编辑用）。
     * 不能走 {@link #save}（整目录替换会删掉 references/scripts 等同级附属资源）；
     * 目录不存在时自动创建。
     */
    void writeSkillFile(String name, String content) throws IOException;

    /** 删除技能内容 */
    void delete(String name) throws IOException;

    /** 同步（启动时 + 每 3 分钟定时）：standalone 实现为目录 → DB 入库/清孤儿 */
    void sync();

    /** DB 里 skill_path 存什么：standalone 返回本地目录绝对路径 */
    String storedPath(String name);

    /**
     * 内容变更后的通知（DB 写完后调用）：cluster 实现广播 pub/sub 让各节点立即 sync；
     * standalone 单机无需通知，默认空操作。
     */
    default void notifyChanged() {
    }
}
