package com.llmetor.dataagent.dataagent.file;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * agentx_file 表实体（M5，启动时自动建表，见 {@link FileManageService#ensureTableExists}）。
 *
 * <p>status 状态机：PROCESSING → SUCCESS / FAILED；
 * embed 是 analyzeFile 三路路由的依据：1=已向量化走 RAG 检索，0=直接加载 extracted_text。
 */
@Data
@TableName("agentx_file")
public class AgentxFile implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 业务唯一键 UUID（对外暴露的 fileId） */
    @TableField("file_id")
    private String fileId;

    @TableField("file_name")
    private String fileName;

    /** 扩展名（小写），类型路由 + 预览 MIME 映射的依据 */
    @TableField("file_type")
    private String fileType;

    @TableField("file_size")
    private Long fileSize;

    /** MinIO 完整 URL（objectName 从中截取） */
    @TableField("minio_path")
    private String minioPath;

    /** 解析后文本（截断版），LLM 直接加载；图片识别结果也懒写回此列作缓存 */
    @TableField("extracted_text")
    private String extractedText;

    /** PROCESSING / SUCCESS / FAILED */
    @TableField("status")
    private String status;

    /** 0=未向量化（直接加载完整文本） 1=已向量化（analyzeFile 走 RAG 检索） */
    @TableField("embed")
    private Integer embed;

    /** 所属会话（stream 启动时 linkToConversation 立即写入，system prompt 据此反查） */
    @TableField("conversation_id")
    private String conversationId;

    /** 所属轮次（Complete/Paused 事件后 linkToSession 补写，历史回放按轮取附件） */
    @TableField("session_id")
    private Long sessionId;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
