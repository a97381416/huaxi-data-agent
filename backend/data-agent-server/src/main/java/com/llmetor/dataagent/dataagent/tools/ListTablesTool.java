package com.llmetor.dataagent.dataagent.tools;

import com.llmetor.dataagent.dataagent.mschema.SchemaProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 列出当前数据库全部表与视图（含中文描述 + 关联表）。
 */
@Component
public class ListTablesTool {

    private final SchemaProvider schemaProvider;

    public ListTablesTool(SchemaProvider schemaProvider) {
        this.schemaProvider = schemaProvider;
    }

    @Tool(name = "listTables",
            description = "列出当前数据库的全部表和视图，含描述、关联表（外键指向的其它表）。" +
                    "回答数据问题的第一步：根据用户问题与表描述，挑出相关的表，" +
                    "再用 describeTables 查看这些表的字段详情。关联表能帮你快速判断要 JOIN 哪些表")
    public String listTables() {
        return schemaProvider.listTables();
    }
}
