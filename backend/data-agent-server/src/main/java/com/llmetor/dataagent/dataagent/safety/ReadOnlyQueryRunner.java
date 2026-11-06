package com.llmetor.dataagent.dataagent.safety;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读 SQL 执行器：每次新建 Connection（setReadOnly + FORWARD_ONLY + 30s 超时 + maxRows+1 硬限），
 * 读取循环再硬限 200 行；瞬态错误透明重试；错误按 SQLState 分类给 LLM 修复建议。
 */
@Slf4j
@Service
public class ReadOnlyQueryRunner {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /** 瞬态错误（超时/断连）透明重试次数 */
    private static final int MAX_TRANSIENT_RETRIES = 2;

    private final DataSource dataSource;

    @Value("${data-agent.safety.max-rows:200}")
    private int maxRows;

    @Value("${data-agent.safety.query-timeout-seconds:30}")
    private int timeoutSeconds;

    public ReadOnlyQueryRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 执行只读 SELECT/WITH 查询 */
    public SqlResult execute(String safeSql) {
        long t0 = System.currentTimeMillis();
        for (int attempt = 0; attempt <= MAX_TRANSIENT_RETRIES; attempt++) {
            try (Connection conn = dataSource.getConnection()) {
                trySetReadOnly(conn);
                try (Statement stmt = conn.createStatement(
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                    stmt.setQueryTimeout(timeoutSeconds);
                    stmt.setMaxRows(maxRows + 1);
                    try (ResultSet rs = stmt.executeQuery(safeSql)) {
                        return readResultSet(rs, safeSql, t0);
                    }
                }
            } catch (SQLException e) {
                SqlErrorType errorType = classify(e);
                if (errorType == SqlErrorType.TRANSIENT && attempt < MAX_TRANSIENT_RETRIES) {
                    log.debug("TRANSIENT 错误，第 {} 次重试: {}", attempt + 1, rootSqlMessage(e));
                    try {
                        Thread.sleep(500L * (attempt + 1));
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                return SqlResult.fail(safeSql, buildClassifiedError(e, errorType),
                        System.currentTimeMillis() - t0);
            }
        }
        String msg = "执行超时：数据库连接或查询超时，已重试 " + MAX_TRANSIENT_RETRIES
                + " 次仍未成功。可能是数据库负载过高，请稍后重试。";
        return SqlResult.fail(safeSql, msg, System.currentTimeMillis() - t0);
    }

    private SqlResult readResultSet(ResultSet rs, String safeSql, long t0) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int colCount = md.getColumnCount();
        List<String> columns = new ArrayList<>(colCount);
        List<SqlResult.ColumnMeta> columnMetas = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            String label = md.getColumnLabel(i);
            columns.add(label);
            columnMetas.add(new SqlResult.ColumnMeta(label, md.getTableName(i), md.getColumnName(i)));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>(colCount);
            for (int i = 1; i <= colCount; i++) {
                row.put(columns.get(i - 1), normalizeValue(rs.getObject(i)));
            }
            rows.add(row);
        }
        return SqlResult.ok(safeSql, columns, columnMetas, rows, truncated,
                System.currentTimeMillis() - t0);
    }

    private void trySetReadOnly(Connection conn) {
        try {
            conn.setReadOnly(true);
        } catch (SQLException e) {
            log.debug("setReadOnly 失败，忽略：{}", e.getMessage());
        }
    }

    private Object normalizeValue(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd.stripTrailingZeros().toPlainString();
        if (v instanceof LocalDate ld) return ld.format(DATE_FMT);
        if (v instanceof LocalDateTime ldt) return ldt.format(DATETIME_FMT);
        if (v instanceof java.sql.Date d) return d.toLocalDate().format(DATE_FMT);
        if (v instanceof java.sql.Timestamp t) return t.toLocalDateTime().format(DATETIME_FMT);
        if (v instanceof byte[] bytes) return "[BLOB " + bytes.length + " bytes]";
        return v;
    }

    enum SqlErrorType {
        /** 瞬态：超时/连接断开 → 透明重试 */
        TRANSIENT,
        /** Schema：列/表不存在 → 暴露给 LLM，建议查 describeTables */
        SCHEMA,
        /** 语法：SQL 语法错误 → 暴露给 LLM */
        SYNTAX,
        /** 致命：权限/资源等 → 告知 LLM 无法继续 */
        FATAL
    }

    private SqlErrorType classify(SQLException e) {
        String state = e.getSQLState();
        if (state == null) return SqlErrorType.FATAL;
        if (state.startsWith("08")) return SqlErrorType.TRANSIENT;
        if ("40001".equals(state)) return SqlErrorType.TRANSIENT;
        if ("42S02".equals(state) || "42S22".equals(state)) return SqlErrorType.SCHEMA;
        if ("42000".equals(state) || "42601".equals(state)) return SqlErrorType.SYNTAX;
        if (state.startsWith("28")) return SqlErrorType.FATAL;
        return SqlErrorType.FATAL;
    }

    private String buildClassifiedError(SQLException e, SqlErrorType type) {
        String rawMsg = rootSqlMessage(e);
        return switch (type) {
            case SCHEMA -> "字段或表不存在：" + rawMsg
                    + "\n建议：重新调用 describeTables 确认真实的表名和字段名（注意拼写和大小写），再修改 SQL。";
            case SYNTAX -> "SQL 语法错误：" + rawMsg
                    + "\n建议：检查 SQL 语法，确认关键字、括号、引号是否正确。";
            case FATAL -> "数据库错误（无法重试）：" + rawMsg
                    + "\n可能是权限不足或资源问题，请检查数据库状态。";
            default -> "执行失败：" + rawMsg;
        };
    }

    private String rootSqlMessage(SQLException e) {
        SQLException cur = e;
        SQLException deepest = e;
        while (cur != null) {
            deepest = cur;
            cur = cur.getNextException();
        }
        String msg = deepest.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = deepest.getClass().getSimpleName();
        }
        return truncate(msg, 500);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
