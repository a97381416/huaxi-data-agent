package com.llmetor.dataagent.dataagent.eval.bird;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmetor.dataagent.dataagent.eval.bird.sqlite.SqliteQueryExecutor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M9 BIRD verifySql 工具单测：临时 SQLite 文件真跑，覆盖双模式契约与四类语义检查。
 * 核心断言"SQL 原样执行"（探测模式返回真实行、列名不被改写、无 LIMIT 注入）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BirdVerifySqlToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Path dbFile;

    @BeforeAll
    void setUp() throws Exception {
        dbFile = Files.createTempFile("bird-verify-test", ".sqlite");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE schools (code TEXT PRIMARY KEY, name TEXT, founded TEXT, score INTEGER)");
            stmt.execute("CREATE TABLE students (id INTEGER PRIMARY KEY, school_code TEXT, total INTEGER)");
            stmt.execute("INSERT INTO schools VALUES ('01', 'Alpha High', '2023-01-15', 90)");
            stmt.execute("INSERT INTO schools VALUES ('002', 'Beta High', '2024-02-20', 80)");
            stmt.execute("INSERT INTO schools VALUES ('3', 'Gamma High', '2024-06-01', 70)");
            stmt.execute("INSERT INTO students VALUES (1, '01', 100)");
            stmt.execute("INSERT INTO students VALUES (2, '01', 60)");
            stmt.execute("INSERT INTO students VALUES (3, '002', 80)");
        }
    }

    private BirdVerifySqlTool tool() {
        return new BirdVerifySqlTool(dbFile.toAbsolutePath().toString(),
                new SqliteQueryExecutor(dbFile.toAbsolutePath().toString()),
                "Question: test question\nEvidence: (none)",
                objectMapper);
    }

    private JsonNode verify(String sql, List<String> requestedColumns) throws Exception {
        return objectMapper.readTree(tool().verifySql(sql, requestedColumns));
    }

    private List<String> rules(JsonNode result) {
        List<String> list = new ArrayList<>();
        result.get("errors").forEach(e -> list.add(e.get("rule").asText()));
        return list.stream().sorted().toList();
    }

    @Test
    void probeModeExecutesExactlyAsWrittenAndReturnsRows() throws Exception {
        JsonNode result = verify("SELECT name FROM schools WHERE score > 75", List.of());
        assertThat(result.get("passed").asBoolean()).isTrue();
        assertThat(result.get("errors").size()).isZero();
        assertThat(result.get("execution").get("rowCount").asLong()).isEqualTo(2);
        assertThat(result.get("execution").get("columns").get(0).asText()).isEqualTo("name");
    }

    @Test
    void finalModeExactProjectionPasses() throws Exception {
        JsonNode result = verify("SELECT name, score FROM schools", List.of("name", "score"));
        assertThat(result.get("passed").asBoolean()).isTrue();
        assertThat(result.get("errors").size()).isZero();
    }

    @Test
    void finalModeCountMismatchReportsProjectionCount() throws Exception {
        JsonNode result = verify("SELECT name FROM schools", List.of("name", "score"));
        assertThat(result.get("passed").asBoolean()).isFalse();
        assertThat(rules(result)).containsExactly("PROJECTION_COUNT");
    }

    @Test
    void finalModeOrderMismatchReportsProjectionOrder() throws Exception {
        JsonNode result = verify("SELECT score, name FROM schools", List.of("name", "score"));
        assertThat(rules(result)).containsExactly("PROJECTION_ORDER");
    }

    @Test
    void finalModeUnknownNameReportsProjectionName() throws Exception {
        JsonNode result = verify("SELECT score AS s FROM schools", List.of("score"));
        assertThat(rules(result)).containsExactly("PROJECTION_NAME");
    }

    @Test
    void inferredNullFilterReportedWhenSqlFiltersNullWithoutAbsenceRequest() throws Exception {
        JsonNode result = verify("SELECT name FROM schools WHERE score IS NOT NULL", List.of("name"));
        assertThat(rules(result)).containsExactly("INFERRED_NULL_FILTER");
    }

    @Test
    void nullConditionAllowedWhenAbsenceExplicitlyRequested() throws Exception {
        BirdVerifySqlTool absenceTool = new BirdVerifySqlTool(
                dbFile.toAbsolutePath().toString(),
                new SqliteQueryExecutor(dbFile.toAbsolutePath().toString()),
                "Question: which schools are without a score?",
                objectMapper);
        JsonNode result = objectMapper.readTree(absenceTool.verifySql(
                "SELECT name FROM schools WHERE score IS NOT NULL", List.of("name")));
        assertThat(result.get("passed").asBoolean()).isTrue();
    }

    @Test
    void integerDivisionReportedWithoutRealCast() throws Exception {
        JsonNode result = verify("SELECT total / id FROM students", List.of("quotient"));
        assertThat(rules(result)).containsExactly("INTEGER_DIVISION", "PROJECTION_NAME");
    }

    @Test
    void integerDivisionSuppressedInsideCastAsReal() throws Exception {
        // 除法在 CAST(... AS REAL) 括号内 → realSpans 覆盖 → 不再报 INTEGER_DIVISION
        JsonNode result = verify("SELECT CAST(total / id AS REAL) FROM students", List.of("quotient"));
        assertThat(rules(result)).containsExactly("PROJECTION_NAME");
    }

    @Test
    void slashDateLiteralReported() throws Exception {
        JsonNode result = verify("SELECT name FROM schools WHERE founded = '2024/02/20'", List.of("name"));
        assertThat(rules(result)).containsExactly("DATE_LITERAL_FORMAT");
    }

    @Test
    void leadingZeroPaddingOnTextIdentifierReported() throws Exception {
        JsonNode result = verify("SELECT name FROM schools WHERE code = '0' || code", List.of("name"));
        assertThat(rules(result)).containsExactly("TEXT_IDENTIFIER_PADDING");
    }

    @Test
    void blankSqlReportsBlankSqlRule() throws Exception {
        JsonNode result = verify("  ", List.of());
        assertThat(result.get("passed").asBoolean()).isFalse();
        assertThat(rules(result)).containsExactly("BLANK_SQL");
    }

    @Test
    void brokenSqlReportsSqliteExecution() throws Exception {
        JsonNode result = verify("SELECT * FROM no_such_table", List.of());
        assertThat(result.get("passed").asBoolean()).isFalse();
        assertThat(rules(result)).containsExactly("SQLITE_EXECUTION");
    }

    @Test
    void executionErrorCarriesOriginalMessageForFixLoop() throws Exception {
        JsonNode result = verify("SELECT * FROM no_such_table", List.of());
        assertThat(result.get("errors").get(0).get("message").asText()).contains("no_such_table");
    }
}
