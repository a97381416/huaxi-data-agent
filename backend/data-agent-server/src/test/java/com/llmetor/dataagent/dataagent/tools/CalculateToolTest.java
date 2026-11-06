package com.llmetor.dataagent.dataagent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CalculateTool / TimeTool 单元测试（对应 M2-测试用例 C04/C05 的工具侧断言）。
 */
class CalculateToolTest {

    private final CalculateTool tool = new CalculateTool(new ObjectMapper());

    private String calc(String expression, String varsJson) {
        return tool.calculate(expression, varsJson);
    }

    @Test
    void simpleArithmetic() {
        String out = calc("(current - previous) / previous * 100", "{\"current\":3298,\"previous\":3105}");
        assertTrue(out.startsWith("✅"), out);
        assertTrue(out.contains("6.2"), out);
    }

    @Test
    void roundFunctionHalfUp() {
        String out = calc("round(x, 2)", "{\"x\":3.14159}");
        assertTrue(out.contains("3.14"), out);
    }

    @Test
    void integerResultNoDecimalPoint() {
        String out = calc("a * b", "{\"a\":6,\"b\":7}");
        assertTrue(out.contains("= 42"), out);
        assertFalse(out.contains("42.0"), out);
    }

    @Test
    void divisionByZeroReported() {
        String out = calc("1 / 0", null);
        assertTrue(out.contains("❌"), out);
    }

    @Test
    void unknownVariableRejected() {
        String out = calc("foo + 1", "{}");
        assertTrue(out.contains("❌"), out);
    }

    @Test
    void nonNumericVariableRejected() {
        String out = calc("foo + 1", "{\"foo\":\"abc\"}");
        assertTrue(out.contains("❌"), out);
    }

    @Test
    void emptyExpressionRejected() {
        assertTrue(calc("  ", null).contains("❌"));
    }

    @Test
    void maxMinFunctions() {
        String out = calc("max(a, b) + min(c, d)", "{\"a\":10,\"b\":3,\"c\":2,\"d\":9}");
        assertTrue(out.contains("= 12"), out);
    }
}
