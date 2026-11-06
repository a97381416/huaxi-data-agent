package com.llmetor.dataagent.dataagent.safety;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * ExplainPrecheckService 单元测试（对应 M4-测试用例 A 组的规则评估分支）。
 * evaluate() 直接构造计划行验证两条拦截规则；聚合放行走 precheck 文本判断（不触数据库）。
 */
class ExplainPrecheckServiceTest {

    private ExplainPrecheckService service;

    @BeforeEach
    void setUp() {
        service = new ExplainPrecheckService(mock(DataSource.class));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "timeoutSeconds", 5);
        ReflectionTestUtils.setField(service, "maxEstimatedRows", 100_000L);
    }

    // ── 聚合放行（M4-A01） ──

    @Test
    void aggregateQueryAllowedWithoutDb() {
        // 聚合文本判断在 DB 访问之前，DataSource 未被调用（mock 不打桩即通过）
        ExplainVerdict v = service.precheck(
                "SELECT dept_id, COUNT(*) FROM payment GROUP BY dept_id ORDER BY dept_id");
        assertTrue(v.allowed());
    }

    @Test
    void aggregateWithHavingAllowed() {
        ExplainVerdict v = service.precheck(
                "SELECT customer_id FROM payment GROUP BY customer_id HAVING SUM(amount) > 100");
        assertTrue(v.allowed());
    }

    @Test
    void maxFunctionAggregateAllowed() {
        ExplainVerdict v = service.precheck("SELECT MAX(rental_date) FROM rental");
        assertTrue(v.allowed());
    }

    // ── 规则 1：大表明细扫描（M4-A02） ──

    @Test
    void fullScanAboveThresholdBlocked() {
        ExplainVerdict v = service.evaluate(List.of(
                new ExplainPrecheckService.PlanRow("payment", "Seq Scan", 150_000, null)));
        assertFalse(v.allowed());
        assertTrue(v.reason().contains("大表明细扫描"));
    }

    @Test
    void indexScanAboveThresholdAllowed() {
        // M-H1 PG：Index Scan 是有索引访问（非全扫），估算行数大也不按"全扫明细"拦
        ExplainVerdict v = service.evaluate(List.of(
                new ExplainPrecheckService.PlanRow("payment", "Index Scan", 100_000, null)));
        assertTrue(v.allowed());
    }

    @Test
    void fullScanBelowThresholdAllowed() {
        ExplainVerdict v = service.evaluate(List.of(
                new ExplainPrecheckService.PlanRow("payment", "Seq Scan", 99_999, null)));
        assertTrue(v.allowed());
    }

    @Test
    void indexedAccessEvenWithHugeRowsAllowed() {
        // M-H1 PG：Index Scan 等索引访问，估算行数再大也不按"全扫明细"拦（有索引条件命中）
        ExplainVerdict v = service.evaluate(List.of(
                new ExplainPrecheckService.PlanRow("payment", "Index Scan", 500_000, null)));
        assertTrue(v.allowed());
    }

    // ── 规则 2：JOIN 扩张（M4-A03） ──

    @Test
    void joinBlowupWithRiskSignalsBlocked() {
        // 两行都低于规则 1 阈值（10 万），乘积 80000×90000 远超 JOIN 阈值（100 万）
        // 且第 2 行全扫命中"非驱动表全扫"信号 → 规则 2 拦截
        ExplainVerdict v = service.evaluate(List.of(
                new ExplainPrecheckService.PlanRow("customer", "Seq Scan", 80_000, null),
                new ExplainPrecheckService.PlanRow("payment", "Seq Scan", 90_000, null)));
        assertFalse(v.allowed());
        assertTrue(v.reason().contains("JOIN 扩张"));
    }

    @Test
    void nonDrivingSeqScanSignalBlocked() {
        // M-H1 PG：无 "Using join buffer" 信号，等价风险 = 非驱动表（第 2 节点起）Seq Scan
        ExplainVerdict v = service.evaluate(List.of(
                new ExplainPrecheckService.PlanRow("customer", "Seq Scan", 50_000, null),
                new ExplainPrecheckService.PlanRow("payment", "Seq Scan", 90_000, null)));
        assertFalse(v.allowed());
        assertTrue(v.reason().contains("非驱动表全表扫描"));
    }

    @Test
    void joinBlowupWithoutRiskSignalsAllowed() {
        // 乘积 90000×20000 超阈值，但驱动表全扫（首行）+ 另一表走索引 → 无风险信号，放行
        ExplainVerdict v = service.evaluate(List.of(
                new ExplainPrecheckService.PlanRow("customer", "Seq Scan", 90_000, null),
                new ExplainPrecheckService.PlanRow("payment", "Index Scan", 20_000, null)));
        assertTrue(v.allowed());
    }

    @Test
    void smallJoinAllowed() {
        ExplainVerdict v = service.evaluate(List.of(
                new ExplainPrecheckService.PlanRow("customer", "Index Scan", 100, null),
                new ExplainPrecheckService.PlanRow("payment", "Index Scan", 200, null)));
        assertTrue(v.allowed());
    }

    @Test
    void emptyPlanAllowed() {
        assertTrue(service.evaluate(List.of()).allowed());
    }
}
