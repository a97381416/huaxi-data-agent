package com.llmetor.miniagentx.model.think;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THINK_TAG 解析器全场景（对应测试用例 M1-B01~B07，含标签跨 chunk 拆分）。
 */
class ThinkTagParserTest {

    private static String text(ThinkTagParser.Output o) {
        return o.textDelta() == null ? "" : o.textDelta();
    }

    private static String think(ThinkTagParser.Output o) {
        return o.thinkingDelta() == null ? "" : o.thinkingDelta();
    }

    @Test
    void completeTagInSingleChunk() {
        // M1-B01：标签完整单 chunk
        ThinkTagParser p = new ThinkTagParser();
        ThinkTagParser.Output out = p.feed("<think>推理</think>答案");
        assertEquals("推理", think(out));
        assertEquals("答案", text(out));
        assertFalse(p.isInThink());
    }

    @Test
    void openTagSplitAcrossChunks() {
        // M1-B02：开标签跨 chunk 拆分（解析器逐 chunk 发增量）
        ThinkTagParser p = new ThinkTagParser();
        assertEquals("前缀", text(p.feed("前缀<th")));
        ThinkTagParser.Output o2 = p.feed("ink>推");
        assertEquals("", text(o2));
        assertEquals("推", think(o2), "标签齐后的首个思考增量");
        assertEquals("理", think(p.feed("理")));
    }

    @Test
    void closeTagSplitAcrossChunks() {
        // M1-B03：闭标签拆在边界
        ThinkTagParser p = new ThinkTagParser();
        assertEquals("思考", think(p.feed("<think>思考")));
        ThinkTagParser.Output out = p.feed("内容</th");
        assertEquals("内容", think(out), "闭标签残缺时内容先发");
        assertEquals("", text(out));
        assertEquals("正文", text(p.feed("ink>正文")));
    }

    @Test
    void closeTagSplitRightAfterContent() {
        // 闭标签拆在 </thi 与 nk> 之间（合法拆分点）
        ThinkTagParser p = new ThinkTagParser();
        assertEquals("abc", think(p.feed("<think>abc</thi")));
        ThinkTagParser.Output o1 = p.feed("nk>答案");
        assertEquals("", think(o1));
        assertEquals("答案", text(o1));
    }

    @Test
    void tagWithAttributes() {
        // M1-B04：带属性变体
        ThinkTagParser p = new ThinkTagParser();
        ThinkTagParser.Output out = p.feed("<think lang=\"zh\">推理</think>答案");
        assertEquals("推理", think(out));
        assertEquals("答案", text(out));
    }

    @Test
    void noThinkTag() {
        // M1-B05：无思考内容
        ThinkTagParser p = new ThinkTagParser();
        ThinkTagParser.Output out = p.feed("纯正文");
        assertEquals("纯正文", text(out));
        assertEquals("", think(out));
    }

    @Test
    void thinkOnlyNoText() {
        // M1-B06：只有思考无正文（逐 chunk 增量）
        ThinkTagParser p = new ThinkTagParser();
        assertEquals("只想", think(p.feed("<think>只想")));
        ThinkTagParser.Output out = p.feed("考</think>");
        assertEquals("考", think(out));
        assertEquals("", text(out));
    }

    @Test
    void flushDanglingPrefixAsText() {
        // M1-B07：流结束残留 "<thi" 应作为正文发出，不吞字
        ThinkTagParser p = new ThinkTagParser();
        assertEquals("正文", text(p.feed("正文<thi")));
        ThinkTagParser.Output flush = p.flush();
        assertEquals("<thi", text(flush));
        assertNull(flush.thinkingDelta());
    }

    @Test
    void flushDanglingPrefixInsideThink() {
        // 思考区内残留 "</thi"：内容已在 feed 时发出，flush 时只补残缺前缀
        ThinkTagParser p = new ThinkTagParser();
        assertEquals("思考", think(p.feed("<think>思考</thi")));
        ThinkTagParser.Output flush = p.flush();
        assertEquals("</thi", think(flush));
        assertNull(flush.textDelta());
    }

    @Test
    void loneAngleBracketNotSwallowed() {
        // 普通小于号不是标签前缀，不吞不滞留
        ThinkTagParser p = new ThinkTagParser();
        assertEquals("a < b", text(p.feed("a < b")));
        ThinkTagParser.Output flush = p.flush();
        assertEquals("", text(flush));
    }

    @Test
    void multipleThinkBlocks() {
        // 多段思考/正文交替
        ThinkTagParser p = new ThinkTagParser();
        StringBuilder thinkBuf = new StringBuilder();
        StringBuilder textBuf = new StringBuilder();
        for (String chunk : new String[]{"<think>一</think>甲", "<think>二</think>乙"}) {
            ThinkTagParser.Output out = p.feed(chunk);
            thinkBuf.append(think(out));
            textBuf.append(text(out));
        }
        assertEquals("一二", thinkBuf.toString());
        assertEquals("甲乙", textBuf.toString());
        assertTrue(true);
    }
}
