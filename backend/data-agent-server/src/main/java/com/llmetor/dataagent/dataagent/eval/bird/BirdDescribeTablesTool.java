package com.llmetor.dataagent.dataagent.eval.bird;

import com.llmetor.dataagent.dataagent.mschema.SchemaProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * BIRD 评测用 describeTables 工具（M9 自原版移植时按本项目情况重写，非 bean，见
 * {@link BirdListTablesTool} 类注释）。字段是当指标还是维度由 LLM 根据注释和类型自行判断，
 * 本工具只呈现客观事实；列描述/值映射/示例值（database_description CSV）是权威元数据。
 */
public class BirdDescribeTablesTool {

    private final SchemaProvider schemaProvider;

    public BirdDescribeTablesTool(SchemaProvider schemaProvider) {
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
