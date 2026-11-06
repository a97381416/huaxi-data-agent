package com.llmetor.dataagent.dataagent.tools;

import com.llmetor.dataagent.dataagent.mschema.SchemaProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 查看指定表/视图的字段详情（字段名/类型/可空/键/注释/外键/示例值）。
 */
@Component
public class DescribeTablesTool {

    private final SchemaProvider schemaProvider;

    public DescribeTablesTool(SchemaProvider schemaProvider) {
        this.schemaProvider = schemaProvider;
    }

    @Tool(name = "describeTables",
            description = "查看指定表/视图的字段详情（字段名/类型/可空/键/注释/外键/示例值）。" +
                    "写 SQL 前必须先调本工具看真实结构，严禁凭记忆写 SQL。" +
                    "建议先调 listTables 挑出相关表，再把表名传给本工具。" +
                    "字段是否当指标/维度/时间，由你（LLM）根据字段注释 + 类型 + 示例值自行判断。")
    public String describeTables(
            @ToolParam(description = "要查看的表名列表，如 [\"customer\", \"rental\"]")
            List<String> tableNames) {
        if (tableNames == null || tableNames.isEmpty()) {
            return "tableNames 为空。请传入具体表名，或先调 listTables 查看可用的表。";
        }
        return schemaProvider.describeTables(tableNames);
    }
}
