package com.llmetor.dataagent.dataagent.eval.bird.sqlite;

import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Transparent SQLite executor for BIRD evaluation.
 *
 * <p>The SQL is passed to SQLite exactly as supplied. This executor deliberately
 * performs no validation, rewriting, timeout, read-only enforcement, result
 * formatting, or row-limit injection.</p>
 */
@Slf4j
public class SqliteQueryExecutor {

    private final String jdbcUrl;

    public SqliteQueryExecutor(String sqlitePath) {
        this.jdbcUrl = "jdbc:sqlite:" + sqlitePath;
    }

    public ExecutionResult execute(String sql) {
        long start = System.currentTimeMillis();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            boolean hasResultSet = statement.execute(sql);
            if (!hasResultSet) {
                return ExecutionResult.error(
                        "The statement returned no result set.",
                        System.currentTimeMillis() - start);
            }
            try (ResultSet resultSet = statement.getResultSet()) {
                return readResult(resultSet, start);
            }
        } catch (Exception e) {
            log.debug("[bird-eval] sqlite execution failed: {}", e.getMessage());
            return ExecutionResult.error(
                    e.getClass().getSimpleName() + ": " + e.getMessage(),
                    System.currentTimeMillis() - start);
        }
    }

    private ExecutionResult readResult(ResultSet resultSet, long start) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        int columnCount = metadata.getColumnCount();
        List<String> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            columns.add(metadata.getColumnLabel(i));
        }

        List<List<Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            List<Object> row = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                row.add(resultSet.getObject(i));
            }
            rows.add(row);
        }
        return ExecutionResult.success(columns, rows, System.currentTimeMillis() - start);
    }

    /**
     * Neutral execution observation. All returned rows are retained and the SQL
     * itself is never modified.
     */
    public record ExecutionResult(
            boolean ok,
            String error,
            List<String> columns,
            List<List<Object>> rows,
            long rowCount,
            long durationMs) {

        static ExecutionResult success(List<String> columns, List<List<Object>> rows,
                                       long durationMs) {
            return new ExecutionResult(true, null, columns, rows, rows.size(), durationMs);
        }

        static ExecutionResult error(String error, long durationMs) {
            return new ExecutionResult(false, error, List.of(), List.of(), 0, durationMs);
        }
    }
}
