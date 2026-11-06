package com.llmetor.dataagent.dataagent.tools;

import com.llmetor.dataagent.dataagent.file.AgentxFile;
import com.llmetor.dataagent.dataagent.file.EmbeddingService;
import com.llmetor.dataagent.dataagent.file.FileManageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 文件内容加载工具（M5，analyzeFile 三路路由）。
 *
 * <p>按 DB 的 fileType + embed 字段分发，LLM 不需要知道文件类型：
 * <ol>
 *   <li>status != SUCCESS → 返回状态提示（不执行检索）</li>
 *   <li>图片 → MinIO 下载字节 → 多模态识别；首次识别后懒写回 extracted_text 缓存</li>
 *   <li>embed=1 → RAG 四步检索（按 question 召回片段）</li>
 *   <li>其他文本 → 直接返回 extracted_text</li>
 * </ol>
 * 本工具仅在会话存在文件时由 DodoAgent 条件注入（闲聊不浪费工具 schema）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyzeFileTool {

    private final FileManageService fileManageService;
    private final EmbeddingService embeddingService;
    private final org.springframework.beans.factory.ObjectProvider<com.llmetor.dataagent.dataagent.file.MultimodalService> multimodalProvider;

    @Tool(description = "针对用户上传的文件，按文件 ID 加载内容，支持文本解析、大文件 RAG 检索和图片识别，返回文本内容。"
            + "触发场景：用户问到'文件/文档/PDF/图片/报表/表格'等文件相关词，"
            + "或提到'这个/这些/那个/刚才的/之前的/第N章/第N页'等指代词且可能指代文件，"
            + "或 system prompt 里'当前会话文件'段列出了文件。"
            + "重要：system prompt 里【本次新上传】是一个整体，用户说'这个/这些/它们'时必须挨个 fileId 全部加载，不能只加载第一个。"
            + "大文件传 question 做语义检索。普通对话不要调本工具。")
    public String analyzeFile(
            @ToolParam(description = "文件 ID（UUID）") String fileId,
            @ToolParam(description = "用户问题，用于大文件 RAG 语义检索") String question) {

        log.info("[file] EXECUTE analyzeFile: fileId={}, question={}", fileId, question);
        if (!StringUtils.hasText(fileId)) {
            return "文件 ID 不能为空";
        }

        try {
            AgentxFile file = fileManageService.getFileInfo(fileId);
            if (!"SUCCESS".equals(file.getStatus())) {
                // D01：状态提示路由——处理中/失败不执行检索
                return String.format("文件尚不可用（状态 %s），fileId: %s。请稍后重试或提醒用户重新上传。",
                        file.getStatus(), fileId);
            }

            if (fileManageService.isImageFile(file.getFileType())) {
                return handleImage(file);
            }

            if (file.getEmbed() != null && file.getEmbed() == 1) {
                List<String> segments = embeddingService.ragRetrieve(fileId, question == null ? "" : question);
                if (segments.isEmpty()) {
                    return buildResponse(file, "未检索到与问题相关的内容，可换一种问法重试，或换个角度提问。", null, false);
                }
                return buildResponse(file, "RAG 检索命中片段", segments, false);
            }

            return buildResponse(file, file.getExtractedText(), null, false);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (Exception e) {
            log.error("[file] analyzeFile 失败: fileId={}", fileId, e);
            return "加载文件内容失败: " + e.getMessage();
        }
    }

    /** 图片按需识别 + 懒缓存：缓存命中直接返回，否则 MinIO 下载 → 多模态 → 写回 DB */
    private String handleImage(AgentxFile file) {
        if (StringUtils.hasText(file.getExtractedText())) {
            log.info("[file] 图片识别缓存命中: fileId={}", file.getFileId());
            return buildResponse(file, file.getExtractedText(), null, true);
        }

        var multimodalService = multimodalProvider.getIfAvailable();
        if (multimodalService == null) {
            return "图片识别服务未配置（multimodal.api-key 缺失），无法识别该图片内容。";
        }

        log.info("[file] 首次读取图片，调用多模态识别: fileId={}", file.getFileId());
        byte[] bytes = fileManageService.downloadFileBytes(file.getFileId());
        String description = multimodalService.imageToText(bytes, file.getFileName());

        // 懒缓存：写回 extracted_text，下次直接命中；失败不影响本次返回
        try {
            fileManageService.saveExtractedText(file.getFileId(), description);
        } catch (Exception cacheErr) {
            log.warn("[file] 图片识别结果写回缓存失败（不影响本次返回）: fileId={}, err={}",
                    file.getFileId(), cacheErr.getMessage());
        }
        return buildResponse(file, description, null, true);
    }

    private String buildResponse(AgentxFile file, String content, List<String> segments, boolean isImage) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 文件信息 ===\n");
        sb.append("文件名: ").append(file.getFileName()).append("\n");
        sb.append("文件类型: ").append(file.getFileType()).append("\n");
        if (isImage) {
            sb.append("内容来源: 多模态识别\n");
        } else if (file.getEmbed() != null && file.getEmbed() == 1) {
            sb.append("已向量化: 是（按问题检索相关片段）\n");
        }
        sb.append("\n=== 文件内容 ===\n");
        if (segments != null && !segments.isEmpty()) {
            for (int i = 0; i < segments.size(); i++) {
                sb.append("[片段 ").append(i + 1).append("]\n")
                        .append(segments.get(i)).append("\n\n");
            }
        } else if (content != null) {
            sb.append(content);
        } else {
            sb.append("无内容可显示");
        }
        return sb.toString();
    }
}
