package com.llmetor.miniagentx.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JsonRepairUtil 全场景（对应测试用例 M1-E01~E03）。
 */
class JsonRepairUtilTest {

    @Test
    void validJsonUntouched() {
        assertEquals("{\"a\":1}", JsonRepairUtil.fixJson("{\"a\":1}"));
    }

    @Test
    void stripMarkdownFence() {
        // M1-E02：markdown 围栏剥离
        assertEquals("{\"answer\":\"42\"}",
                JsonRepairUtil.fixJson("```json\n{\"answer\":\"42\"}\n```"));
        assertEquals("{\"a\":1}", JsonRepairUtil.fixJson("```\n{\"a\":1}\n```"));
    }

    @Test
    void trimJunkAroundWrapper() {
        assertEquals("{\"a\":1}", JsonRepairUtil.fixJson("结果是 {\"a\":1} 以上"));
    }

    @Test
    void removeTrailingComma() {
        assertEquals("{\"a\":1,\"b\":[1,2]}", JsonRepairUtil.fixJson("{\"a\":1,\"b\":[1,2,],}"));
    }

    @Test
    void quoteBareKeys() {
        assertEquals("{\"a\":1}", JsonRepairUtil.fixJson("{a:1}"));
        assertEquals("{\"a\":1, \"b\":2}", JsonRepairUtil.fixJson("{a:1, b:2}"));
    }

    @Test
    void smartQuotesReplaced() {
        assertEquals("{\"a\":\"x\"}", JsonRepairUtil.fixJson("{“a”:”x”}".replace('”', '"')));
    }

    @Test
    void invalidEscapeDropped() {
        // \x 是非法转义：反斜杠被丢弃（x\xx → xxx）
        assertEquals("{\"a\":\"xxx\"}", JsonRepairUtil.fixJson("{\"a\":\"x\\xx\"}"));
    }

    @Test
    void unbalancedFallsBackToContent() {
        String fixed = JsonRepairUtil.fixJson("这根本不是JSON");
        assertTrue(fixed.startsWith("{\"content\""), "兜底包成 content 字段: " + fixed);
        assertTrue(JsonRepairUtil.isValidJson(fixed));
    }

    @Test
    void nullAndBlankSafe() {
        assertEquals("{\"content\":\"\"}", JsonRepairUtil.fixJson(null));
        assertEquals("{\"content\":\"\"}", JsonRepairUtil.fixJson("  "));
    }

    @Test
    void isValidJsonChecks() {
        assertTrue(JsonRepairUtil.isValidJson("{\"a\":[1,{\"b\":\"}]}\"}]}"));
        assertTrue(!JsonRepairUtil.isValidJson("{\"a\":1"));
        assertTrue(!JsonRepairUtil.isValidJson("[1,2"));
        assertTrue(!JsonRepairUtil.isValidJson("\"unterminated"));
    }
}
