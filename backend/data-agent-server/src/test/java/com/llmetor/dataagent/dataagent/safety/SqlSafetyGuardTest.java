package com.llmetor.dataagent.dataagent.safety;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SqlSafetyGuard 单元测试（对应 M2-测试用例 B 组：B01-B10）。
 */
class SqlSafetyGuardTest {

    private final SqlSafetyGuard guard = new SqlSafetyGuard(200, 3);

    private ValidationResult v(String sql) {
        return guard.validate(sql);
    }

    // ── B01 非只读语句拦截 ──

    @Test
    void rejectUpdate() {
        ValidationResult r = v("UPDATE film SET title = 'x' WHERE film_id = 1");
        assertFalse(r.valid());
        assertTrue(r.reason().contains("SELECT/WITH"));
    }

    @Test
    void rejectDelete() {
        ValidationResult r = v("DELETE FROM film WHERE film_id = 1");
        assertFalse(r.valid());
    }

    @Test
    void rejectDrop() {
        ValidationResult r = v("DROP TABLE film");
        assertFalse(r.valid());
    }

    // ── B02 多语句串联 ──

    @Test
    void rejectMultiStatement() {
        ValidationResult r = v("SELECT 1; DROP TABLE film");
        assertFalse(r.valid());
        assertTrue(r.reason().contains("单条语句"));
    }

    // ── B03 危险函数黑名单（AST 全树） ──

    @Test
    void rejectSleepFunction() {
        ValidationResult r = v("SELECT sleep(5)");
        assertFalse(r.valid());
        assertTrue(r.reason().contains("sleep"));
    }

    @Test
    void rejectBenchmarkInSubquery() {
        ValidationResult r = v("SELECT * FROM film WHERE film_id IN (SELECT film_id FROM film WHERE BENCHMARK(1000000, MD5('x')) = 1)");
        assertFalse(r.valid());
        assertTrue(r.reason().contains("benchmark"));
    }

    @Test
    void rejectLoadFile() {
        ValidationResult r = v("SELECT LOAD_FILE('/etc/passwd')");
        assertFalse(r.valid());
        assertTrue(r.reason().contains("危险"));
    }

    // ── B04 写文件通道 ──

    @Test
    void rejectIntoOutfile() {
        ValidationResult r = v("SELECT title FROM film INTO OUTFILE '/tmp/x'");
        assertFalse(r.valid());
    }

    // ── B05 JOIN 数超限 ──

    @Test
    void rejectTooManyJoins() {
        String sql = "SELECT f.title FROM film f "
                + "JOIN film_actor fa ON f.film_id = fa.film_id "
                + "JOIN actor a ON fa.actor_id = a.actor_id "
                + "JOIN film_category fc ON f.film_id = fc.film_id "
                + "JOIN category c ON fc.category_id = c.category_id";
        ValidationResult r = v(sql);
        assertFalse(r.valid());
        assertTrue(r.reason().contains("JOIN"));
    }

    @Test
    void allowThreeJoins() {
        String sql = "SELECT f.title FROM film f "
                + "JOIN film_actor fa ON f.film_id = fa.film_id "
                + "JOIN actor a ON fa.actor_id = a.actor_id "
                + "JOIN film_category fc ON f.film_id = fc.film_id";
        ValidationResult r = v(sql);
        assertTrue(r.valid(), "3 个 JOIN 应放行，reason=" + r.reason());
    }

    // ── B06 无 ON 的 JOIN / CROSS JOIN（笛卡尔积） ──

    @Test
    void rejectJoinWithoutOn() {
        ValidationResult r = v("SELECT * FROM film f JOIN actor a");
        assertFalse(r.valid());
        assertTrue(r.reason().contains("ON/USING"), r.reason());
    }

    @Test
    void rejectCrossJoin() {
        ValidationResult r = v("SELECT * FROM film f CROSS JOIN actor a");
        assertFalse(r.valid());
        assertTrue(r.reason().contains("CROSS JOIN"));
    }

    // ── B07 缺 LIMIT 自动注入 ──

    @Test
    void injectMissingLimit() {
        ValidationResult r = v("SELECT title FROM film");
        assertTrue(r.valid());
        assertNotNull(r.safeSql());
        assertTrue(r.safeSql().toUpperCase().contains("LIMIT 200"), safeSqlOf(r));
    }

