package com.llmetor.dataagent.dataagent.file;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 按段落（\n+）切分 + 块内按 chunkSize 续写 + 块间 overlap 重叠的文本切分器（M5）。
 *
 * <p>大文件向量化用，参数 (500, 50)：段落超长时按 chunkSize 填满即出块；
 * 块间重叠取块尾 overlap 字符作下块前缀——保住被块边界切断的句子上下文。
 * metadata 不从原文档继承（fileid/chunkId 由 EmbeddingService 注入）。
 * overlap < chunkSize 是硬约束（overlap ≥ chunkSize 会死循环）。
 */
public class OverlapParagraphTextSplitter extends TextSplitter {

    private final int chunkSize;
    private final int overlap;

    public OverlapParagraphTextSplitter(int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须大于 0");
        }
        if (overlap < 0) {
            throw new IllegalArgumentException("overlap 不能为负数");
        }
        if (overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap 不能大于等于 chunkSize");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override
    protected List<String> splitText(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        String[] paragraphs = text.split("\\n+");
        List<String> allChunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            if (paragraph.isBlank()) {
                continue;
            }
            int start = 0;
            while (start < paragraph.length()) {
                int remainingSpace = chunkSize - currentChunk.length();
                if (remainingSpace <= 0) {
                    // 理论不可达（出块后 chunk 长度 = overlap < chunkSize），防御性兜底防死循环
                    allChunks.add(currentChunk.toString());
                    currentChunk = new StringBuilder();
                    remainingSpace = chunkSize;
                }
                int end = Math.min(start + remainingSpace, paragraph.length());
                currentChunk.append(paragraph, start, end);

                if (currentChunk.length() >= chunkSize) {
                    allChunks.add(currentChunk.toString());
                    String overlapText = overlap > 0
                            ? currentChunk.substring(Math.max(0, currentChunk.length() - overlap))
                            : "";
                    currentChunk = new StringBuilder();
                    if (!overlapText.isEmpty()) {
                        currentChunk.append(overlapText);
                    }
                }
                start = end;
            }
        }

        if (currentChunk.length() > 0) {
            allChunks.add(currentChunk.toString());
        }
        return allChunks;
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }
        List<Document> result = new ArrayList<>();
        for (Document doc : documents) {
            for (String chunk : splitText(doc.getText())) {
                result.add(new Document(chunk));
            }
        }
        return result;
    }
}
