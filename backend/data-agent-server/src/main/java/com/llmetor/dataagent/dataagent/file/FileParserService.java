package com.llmetor.dataagent.dataagent.file;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

/**
 * 文件解析服务（M5，Apache Tika 统一入口）。
 *
 * <p>parseToString 自动适配 PDF / Office 全家桶 / HTML / Email / 纯文本等几十种格式，
 * 替代 PDFBox + POI 的手写 switch-case。返回全量文本（供向量化）+ 截断版（供直接加载）。
 */
@Slf4j
@Service
public class FileParserService {

    /** Tika 解析上限：默认 parseToString(is) 只取 10 万字符，这里放宽到 200 万（100MB 上限的文件足够） */
    private static final int MAX_PARSE_LENGTH = 2_000_000;

    private final Tika tika = new Tika();

    @Value("${data-agent.file.text-max-length:20000}")
    private int maxTextLength;

    /**
     * 解析文件，返回全量文本 + 截断版（避免大文件塞爆 DB / LLM 上下文）。
     */
    public ParseResult parse(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        log.info("[file] Tika 开始解析: {} ({} bytes)", fileName, file.getSize());
        try (InputStream is = file.getInputStream()) {
            String fullText = tika.parseToString(is, new Metadata(), MAX_PARSE_LENGTH);
            if (fullText == null) {
                fullText = "";
            }
            fullText = fullText.trim();

            String truncated = truncate(fullText);
            log.info("[file] Tika 解析完成: {} | 全量长度={} | 截断长度={}",
                    fileName, fullText.length(), truncated.length());
            return new ParseResult(fullText, truncated);
        } catch (Exception e) {
            throw new RuntimeException("文件解析失败: " + e.getMessage(), e);
        }
    }

    private String truncate(String text) {
        if (text.length() <= maxTextLength) {
            return text;
        }
        log.info("[file] 文本超过 {} 字符，截断存储", maxTextLength);
        return text.substring(0, maxTextLength) + "\n\n... (内容已截断，文件过长；细节可通过 RAG 检索)";
    }

    @Data
    @AllArgsConstructor
    public static class ParseResult {
        /** 全量文本，用于向量化 */
        private String fullText;
        /** 截断文本，用于直接加载展示 */
        private String truncatedText;
    }
}
