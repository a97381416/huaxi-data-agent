package com.llmetor.dataagent.dataagent.mschema;

import com.llmetor.dataagent.dataagent.mschema.Mschema.FkDef;
import com.llmetor.dataagent.dataagent.mschema.Mschema.FieldDef;
import com.llmetor.dataagent.dataagent.mschema.Mschema.TableDef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * M-Schema 自省器：JDBC DatabaseMetaData 读表/列/主键/外键，
 * JdbcTemplate 并行采集字符串列的示例值（总超时 5s，单列失败不影响整体）。
 */
@Slf4j
@Service
public class MschemaIntrospector {

    /** 需要采集示例值的类型（仅字符串类，数值/时间/二进制不采）。
     *  M-H1 PG：比较侧 toUpperCase()，此处补 PG 类型的大写形式（BPCHAR/NAME/CHARACTER VARYING/CHARACTER） */
    private static final Set<String> SAMPLE_VALUE_TYPES = Set.of(
            "VARCHAR", "CHAR", "ENUM", "SET", "TINYTEXT", "TEXT", "MEDIUMTEXT",
            "BPCHAR", "NAME", "CHARACTER VARYING", "CHARACTER"
    );

    /** 敏感列名（示例值采集跳过；与 M4 脱敏 mask-fields 默认配置对齐） */
    private static final Set<String> SENSITIVE_COLUMNS = Set.of("password", "id_card", "home_address");

    /** M-H1 PG：自省默认 schema（catalog 语义反转——PG 下 schema 才是命名空间） */
    private static final String DEFAULT_SCHEMA = "public";

    /** 每列最多采集的 distinct 值数量 */
    private static final int MAX_EXAMPLE_VALUES = 5;

    /** 示例值采集总超时（秒） */
    private static final int EXAMPLE_TIMEOUT_SECONDS = 5;

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Value("${data-agent.sensitive-filter.mask-fields:sys_user.password,user_profile.id_card,user_profile.home_address}")
    private List<String> maskFields;

    public MschemaIntrospector(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * 读取数据库完整 schema。
     *
     * @param excludeTables 需排除的表名（支持 * 通配符，为空则不过滤）
     */
    public Mschema readSchema(String catalog, String schema, List<String> excludeTables) {
        return readSchema(catalog, schema, excludeTables, List.of());
    }

    /**
     * M-H3 白名单模式：includeTables 非空时只放行名单内表（精确名，先于 exclude 判定），
     * 名单外一律跳过——适配"华西 65 张白名单"语义，比 60+ 项黑名单可维护。
     */
    public Mschema readSchema(String catalog, String schema, List<String> excludeTables, List<String> includeTables) {
        long t0 = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            // M-H1 PG 语义：catalog 是库名（连接已定位，传 null 即当前库），schema 才是命名空间——
            // 必须显式指定，否则 information_schema/pg_catalog 等系统 schema 会全扫进来
            //（MySQL 时代防呆是"必须指定 catalog"，语义在此反转）
            String effectiveSchema = schema != null ? schema : DEFAULT_SCHEMA;
            List<Pattern> excludePatterns = compilePatterns(excludeTables);
            Set<String> includes = includeTables == null ? Set.of() : Set.copyOf(includeTables);

            Map<String, TableDef> tables = readTables(meta, null, effectiveSchema, excludePatterns, includes);
            List<FkDef> fks = readForeignKeys(meta, null, effectiveSchema, tables.keySet(), excludePatterns);
            collectExamplesAsync(tables);

            log.info("[data-agent] MSchema 自省完成：schema={}, {} 表, {} 外键, 耗时 {}ms",
                    effectiveSchema, tables.size(), fks.size(), System.currentTimeMillis() - t0);
            return new Mschema(effectiveSchema, tables, fks);
        } catch (SQLException e) {
            throw new RuntimeException("MSchema 自省失败：" + e.getMessage(), e);
        }
    }

