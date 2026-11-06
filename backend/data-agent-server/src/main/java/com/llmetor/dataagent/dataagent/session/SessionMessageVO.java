package com.llmetor.dataagent.dataagent.session;

import java.util.List;

/**
 * 会话详情单轮消息 VO（对齐原版 SessionMessageVO 契约）。
 *
 * @param id          agentx_conversation 主键（该轮调用标识）
 * @param question    用户提问
 * @param answer      最终回答（实质性判定：倒序第一个 ≥100 字符或含 markdown 结构的 AssistantMessage）
 * @param timeline    前端时间线 JSON（thinking/text/tool/todo 四类事件按序重构）
 * @param createdAt   提问时间
 * @param attachments 附件元数据（M5 文件问答交付，当前恒为空列表）
 */
public record SessionMessageVO(Long id, String question, String answer, String timeline,
                               String createdAt, List<AttachmentInfo> attachments) {

    /** 附件元数据（M5 起填充） */
    public record AttachmentInfo(String fileId, String fileName, String fileType, Long fileSize) {
    }
}
