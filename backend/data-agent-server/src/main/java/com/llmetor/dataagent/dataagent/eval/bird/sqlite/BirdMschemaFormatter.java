package com.llmetor.dataagent.dataagent.eval.bird.sqlite;

import com.llmetor.dataagent.dataagent.mschema.Mschema;
import com.llmetor.dataagent.dataagent.mschema.Mschema.FieldDef;
import com.llmetor.dataagent.dataagent.mschema.Mschema.FkDef;
import com.llmetor.dataagent.dataagent.mschema.Mschema.TableDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * English schema formatter for BIRD evaluation.
 * <p>
 * 渲染目标：让 flash 这类指令跟随偏弱的模型也能扫读关键字段。
 * 关键设计：
 * - 字段名一律反引号包裹，避免空格/括号字段在生成 SQL 时漏写反引号
 * - PK / FK tag 前置，模型一眼能找到 join key
 * - 类型小写省空间
 * - 描述（CSV description + value_description）完整保留换行，不截断
 *
 * <p>M9 自原版 dodo-agentx 移植：业务 {@code mschema.MschemaFormatter} 是 MySQL 中文格式，
 * BIRD 用英文独立格式，两者并存互不影响。
 */
public final class BirdMschemaFormatter {

    private static final int MAX_EXAMPLES = 5;
    private static final int MAX_EXAMPLE_LENGTH = 40;

    private BirdMschemaFormatter() {
    }

    public static String formatTableList(Mschema schema) {
        StringBuilder sb = new StringBuilder();
        sb.append("Database: ").append(schema.dbId()).append('\n');
        sb.append("Tables:\n");
        for (String table : schema.tables().keySet()) {
            sb.append("  ").append(quote(table)).append('\n');
        }
        List<String> fkLines = formatFkLines(schema.foreignKeys(), schema.tables().keySet());
        if (!fkLines.isEmpty()) {
            sb.append("\nForeign keys (table.column → ref_table.ref_column):\n");
            for (String line : fkLines) {
                sb.append("  ").append(line).append('\n');
            }
        }
        return sb.toString().trim();
    }

    public static String formatTables(Mschema schema, List<String> tableNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("Database: ").append(schema.dbId()).append("\n\n");

        Set<String> outputTables = new LinkedHashSet<>();
        for (String tableName : tableNames) {
            TableDef table = schema.tables().get(tableName);
            if (table == null) {
                continue;
            }
            outputTables.add(tableName);
            formatTable(sb, schema, tableName, table);
            sb.append('\n');
        }
        List<String> fkLines = formatFkLines(schema.foreignKeys(), outputTables);
        if (!fkLines.isEmpty()) {
            sb.append("Foreign keys (table.column → ref_table.ref_column):\n");
            for (String line : fkLines) {
                sb.append("  ").append(line).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private static void formatTable(StringBuilder sb, Mschema schema, String tableName, TableDef table) {
        sb.append("Table ").append(quote(tableName)).append('\n');
        Map<String, List<FkDef>> fkByColumn = groupFksByColumn(schema.foreignKeys(), tableName);
        for (Map.Entry<String, FieldDef> entry : table.fields().entrySet()) {
            String columnName = entry.getKey();
            FieldDef field = entry.getValue();
            String tag = tag(field);
            List<FkDef> fks = fkByColumn.get(columnName);
            String fkRepr = fks == null ? "" : fks.stream()
                                               .map(fk -> "→ " + fk.refTable() + "." + fk.refColumn())
                                               .reduce((a, b) -> a + "; " + b)
                                               .orElse("");

            sb.append("  ");
            if (!tag.isEmpty()) {
                sb.append('[').append(tag).append("] ");
            }
            sb.append(quote(columnName)).append(' ').append(simpleType(field));
            if (!fkRepr.isEmpty()) {
                sb.append(' ').append(fkRepr);
            }
            String comment = field.comment();
            if (comment != null && !comment.isBlank()) {
                sb.append('\n');
                for (String line : comment.split("\n", -1)) {
                    if (!line.isBlank()) {
                        sb.append("      ").append(line).append('\n');
                    }
                }
                // 去掉末尾换行，下面 examples 紧接
                sb.setLength(sb.length() - 1);
            }
            List<String> examples = field.examples();
            if (!examples.isEmpty()) {
                sb.append("  Examples: [")
                        .append(String.join(", ", shorten(examples)))
                        .append(']');
            }
            sb.append('\n');
        }
    }

    private static String tag(FieldDef field) {
        if (field.primaryKey()) return "PK";
        if (!field.nullable()) return "NOT NULL";
        return "";
    }

    private static String simpleType(FieldDef field) {
        String t = field.type() == null ? "" : field.type().trim().toLowerCase();
        if (t.isEmpty()) return "unknown";
        if (t.contains("int")) return "integer";
        if (t.contains("real") || t.contains("double") || t.contains("float") || t.contains("num")) return "real";
        if (t.contains("char") || t.contains("text") || t.contains("clob")) return "text";
        if (t.contains("blob")) return "blob";
        return t;
    }

    private static List<String> shorten(List<String> examples) {
        return examples.stream()
                .limit(MAX_EXAMPLES)
                .map(v -> v.length() > MAX_EXAMPLE_LENGTH ? v.substring(0, MAX_EXAMPLE_LENGTH - 3) + "..." : v)
                .toList();
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private static Map<String, List<FkDef>> groupFksByColumn(List<FkDef> fks, String tableName) {
        Map<String, List<FkDef>> map = new LinkedHashMap<>();
        for (FkDef fk : fks) {
            if (fk.table().equals(tableName)) {
                map.computeIfAbsent(fk.column(), k -> new ArrayList<>()).add(fk);
            }
        }
        return map;
    }

    private static List<String> formatFkLines(List<FkDef> fks, Set<String> scopeTables) {
        return fks.stream()
                .filter(fk -> scopeTables.contains(fk.table()) && scopeTables.contains(fk.refTable()))
                .sorted(BirdMschemaFormatter::compareFk)
                .map(fk -> quote(fk.table()) + "." + quote(fk.column())
                        + " → " + quote(fk.refTable()) + "." + quote(fk.refColumn()))
                .toList();
    }

    private static int compareFk(FkDef a, FkDef b) {
        int c = a.table().compareToIgnoreCase(b.table());
        if (c != 0) return c;
        c = a.column().compareToIgnoreCase(b.column());
        if (c != 0) return c;
        c = a.refTable().compareToIgnoreCase(b.refTable());
        if (c != 0) return c;
        return a.refColumn().compareToIgnoreCase(b.refColumn());
    }
}
