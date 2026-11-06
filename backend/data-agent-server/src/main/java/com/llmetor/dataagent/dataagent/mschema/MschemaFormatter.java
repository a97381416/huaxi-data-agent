package com.llmetor.dataagent.dataagent.mschema;

import com.llmetor.dataagent.dataagent.mschema.Mschema.FkDef;
import com.llmetor.dataagent.dataagent.mschema.Mschema.FieldDef;
import com.llmetor.dataagent.dataagent.mschema.Mschema.TableDef;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mschema → M-Schema 文本格式转换器（XiYan-SQL M-Schema 规范）。
 * 格式：{@code 表名（注释）[(字段: TYPE, PK?, NOT NULL?, 注释, Examples: [...])]}
 */
public final class MschemaFormatter {

    private MschemaFormatter() {
    }

    /** 指定表 → M-Schema 文本（外键仅输出涉及已选表的） */
    public static String formatTables(Mschema schema, List<String> tableNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("【DB_ID】 ").append(schema.dbId()).append("\n");
        sb.append("【Schema】\n\n");

        boolean first = true;
        for (String name : tableNames) {
            TableDef t = schema.tables().get(name);
            if (t == null) {
                continue;
            }
            if (!first) {
                sb.append('\n');
            }
            first = false;
            formatSingleTable(sb, name, t);
        }

        formatForeignKeys(sb, schema.foreignKeys(), new LinkedHashSet<>(tableNames));
        return sb.toString().trim();
    }

    /** 全库 → M-Schema 文本 */
    public static String formatAll(Mschema schema) {
        return formatTables(schema, new ArrayList<>(schema.tables().keySet()));
    }

    /** 仅表名清单 + 注释 + 关联表（给 listTables 用，不输出字段详情） */
    public static String formatTableList(Mschema schema) {
        StringBuilder sb = new StringBuilder();
        sb.append("【DB_ID】 ").append(schema.dbId()).append("\n");
        sb.append("当前数据库共 ").append(schema.tables().size()).append(" 个表：\n\n");
        for (var entry : schema.tables().entrySet()) {
            sb.append("- ").append(entry.getKey());
            String comment = entry.getValue().comment();
            if (comment != null && !comment.isEmpty()) {
                sb.append("（").append(comment).append("）");
            }
            List<String> related = relatedTables(schema, entry.getKey());
            if (!related.isEmpty()) {
                sb.append(" → 关联: ").append(String.join(", ", related));
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static void formatSingleTable(StringBuilder sb, String name, TableDef t) {
        sb.append("# ").append(name);
        if (t.comment() != null && !t.comment().isEmpty()) {
            sb.append("（").append(t.comment()).append("）");
        }
        sb.append("\n[");
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, FieldDef> e : t.fields().entrySet()) {
            lines.add(formatField(e.getKey(), e.getValue()));
        }
        sb.append(String.join(",\n", lines));
        sb.append("]\n");
    }

    private static String formatField(String name, FieldDef f) {
        StringBuilder sb = new StringBuilder();
        sb.append("(").append(name).append(": ").append(f.type().toUpperCase());
        if (f.primaryKey()) {
            sb.append(", Primary Key");
        }
        if (!f.nullable()) {
            sb.append(", NOT NULL");
        }
        if (f.comment() != null && !f.comment().isEmpty()) {
            sb.append(", ").append(f.comment());
        }
        List<String> examples = f.examples();
        if (!examples.isEmpty()) {
            // 最多 5 个、单个超 30 字符截断
            List<String> shortExamples = examples.stream()
                    .limit(5)
                    .map(s -> s.length() > 30 ? s.substring(0, 27) + "..." : s)
                    .toList();
            sb.append(", Examples: [").append(String.join(", ", shortExamples)).append("]");
        }
        sb.append(")");
        return sb.toString();
    }

    private static void formatForeignKeys(StringBuilder sb, List<FkDef> fks, Set<String> outputTables) {
        if (fks.isEmpty()) {
            return;
        }
        boolean hasRelevant = false;
        StringBuilder fkBlock = new StringBuilder();
        fkBlock.append("【Foreign keys】\n");
        for (FkDef fk : fks) {
            if (outputTables.contains(fk.table()) && outputTables.contains(fk.refTable())) {
                fkBlock.append(fk.table()).append(".").append(fk.column())
                        .append(" = ").append(fk.refTable()).append(".").append(fk.refColumn())
                        .append("\n");
                hasRelevant = true;
            }
        }
        if (hasRelevant) {
            sb.append(fkBlock);
        }
    }

    /** 某张表的外键关联表名（去重排序） */
    private static List<String> relatedTables(Mschema schema, String tableName) {
        return schema.foreignKeys().stream()
                .filter(fk -> fk.table().equals(tableName))
                .map(FkDef::refTable)
                .distinct()
                .sorted()
                .toList();
    }
}
