package com.llmetor.dataagent.dataagent.eval.bird;

import com.llmetor.dataagent.dataagent.mschema.SchemaProvider;
import org.springframework.ai.tool.annotation.Tool;

/**
 * BIRD 评测用 listTables 工具（M9 自原版移植时按本项目情况重写）。
 *
 * <p>原版 {@code tools.ListTablesTool} 是 SchemaProvider 注入的 Spring bean；本项目业务侧
 * 同名工具已绑定 MySQL，为不产生 @Tool 冲突，bird 包内自建<b>非 bean</b> 版本——
 * 每题由 {@link BirdEvalToolFactory} new 一个实例，指向该题的 SQLite 库。
 * @Tool 注解与描述文案逐字照抄原版（引导词是 prompt 工程的一部分）。
 */
public class BirdListTablesTool {

    private final SchemaProvider schemaProvider;

    public BirdListTablesTool(SchemaProvider schemaProvider) {
        this.schemaProvider = schemaProvider;
    }

    @Tool(name = "listTables",
            description = "列出当前数据库的全部表和视图，含描述、关联表（外键指向的其它表）。" +
                    "回答数据问题的第一步：根据用户问题与表描述，挑出相关的表，" +
                    "再用 describeTables 查看这些表的字段详情。" +
                    "关联表能帮你快速判断要 JOIN 哪些表")
    public String listTables() {
        return schemaProvider.listTables();
    }
}
