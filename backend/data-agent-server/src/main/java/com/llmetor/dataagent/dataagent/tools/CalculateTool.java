package com.llmetor.dataagent.dataagent.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import net.objecthunter.exp4j.function.Function;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数学表达式计算器（exp4j）。用于同环比、占比、贡献度、加权平均等最终标量计算。
 * 聚合（SUM/AVG/COUNT）必须推给 SQL 完成，本工具只算最终公式。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CalculateTool {

    private static final int MAX_EXPRESSION_LENGTH = 2000;
    private static final int MAX_VARIABLES = 50;

    /** round(x, n)：四舍五入到 n 位小数（exp4j 内置无此函数） */
    private static final Function ROUND = new Function("round", 2) {
        @Override
        public double apply(double... args) {
            return BigDecimal.valueOf(args[0])
                    .setScale((int) args[1], RoundingMode.HALF_UP)
                    .doubleValue();
        }
    };

    /** max(a, b) / min(a, b)：exp4j 内置无此函数，注册 2 元版本与工具描述保持一致 */
    private static final Function MAX2 = new Function("max", 2) {
        @Override
        public double apply(double... args) {
            return Math.max(args[0], args[1]);
        }
    };

    private static final Function MIN2 = new Function("min", 2) {
        @Override
        public double apply(double... args) {
            return Math.min(args[0], args[1]);
        }
    };

    private final ObjectMapper objectMapper;

    @Tool(name = "calculate",
            description = "数学表达式计算器。传表达式 + 变量 JSON，后端安全求值并返回结果。" +
                    "用于同环比、占比、贡献度、加权平均等数据分析的最终计算。" +
                    "支持运算符：+ - * / % ^(幂)；" +
                    "内置函数：sin/cos/tan/log/log2/log10/ln/exp/sqrt/cbrt/abs/ceil/floor/max/min；" +
                    "额外函数：round(x, n) 保留 n 位小数。" +
                    "聚合（求和/均值/最大最小）请推到 SQL 用 GROUP BY + SUM/AVG 完成，本工具只算最终标量公式。")
    public String calculate(
            @ToolParam(description = "数学表达式，变量用变量名占位。例如 (current - previous) / previous * 100")
            String expression,
            @ToolParam(description = "变量值映射，JSON 对象，key=变量名 value=数字。例如 {\"current\":3298,\"previous\":3105}",
                    required = false)
            String variablesJson) {
        if (expression == null || expression.isBlank()) {
            return "❌ 表达式不能为空";
        }
        if (expression.length() > MAX_EXPRESSION_LENGTH) {
            return "❌ 表达式过长（>" + MAX_EXPRESSION_LENGTH + " 字符）";
        }

        Map<String, Double> vars;
        try {
            vars = parseVariables(variablesJson);
        } catch (Exception e) {
            return "❌ variablesJson 解析失败：" + e.getMessage()
                    + "\n正确格式：{\"var1\":1,\"var2\":2.5}";
        }
        if (vars.size() > MAX_VARIABLES) {
            return "❌ 变量数量超过上限 " + MAX_VARIABLES;
        }

        double result;
        try {
            result = evaluate(expression, vars);
        } catch (IllegalArgumentException e) {
            return "❌ 表达式无效：" + e.getMessage()
                    + "\n请检查：变量名是否与 variablesJson 的 key 一致（区分大小写）；运算符是否闭合；函数名拼写。";
        } catch (ArithmeticException e) {
            return "❌ 算术异常：" + e.getMessage() + "（常见原因：除数为 0）";
        } catch (Exception e) {
            return "❌ 计算失败：" + e.getMessage();
        }

        if (Double.isNaN(result)) {
            return "❌ 结果为 NaN（例如 0/0）。请检查变量值。";
        }
        if (Double.isInfinite(result)) {
            return "❌ 结果为无穷大（例如 1/0）。请检查除数。";
        }

        return formatResult(expression, vars, result);
    }

    private Map<String, Double> parseVariables(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<>() {
        });
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            Double d = toDouble(entry.getValue());
            if (d == null) {
                throw new IllegalArgumentException(
                        "变量 " + entry.getKey() + " 的值不是数字：" + entry.getValue());
            }
            out.put(entry.getKey(), d);
        }
        return out;
    }

    private static Double toDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private double evaluate(String expression, Map<String, Double> vars) {
        ExpressionBuilder builder = new ExpressionBuilder(expression)
                .function(ROUND).function(MAX2).function(MIN2);
        if (!vars.isEmpty()) {
            builder.variables(vars.keySet());
        }
        Expression e = builder.build();
        for (Map.Entry<String, Double> entry : vars.entrySet()) {
            e.setVariable(entry.getKey(), entry.getValue());
        }
        return e.evaluate();
    }

    private static String formatResult(String expression, Map<String, Double> vars, double result) {
        StringBuilder sb = new StringBuilder();
        sb.append("✅ ").append(expression.trim()).append(" = ").append(formatNumber(result));
        if (!vars.isEmpty()) {
            sb.append("\n变量值：");
            boolean first = true;
            for (Map.Entry<String, Double> entry : vars.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(entry.getKey()).append("=").append(formatNumber(entry.getValue()));
                first = false;
            }
        }
        return sb.toString();
    }

    /** 整数结果去掉小数点，避免 100.0 显示 */
    private static String formatNumber(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }
}
