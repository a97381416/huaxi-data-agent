package com.llmetor.dataagent.dataagent.eval.bird;

import com.llmetor.dataagent.dataagent.eval.bird.sqlite.BirdDatabaseDescriptionLoader;
import com.llmetor.dataagent.dataagent.eval.bird.sqlite.SqliteSchemaProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M9 SQLite schema 探查器单测：临时目录搭出 BIRD 数据包形态
 * （&lt;db&gt;/&lt;db&gt;.sqlite + database_description/*.csv），真跑校验
 * listTables/describeTables 输出、CSV 描述与值映射加载、外键与示例值。
 */
class SqliteSchemaProviderTest {

    @TempDir
    Path tempDir;

    /** 搭建 BIRD 数据包形态： california_schools/{california_schools.sqlite, database_description/schools.csv} */
    private Path buildBirdLayout() throws Exception {
        Path dbDir = tempDir.resolve("california_schools");
        Path descDir = dbDir.resolve("database_description");
        Files.createDirectories(descDir);
        Path dbFile = dbDir.resolve("california_schools.sqlite");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE schools (id TEXT PRIMARY KEY, name TEXT, code TEXT)");
            stmt.execute("CREATE TABLE students (id INTEGER PRIMARY KEY, school_id TEXT, "
                    + "FOREIGN KEY (school_id) REFERENCES schools(id))");
            stmt.execute("INSERT INTO schools VALUES ('01', 'Alpha High', 'A')");
            stmt.execute("INSERT INTO schools VALUES ('002', 'Beta High', 'B')");
            stmt.execute("INSERT INTO students VALUES (1, '01')");
        }
        // BIRD database_description CSV：original_name, alias, description, (3 留空), value_desc, extra
        Files.writeString(descDir.resolve("schools.csv"), """
                original_column_name,column_alias,column_description,,extra_column_description,
                id,SchoolCode,Unique school identifier,,"code like '001', fixed width",
                name,SchoolName,Name of the school,,full display name,
                code,,Internal code,,unuseful
                """);
        return dbFile;
    }

    @Test
    void listTablesContainsTablesAndForeignKeyLines() throws Exception {
        Path db = buildBirdLayout();
        String out = new SqliteSchemaProvider(db.toAbsolutePath().toString()).listTables();
        assertThat(out).contains("Database: california_schools");
        assertThat(out).contains("`schools`").contains("`students`");
        assertThat(out).contains("Foreign keys");
        assertThat(out).contains("`students`.`school_id` → `schools`.`id`");
    }

    @Test
    void describeTablesRendersDescriptionAliasValuesAndExamples() throws Exception {
        Path db = buildBirdLayout();
        String out = new SqliteSchemaProvider(db.toAbsolutePath().toString())
                .describeTables(List.of("schools"));
        assertThat(out).contains("[PK] `id`");
        assertThat(out).contains("Unique school identifier");
        assertThat(out).contains("alias: SchoolCode");
        assertThat(out).contains("Values: code like '001', fixed width");
        assertThat(out).contains("Examples: [");
        assertThat(out).contains("Alpha High");
    }

    @Test
    void unusefulColumnGetsDoNotUseWarning() throws Exception {
        Path db = buildBirdLayout();
        String out = new SqliteSchemaProvider(db.toAbsolutePath().toString())
                .describeTables(List.of("schools"));
        assertThat(out).contains("Values: unuseful. Do not filter, group by, or branch on this field");
    }

    @Test
    void describeTablesSkipsMissingTablesWithHint() throws Exception {
        Path db = buildBirdLayout();
        String out = new SqliteSchemaProvider(db.toAbsolutePath().toString())
                .describeTables(List.of("schools", "ghost"));
        assertThat(out).contains("Missing tables skipped: ghost");
        assertThat(out).contains("Table `schools`");
    }

    @Test
    void describeAllTablesReturnsEveryTable() throws Exception {
        Path db = buildBirdLayout();
        String out = new SqliteSchemaProvider(db.toAbsolutePath().toString()).describeAllTables();
        assertThat(out).contains("Table `schools`").contains("Table `students`");
    }

    @Test
    void emptyOrMissingTableNamesReturnHint() throws Exception {
        Path db = buildBirdLayout();
        SqliteSchemaProvider provider = new SqliteSchemaProvider(db.toAbsolutePath().toString());
        assertThat(provider.describeTables(List.of())).contains("tableNames is empty");
        assertThat(provider.describeTables(List.of("ghost", "phantom")))
                .contains("No matching tables found");
    }

    @Test
    void missingDescriptionDirectoryStillWorks() throws Exception {
        // 无 database_description 目录：描述为空但仍能出结构（描述是增强不是必需）
        Path dbDir = tempDir.resolve("no_desc_db");
        Files.createDirectories(dbDir);
        Path db = dbDir.resolve("no_desc_db.sqlite");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE t (a TEXT)");
        }
        String out = new SqliteSchemaProvider(db.toAbsolutePath().toString()).listTables();
        assertThat(out).contains("`t`");
        assertThat(out).doesNotContain("Values:");
        // 描述加载器直接对无目录路径安全
        assertThat(BirdDatabaseDescriptionLoader.load(db.toAbsolutePath().toString())).isEmpty();
    }
}
