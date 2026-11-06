package com.llmetor.dataagent.dataagent.safety;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * SensitiveFilter 单元测试（对应 M4-测试用例 C 组：C01/C02）。
 */
class SensitiveFilterTest {

    private static SqlResult result(SqlResult.ColumnMeta[] metas, Object[][] rows) {
        java.util.List<SqlResult.ColumnMeta> metaList = List.of(metas);
        java.util.List<String> columns = java.util.Arrays.stream(metas)
                .map(SqlResult.ColumnMeta::label).toList();
        java.util.List<Map<String, Object>> rowMaps = new java.util.ArrayList<>();
        for (Object[] row : rows) {
            java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
            for (int i = 0; i < metas.length; i++) {
                m.put(metas[i].label(), row[i]);
            }
            rowMaps.add(m);
        }
        return SqlResult.ok("SELECT 1", columns, metaList, rowMaps, false, 1L);
    }

    private static SqlResult.ColumnMeta meta(String label, String table, String column) {
        return new SqlResult.ColumnMeta(label, table, column);
    }

    @Test
    void masksFullNameMatch() {
        SensitiveFilter filter = new SensitiveFilter(true,
                List.of("sys_user.password", "user_profile.id_card", "user_profile.home_address"));
        SqlResult r = result(
                new SqlResult.ColumnMeta[]{
                        meta("real_name", "user_profile", "real_name"),
                        meta("id_card", "user_profile", "id_card"),
                        meta("home_address", "user_profile", "home_address")},
                new Object[][]{{"张明", "310101199405051008", "上海市闵行区莘庄500号"}});
        filter.filter(r);
        Map<String, Object> row = r.rows().get(0);
        assertEquals("张明", row.get("real_name"), "非敏感列原样");
        assertEquals("********", row.get("id_card"));
        assertEquals("********", row.get("home_address"));
    }

    @Test
    void masksBareColumnWhenTableUnknown() {
        // JDBC 元数据丢表归属（如视图/表达式列）时纯列名兜底
        SensitiveFilter filter = new SensitiveFilter(true,
                List.of("user_profile.id_card"));
        SqlResult r = result(
                new SqlResult.ColumnMeta[]{meta("id_card", "", "id_card")},
                new Object[][]{{"310101199405051008"}});
        filter.filter(r);
        assertEquals("********", r.rows().get(0).get("id_card"));
    }

    @Test
    void masksByLabelFallback() {
        // AS 别名与列名同名：label 兜底仍能拦住
        SensitiveFilter filter = new SensitiveFilter(true,
                List.of("user_profile.home_address"));
        SqlResult r = result(
                new SqlResult.ColumnMeta[]{meta("home_address", "", "")},
                new Object[][]{{"某地址"}});
        filter.filter(r);
        assertEquals("********", r.rows().get(0).get("home_address"));
    }

    @Test
    void nonSensitiveColumnsUntouched() {
        SensitiveFilter filter = new SensitiveFilter(true,
                List.of("user_profile.id_card"));
        SqlResult r = result(
                new SqlResult.ColumnMeta[]{meta("username", "sys_user", "username"),
                        meta("age", "user_profile", "age")},
                new Object[][]{{"sh_sales", 31}});
        filter.filter(r);
        assertEquals("sh_sales", r.rows().get(0).get("username"));
        assertEquals(31, r.rows().get(0).get("age"));
    }

    @Test
    void nullValuesSkipped() {
        SensitiveFilter filter = new SensitiveFilter(true,
                List.of("user_profile.home_address"));
        SqlResult r = result(
                new SqlResult.ColumnMeta[]{meta("home_address", "user_profile", "home_address")},
                new Object[][]{{null}});
        filter.filter(r);
        assertNull(r.rows().get(0).get("home_address"), "NULL 不应被替换成掩码");
    }

    @Test
    void disabledFilterDoesNothing() {
        SensitiveFilter filter = new SensitiveFilter(false,
                List.of("user_profile.id_card"));
        SqlResult r = result(
                new SqlResult.ColumnMeta[]{meta("id_card", "user_profile", "id_card")},
                new Object[][]{{"310101199405051008"}});
        filter.filter(r);
        assertEquals("310101199405051008", r.rows().get(0).get("id_card"));
    }

    @Test
    void bareColumnConfigSupported() {
        // 配置里不带表名（纯列名）也应生效
        SensitiveFilter filter = new SensitiveFilter(true, List.of("id_card"));
        SqlResult r = result(
                new SqlResult.ColumnMeta[]{meta("id_card", "user_profile", "id_card")},
                new Object[][]{{"x"}});
        filter.filter(r);
        assertEquals("********", r.rows().get(0).get("id_card"));
    }

    @Test
    void bareColumnLookupForContextBuilder() {
        SensitiveFilter filter = new SensitiveFilter(true,
                List.of("sys_user.password", "user_profile.id_card", "user_profile.home_address"));
        org.junit.jupiter.api.Assertions.assertTrue(filter.isSensitiveBareColumn("ID_CARD"));
        org.junit.jupiter.api.Assertions.assertTrue(filter.isSensitiveBareColumn("password"));
        org.junit.jupiter.api.Assertions.assertFalse(filter.isSensitiveBareColumn("real_name"));
    }

    @Test
    void failedResultIgnored() {
        SensitiveFilter filter = new SensitiveFilter(true, List.of("user_profile.id_card"));
        SqlResult fail = SqlResult.fail("select 1", "boom", 1L);
        filter.filter(fail); // 不应抛异常
        assertEquals(false, fail.success());
    }
}
