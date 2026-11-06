package com.llmetor.dataagent.common;

import com.llmetor.dataagent.common.enums.DataScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DataScope 解析与多角色合并自测（不依赖 Spring 上下文）。
 */
class DataScopeTest {

    @Test
    void fromStringFailClosed() {
        assertEquals(DataScope.ALL, DataScope.fromString("ALL"));
        assertEquals(DataScope.DEPT_AND_SUB, DataScope.fromString("dept_and_sub"));
        assertEquals(DataScope.SELF, DataScope.fromString(null));
        assertEquals(DataScope.SELF, DataScope.fromString(" "));
        assertEquals(DataScope.SELF, DataScope.fromString("garbage"), "解析失败必须 fail-closed 回落 SELF");
    }

    @Test
    void maxTakesWiderScope() {
        assertEquals(DataScope.ALL, DataScope.max(DataScope.ALL, DataScope.SELF));
        assertEquals(DataScope.DEPT, DataScope.max(DataScope.SELF, DataScope.DEPT));
        assertEquals(DataScope.DEPT_AND_SUB, DataScope.max(DataScope.DEPT_AND_SUB, DataScope.DEPT));
        assertEquals(DataScope.SELF, DataScope.max(null, null), "null 视为 SELF");
    }
}
