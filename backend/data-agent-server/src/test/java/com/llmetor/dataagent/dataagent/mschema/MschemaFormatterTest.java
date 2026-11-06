package com.llmetor.dataagent.dataagent.mschema;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MschemaFormatter 单元测试（对应 M2-测试用例 A02：示例值截断）。
 */
class MschemaFormatterTest {

    private Mschema sampleSchema() {
        Map<String, Mschema.FieldDef> filmFields = new LinkedHashMap<>();
        filmFields.put("film_id", new Mschema.FieldDef("smallint unsigned", true, false, "影片主键ID", List.of()));
        filmFields.put("title", new Mschema.FieldDef("varchar(128)", false, false, "影片标题",
                List.of("ACADEMY DINOSAUR", "CHAMBER ITALIAN")));
        filmFields.put("description", new Mschema.FieldDef("text", false, true, "影片简介",
                List.of("A Epic Drama of a Feminist And a Mad Scientist who must Battle a Teacher in The Canadian Rockies")));
        filmFields.put("rating", new Mschema.FieldDef("enum", false, true, "分级",
                List.of("G", "PG", "PG-13", "R", "NC-17", "额外第六个会被丢弃")));
        Map<String, Mschema.TableDef> tables = new LinkedHashMap<>();
        tables.put("film", new Mschema.TableDef("影片（租赁商品主数据）", filmFields));
        tables.put("agentx_trace", new Mschema.TableDef("trace（应被排除）", Map.of()));

        List<Mschema.FkDef> fks = List.of(
                new Mschema.FkDef("film", "language_id", "language", "language_id"));
        return new Mschema("mini_data_agent", tables, fks);
    }

    @Test
    void tableListContainsCommentAndCount() {
        String out = MschemaFormatter.formatTableList(sampleSchema());
        assertTrue(out.contains("mini_data_agent"));
        assertTrue(out.contains("film（影片（租赁商品主数据））"));
        assertTrue(out.contains("agentx_trace"));
    }

    @Test
    void describeContainsTypePkAndExamples() {
        String out = MschemaFormatter.formatTables(sampleSchema(), List.of("film"));
        assertTrue(out.contains("film_id: SMALLINT UNSIGNED, Primary Key, NOT NULL"));
        assertTrue(out.contains("Examples: [ACADEMY DINOSAUR, CHAMBER ITALIAN]"));
        assertFalse(out.contains("agentx_trace"), "describe 只输出指定表");
    }

    @Test
    void longExampleTruncatedTo30Chars() {
        String out = MschemaFormatter.formatTables(sampleSchema(), List.of("film"));
        // 30 字符截断：27 字符 + "..."
        assertTrue(out.contains("A Epic Drama of a Feminist ..."));
        assertFalse(out.contains("Mad Scientist"));
    }

    @Test
    void examplesCappedAtFive() {
        String out = MschemaFormatter.formatTables(sampleSchema(), List.of("film"));
        assertTrue(out.contains("NC-17"));
        assertFalse(out.contains("额外第六个会被丢弃"), "示例值最多 5 个");
    }

    @Test
    void foreignKeyRenderedWhenBothTablesSelected() {
        String out = MschemaFormatter.formatTables(sampleSchema(), List.of("film", "language"));
        assertTrue(out.contains("【Foreign keys】"));
        assertTrue(out.contains("film.language_id = language.language_id"));
    }

    @Test
    void foreignKeySkippedWhenRefTableMissing() {
        String out = MschemaFormatter.formatTables(sampleSchema(), List.of("film"));
        assertFalse(out.contains("【Foreign keys】"));
    }

    @Test
    void unknownTableIgnored() {
        String out = MschemaFormatter.formatTables(sampleSchema(), List.of("film", "not_exists"));
        assertTrue(out.contains("# film"));
        assertFalse(out.contains("not_exists"));
    }

    @Test
    void emptySchemaNoCrash() {
        Mschema empty = new Mschema("db", Map.of(), new ArrayList<>());
        assertTrue(MschemaFormatter.formatTableList(empty).contains("0 个表"));
        assertTrue(MschemaFormatter.formatAll(empty).contains("db"));
    }
}
