package com.llmetor.dataagent.dataagent.file;

/**
 * 文件上传响应（M5）。
 *
 * @param fileId   文件唯一 ID（UUID，后续对话请求的 fileIds 用它）
 * @param fileName 原始文件名
 * @param fileType 扩展名
 * @param fileSize 大小（字节）
 * @param status   SUCCESS / FAILED
 * @param embed    是否已向量化（1=大文件走 RAG，0=直接加载 extracted_text）
 */
public record UploadResponse(String fileId,
                             String fileName,
                             String fileType,
                             long fileSize,
                             String status,
                             int embed) {

    public static UploadResponse from(AgentxFile f) {
        return new UploadResponse(
                f.getFileId(),
                f.getFileName(),
                f.getFileType(),
                f.getFileSize() == null ? 0 : f.getFileSize(),
                f.getStatus(),
                f.getEmbed() == null ? 0 : f.getEmbed());
    }
}