    /** 读表 + 视图清单（sakila 的 customer_list 等预聚合视图对分析同样有价值） */
    private Map<String, TableDef> readTables(DatabaseMetaData meta, String catalog,
                                             String schema, List<Pattern> excludePatterns,
                                             Set<String> includes) throws SQLException {
        Map<String, TableDef> tables = new LinkedHashMap<>();
        for (String kind : new String[]{"TABLE", "VIEW"}) {
            try (ResultSet rs = meta.getTables(catalog, schema, "%", new String[]{kind})) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    if (!includes.isEmpty() && !includes.contains(tableName.toLowerCase())) {
                        continue; // M-H3 白名单外直接跳过
                    }
                    if (isExcluded(tableName, excludePatterns)) {
                        continue;
                    }
                    String comment = rs.getString("REMARKS");
                    tables.put(tableName, new TableDef(
                            comment == null ? "" : comment.trim(), new LinkedHashMap<>()));
                }
            }
        }
        // 每张表：主键 + 列
        for (Map.Entry<String, TableDef> entry : tables.entrySet()) {
            String tableName = entry.getKey();
            Set<String> pks = readPrimaryKeys(meta, catalog, schema, tableName);
            try (ResultSet rs = meta.getColumns(catalog, schema, tableName, "%")) {
                while (rs.next()) {
                    String colName = rs.getString("COLUMN_NAME");
                    String type = rs.getString("TYPE_NAME");
                    boolean nullable = "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                    String colComment = rs.getString("REMARKS");
                    entry.getValue().fields().put(colName, new FieldDef(
                            type, pks.contains(colName), nullable,
                            colComment == null ? "" : colComment.trim(), new ArrayList<>()));
                }
            }
        }
        return tables;
    }

    private List<FkDef> readForeignKeys(DatabaseMetaData meta, String catalog, String schema,
                                        Set<String> tableNames, List<Pattern> excludePatterns) throws SQLException {
        List<FkDef> fks = new ArrayList<>();
        for (String tableName : tableNames) {
            try (ResultSet rs = meta.getImportedKeys(catalog, schema, tableName)) {
                while (rs.next()) {
                    String fkCol = rs.getString("FKCOLUMN_NAME");
                    String refTable = rs.getString("PKTABLE_NAME");
                    String refCol = rs.getString("PKCOLUMN_NAME");
                    if (fkCol != null && refTable != null && !isExcluded(refTable, excludePatterns)) {
                        fks.add(new FkDef(tableName, fkCol, refTable, refCol));
                    }
                }
            }
        }
        return fks;
    }

    /** 示例值并行采集：仅字符串类且非敏感列；总超时 5s，超时保留已完成部分 */
    private void collectExamplesAsync(Map<String, TableDef> tables) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (var tableEntry : tables.entrySet()) {
            String tableName = tableEntry.getKey();
            for (var fieldEntry : tableEntry.getValue().fields().entrySet()) {
                String colName = fieldEntry.getKey();
                FieldDef fd = fieldEntry.getValue();
                if (isSensitiveColumn(colName)) {
                    continue;
                }
                if (SAMPLE_VALUE_TYPES.contains(fd.type().toUpperCase())) {
                    futures.add(CompletableFuture.runAsync(() -> {
                        try {
                            List<String> values = fetchDistinct(tableName, colName);
                            fieldEntry.getValue().examples().addAll(values);
                        } catch (Exception e) {
                            log.debug("示例值采集失败 {}.{}: {}", tableName, colName, e.getMessage());
                        }
                    }));
                }
            }
        }
        if (!futures.isEmpty()) {
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(EXAMPLE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.debug("示例值采集超时（保留已完成部分）：{}", e.getMessage());
            }
        }
    }

    private List<String> fetchDistinct(String tableName, String colName) {
        // M-H1 PG：标识符引用从反引号改为双引号
        String sql = String.format(
                "SELECT DISTINCT \"%s\" FROM \"%s\" WHERE \"%s\" IS NOT NULL LIMIT %d",
                colName, tableName, colName, MAX_EXAMPLE_VALUES);
        return jdbcTemplate.queryForList(sql).stream()
                .map(m -> m.get(colName))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /** 敏感列判断：mask-fields 配置的纯列名 + 内置敏感列名（M-Schema 示例值永不采集这些列） */
    private boolean isSensitiveColumn(String colName) {
        String lower = colName.toLowerCase();
        if (SENSITIVE_COLUMNS.contains(lower)) {
            return true;
        }
        for (String field : maskFields) {
            int dot = field.lastIndexOf('.');
            String col = dot >= 0 ? field.substring(dot + 1) : field;
            if (col.trim().equalsIgnoreCase(colName)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> readPrimaryKeys(DatabaseMetaData meta, String catalog,
                                        String schema, String tableName) throws SQLException {
        Set<String> pks = new java.util.HashSet<>();
        try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, tableName)) {
            while (rs.next()) {
                pks.add(rs.getString("COLUMN_NAME"));
            }
        }
        return pks;
    }

    private List<Pattern> compilePatterns(List<String> excludeTables) {
        if (excludeTables == null || excludeTables.isEmpty()) {
            return List.of();
        }
        return excludeTables.stream()
                .map(s -> s.trim().replace("*", ".*"))
                .filter(s -> !s.isEmpty())
                .map(Pattern::compile)
                .toList();
    }

    private boolean isExcluded(String tableName, List<Pattern> patterns) {
        return patterns.stream().anyMatch(p -> p.matcher(tableName).matches());
    }
}
