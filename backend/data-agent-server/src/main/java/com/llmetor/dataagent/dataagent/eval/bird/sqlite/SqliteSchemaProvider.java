package com.llmetor.dataagent.dataagent.eval.bird.sqlite;

import com.llmetor.dataagent.dataagent.eval.bird.sqlite.BirdDatabaseDescriptionLoader.ColumnDescription;
import com.llmetor.dataagent.dataagent.mschema.Mschema;
import com.llmetor.dataagent.dataagent.mschema.Mschema.FieldDef;
import com.llmetor.dataagent.dataagent.mschema.Mschema.FkDef;
import com.llmetor.dataagent.dataagent.mschema.Mschema.TableDef;
import com.llmetor.dataagent.dataagent.mschema.SchemaProvider;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite schema 探查器（BIRD 评测用，M9 自原版 dodo-agentx 移植）。
 *
 * <p>实现业务 {@link SchemaProvider} 接口（签名与原版一致），但与生产的 MySQL M-Schema
 * 链路无任何共享状态：每题 new 一个实例，SQLite 连接直开直关，schema 与描述懒加载缓存。
 */
@Slf4j
public class SqliteSchemaProvider implements SchemaProvider {

    private static final String LIST_TABLES_SQL =
            "SELECT name, type FROM sqlite_master " +
                    "WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite_%' ORDER BY name";
    private static final int MAX_EXAMPLE_VALUES = 5;

    private final String jdbcUrl;
    private final String sqlitePath;
    private final String dbId;
    private Map<String, Map<String, ColumnDescription>> descriptions;
    private Mschema schema;

    public SqliteSchemaProvider(String sqlitePath) {
        this.jdbcUrl = "jdbc:sqlite:" + sqlitePath;
        this.sqlitePath = sqlitePath;
        this.dbId = deriveDbId(sqlitePath);
    }

    @Override
    public String listTables() {
        Mschema current = schema();
        if (current.tables().isEmpty()) {
            return "Schema is empty. The SQLite file may contain no tables or the path may be wrong.";
        }
        return BirdMschemaFormatter.formatTableList(current);
    }

    @Override
    public String describeTables(List<String> tableNames) {
        if (tableNames == null || tableNames.isEmpty()) {
            return "tableNames is empty. Provide exact table names, or call listTables first.";
        }

        Mschema current = schema();
        List<String> found = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String name : tableNames) {
            String trimmed = name == null ? "" : name.trim();
            if (current.tables().containsKey(trimmed)) {
                found.add(trimmed);
            } else if (!trimmed.isEmpty()) {
                missing.add(trimmed);
            }
        }

        if (found.isEmpty()) {
            return "No matching tables found: " + String.join(", ", missing)
                    + ". Call listTables and use exact table names.";
        }