    // ── B08 超限 LIMIT 下调 ──

    @Test
    void capOversizedLimit() {
        ValidationResult r = v("SELECT title FROM film LIMIT 10000");
        assertTrue(r.valid());
        assertTrue(r.safeSql().toUpperCase().contains("LIMIT 200"), safeSqlOf(r));
        assertFalse(r.safeSql().contains("10000"), safeSqlOf(r));
    }

    @Test
    void keepSmallLimit() {
        ValidationResult r = v("SELECT title FROM film LIMIT 10");
        assertTrue(r.valid());
        assertTrue(r.safeSql().toUpperCase().contains("LIMIT 10"), safeSqlOf(r));
    }

    // ── B09 OFFSET 无 ORDER BY ──

    @Test
    void rejectOffsetWithoutOrderBy() {
        ValidationResult r = v("SELECT title FROM film LIMIT 10 OFFSET 5");
        assertFalse(r.valid());
        assertTrue(r.reason().contains("ORDER BY"));
    }

    @Test
    void allowOffsetWithOrderBy() {
        ValidationResult r = v("SELECT title FROM film ORDER BY film_id LIMIT 10 OFFSET 5");
        assertTrue(r.valid(), safeSqlOf(r));
    }

    // ── B10 CTE / UNION / 子查询分支覆盖 ──

    @Test
    void rejectUpdateWrappedInCte() {
        // WITH 内藏非 SELECT 语句：解析为非 Select 或形态校验失败
        ValidationResult r = v("WITH t AS (SELECT film_id FROM film) UPDATE film SET title = 'x'");
        assertFalse(r.valid());
    }

    @Test
    void rejectSleepHiddenInUnionBranch() {
        ValidationResult r = v("SELECT title FROM film UNION ALL SELECT sleep(3)");
        assertFalse(r.valid());
        assertTrue(r.reason().contains("sleep"));
    }

    @Test
    void rejectSleepHiddenInWhereSubquery() {
        ValidationResult r = v("SELECT title FROM film WHERE film_id IN (SELECT film_id FROM film WHERE sleep(1) = 1)");
        assertFalse(r.valid());
        assertTrue(r.reason().contains("sleep"));
    }

    @Test
    void rejectForUpdate() {
        ValidationResult r = v("SELECT title FROM film WHERE film_id = 1 FOR UPDATE");
        assertFalse(r.valid());
        assertTrue(r.reason().contains("FOR UPDATE"));
    }

    @Test
    void rejectSelectInto() {
        ValidationResult r = v("SELECT title INTO @t FROM film WHERE film_id = 1");
        assertFalse(r.valid());
    }

    // ── CTE / UNION 合法形态放行 ──

    @Test
    void allowValidCte() {
        ValidationResult r = v("""
                WITH top_films AS (
                    SELECT film_id, COUNT(*) AS cnt FROM rental GROUP BY film_id
                )
                SELECT f.title, t.cnt FROM film f JOIN top_films t ON f.film_id = t.film_id
                ORDER BY t.cnt DESC
                """);
        assertTrue(r.valid(), "合法 CTE 应放行，reason=" + r.reason());
    }

    @Test
    void allowValidUnion() {
        ValidationResult r = v("SELECT title FROM film LIMIT 5 UNION ALL SELECT title FROM film_text LIMIT 5");
        assertTrue(r.valid(), "合法 UNION 应放行，reason=" + r.reason());
    }

    // ── 杂项 ──

    @Test
    void rejectBlank() {
        ValidationResult r = v("   ");
        assertFalse(r.valid());
    }

    @Test
    void rejectUnparseable() {
        ValidationResult r = v("SELEC * FORM film");
        assertFalse(r.valid());
        assertTrue(r.reason().contains("解析失败"));
    }

    @Test
    void trailingSemicolonStripped() {
        ValidationResult r = v("SELECT title FROM film;");
        assertTrue(r.valid(), "尾分号应剥离，reason=" + r.reason());
    }

    @Test
    void invalidResultHasNoSafeSql() {
        ValidationResult r = v("UPDATE film SET title = 'x'");
        assertFalse(r.valid());
        assertNull(r.safeSql());
        assertEquals("OK", new ValidationResult(true, "OK", "SELECT 1").reason());
    }

    private static String safeSqlOf(ValidationResult r) {
        return "safeSql=" + r.safeSql();
    }
}
