package com.llmetor.dataagent.dataagent.file;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OverlapParagraphTextSplitter 单元测试（对应 M5-测试用例 A04：分块 (500,50)，重叠 50 字符可验证）。
 */
class OverlapParagraphTextSplitterTest {

    @Test
    void emptyAndBlankReturnEmpty() {
        OverlapParagraphTextSplitter splitter = new OverlapParagraphTextSplitter(500, 50);
        assertTrue(splitter.apply(List.of(new Document(""))).isEmpty());
        assertTrue(splitter.apply(List.of(new Document("  \n\n "))).isEmpty());
        assertTrue(splitter.apply(List.of()).isEmpty());
    }

    @Test
    void shortTextIsSingleChunk() {
        OverlapParagraphTextSplitter splitter = new OverlapParagraphTextSplitter(500, 50);
        List<Document> chunks = splitter.apply(List.of(new Document("只有一段短文本")));
        assertEquals(1, chunks.size());
        assertEquals("只有一段短文本", chunks.get(0).getText());
    }

    @Test
    void longParagraphSplitsAtChunkSize() {
        String text = "字".repeat(1200);
        OverlapParagraphTextSplitter splitter = new OverlapParagraphTextSplitter(500, 50);
        List<Document> chunks = splitter.apply(List.of(new Document(text)));

        // 1200 字符：块1=500 → 块2=50重叠+450 → 块3=50重叠+250（尾块不填满），共 3 块
        assertEquals(3, chunks.size());
        assertEquals(500, chunks.get(0).getText().length());
        for (int i = 1; i < chunks.size(); i++) {
            // 中间块填满 500，尾块 = overlap 前缀 + 剩余正文
            assertTrue(chunks.get(i).getText().length() <= 500, "块长不得超过 chunkSize: " + i);
            // 重叠验证：上一块尾部 50 字符 == 下一块开头 50 字符（尾块正文不足时除外）
            String prevTail = chunks.get(i - 1).getText().substring(500 - 50);
            assertTrue(chunks.get(i).getText().startsWith(prevTail),
                    "块 " + i + " 未以上一块尾部 50 字符开头（overlap 失效）");
        }
    }

    @Test
    void paragraphBreakKeepsOverlapAcrossParagraphs() {
        // 三个 400 字符段落，无换行分段时块长 500；有段落边界时段落完整性优先（块内填充段落剩余空间）
        String p = "段".repeat(400);
        String text = p + "\n\n" + p + "\n\n" + p;
        OverlapParagraphTextSplitter splitter = new OverlapParagraphTextSplitter(500, 50);
        List<Document> chunks = splitter.apply(List.of(new Document(text)));

        assertTrue(chunks.size() >= 3);
        // 相邻块重叠：前块尾 50 == 后块头 50
        for (int i = 1; i < chunks.size(); i++) {
            String prevTail = chunks.get(i - 1).getText().substring(
                    chunks.get(i - 1).getText().length() - 50);
            assertTrue(chunks.get(i).getText().startsWith(prevTail),
                    "段落边界处的块间重叠失效: 块 " + i);
        }
    }

    @Test
    void noOverlapKeepsChunksClean() {
        String text = "字".repeat(1000);
        OverlapParagraphTextSplitter splitter = new OverlapParagraphTextSplitter(500, 0);
        List<Document> chunks = splitter.apply(List.of(new Document(text)));
        assertEquals(2, chunks.size());
        assertEquals(500, chunks.get(0).getText().length());
        assertEquals(500, chunks.get(1).getText().length());
    }

    @Test
    void illegalArguments() {
        assertThrows(IllegalArgumentException.class, () -> new OverlapParagraphTextSplitter(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new OverlapParagraphTextSplitter(100, -1));
        // overlap ≥ chunkSize 会死循环，构造期直接拒绝
        assertThrows(IllegalArgumentException.class, () -> new OverlapParagraphTextSplitter(50, 50));
    }
}
