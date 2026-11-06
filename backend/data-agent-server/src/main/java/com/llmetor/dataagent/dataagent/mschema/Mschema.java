package com.llmetor.dataagent.dataagent.mschema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * M-Schema 元数据模型（dbId + 表 + 外键清单），Jackson 序列化到 Redis。
 *
 * <p>示例值是 M-Schema 的灵魂——字符串列的真实取值样本，让 LLM 不用猜 WHERE 条件。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Mschema(
        String dbId,
        Map<String, TableDef> tables,
        List<FkDef> foreignKeys) {

    public Mschema {
        tables = tables == null ? Map.of() : tables;
        foreignKeys = foreignKeys == null ? List.of() : foreignKeys;
    }

    /** 表定义（注释 + 字段映射） */
    public record TableDef(
            String comment,
            Map<String, FieldDef> fields) {
        public TableDef {
            fields = fields == null ? Map.of() : fields;
        }
    }

    /** 字段定义（类型/主键/可空/注释/示例值） */
    public record FieldDef(
            String type,
            boolean primaryKey,
            boolean nullable,
            String comment,
            List<String> examples) {
        public FieldDef {
            examples = examples == null ? List.of() : examples;
        }
    }

    /** 外键定义（table.column → refTable.refColumn） */
    public record FkDef(
            String table,
            String column,
            String refTable,
            String refColumn) {
    }
}