        StringBuilder sb = new StringBuilder(BirdMschemaFormatter.formatTables(current, found));
        if (!missing.isEmpty()) {
            sb.append("\n\nMissing tables skipped: ").append(String.join(", ", missing))
                    .append(". Call listTables to verify exact names.");
        }
        return sb.toString().trim();
    }

    public String describeAllTables() {
        Mschema current = schema();
        if (current.tables().isEmpty()) {
            return "Schema is empty. The SQLite file may contain no tables or the path may be wrong.";
        }
        return BirdMschemaFormatter.formatTables(current, List.copyOf(current.tables().keySet()));
    }

    private Mschema schema() {
        if (schema == null) {
            schema = readSchema();
        }
        return schema;
    }

    private Mschema readSchema() {
        try (Connection conn = openReadOnly();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(LIST_TABLES_SQL)) {

            Map<String, TableDef> tables = new LinkedHashMap<>();
            while (rs.next()) {
                String tableName = rs.getString("name");
                tables.put(tableName, new TableDef("", new LinkedHashMap<>()));
            }

            for (String tableName : tables.keySet()) {
                Map<String, FieldDef> fields = tables.get(tableName).fields();
                for (ColumnDef c : readColumns(conn, tableName)) {
                    ColumnDescription desc = description(tableName, c.name);
                    fields.put(c.name, new FieldDef(
                            c.type,
                            c.pk,
                            !c.notNull,
                            desc.formattedComment(c.name),
                            fetchExamples(conn, tableName, c.name, c.type)
                    ));
                }
            }

            List<FkDef> fks = new ArrayList<>();
            for (String tableName : tables.keySet()) {
                fks.addAll(readForeignKeys(conn, tableName));
            }
            return new Mschema(dbId, tables, fks);
        } catch (SQLException e) {
            log.warn("[bird-eval] sqlite schema 读取失败: {}", e.getMessage());
            return new Mschema(dbId, Map.of(), List.of());
        }
    }

    private List<ColumnDef> readColumns(Connection conn, String tableName) throws SQLException {
        List<ColumnDef> cols = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + quoteIdent(tableName) + ")")) {
            while (rs.next()) {
                cols.add(new ColumnDef(
                        rs.getString("name"),
                        rs.getString("type"),
                        rs.getInt("notnull") == 1,
                        rs.getInt("pk") > 0));
            }
        }
        return cols;
    }

    private List<FkDef> readForeignKeys(Connection conn, String tableName) throws SQLException {
        List<FkDef> fks = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA foreign_key_list(" + quoteIdent(tableName) + ")")) {
            // PRAGMA foreign_key_list 列顺序：id(1), seq(2), table(3), from(4), to(5), on_update(6), on_delete(7), match(8)
            // 不用列名取，"from"/"to" 在某些 sqlite-jdbc 版本里取不到
            while (rs.next()) {
                String refTable = rs.getString(3);
                String fromCol = rs.getString(4);
                String toCol = rs.getString(5);
                if (refTable == null || fromCol == null) {
                    continue;
                }
                // to 为 NULL 表示 FK 引用父表主键但未显式指定列名（SQLite 默认指向 PK）
                if (toCol == null) {
                    toCol = resolvePrimaryKeyColumn(conn, refTable);
                }
                if (toCol != null) {
                    fks.add(new FkDef(tableName, fromCol, refTable, toCol));
                }
            }
        }
        return fks;
    }

    private static String resolvePrimaryKeyColumn(Connection conn, String tableName) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + quoteIdent(tableName) + ")")) {
            while (rs.next()) {
                if (rs.getInt("pk") > 0) {
                    return rs.getString("name");
                }
            }
        }
        return null;
    }

    private ColumnDescription description(String tableName, String columnName) {
        Map<String, ColumnDescription> tableDescriptions = descriptions().get(tableName);
        if (tableDescriptions == null) {
            return ColumnDescription.empty();
        }
        return tableDescriptions.getOrDefault(columnName, ColumnDescription.empty());
    }

    private Map<String, Map<String, ColumnDescription>> descriptions() {
        if (descriptions == null) {
            descriptions = BirdDatabaseDescriptionLoader.load(sqlitePath);
        }
        return descriptions;
    }

    /**
     * 只对文本字段采集示例值：取 distinct，最多 5 条，与生产 M-Schema 的采样策略保持一致。
     */
    private List<String> fetchExamples(Connection conn, String tableName, String colName, String type) {
        if (!isTextType(type)) {
            return List.of();
        }
        String sql = "SELECT DISTINCT " + quoteIdent(colName)
                + " FROM " + quoteIdent(tableName)
                + " WHERE " + quoteIdent(colName) + " IS NOT NULL LIMIT " + MAX_EXAMPLE_VALUES;
        List<String> values = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Object v = rs.getObject(1);
                if (v != null) {
                    String s = String.valueOf(v);
                    if (!s.isEmpty()) {
                        values.add(s);
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("[bird-eval] 示例值采集失败 {}.{}: {}", tableName, colName, e.getMessage());
            return List.of();
        }
        return values;
    }

    private Connection openReadOnly() throws SQLException {
        Connection conn = DriverManager.getConnection(jdbcUrl);
        try {
            conn.setReadOnly(true);
        } catch (SQLException e) {
            log.debug("[bird-eval] sqlite setReadOnly 失败，忽略：{}", e.getMessage());
        }
        return conn;
    }

    private static boolean isTextType(String type) {
        if (type == null) {
            return true;
        }
        String t = type.toUpperCase();
        return t.contains("CHAR") || t.contains("TEXT") || t.contains("CLOB") || t.isBlank();
    }

    private static String quoteIdent(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static String deriveDbId(String sqlitePath) {
        String normalized = sqlitePath.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String file = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    private record ColumnDef(String name, String type, boolean notNull, boolean pk) {
    }
}
