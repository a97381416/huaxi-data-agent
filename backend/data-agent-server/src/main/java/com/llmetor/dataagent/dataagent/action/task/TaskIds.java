package com.llmetor.dataagent.dataagent.action.task;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 身份/ID/JSON 值解析小工具（M-E1）：toolParams 注入的是 String，JsonNode 取数
 * 的 null 语义也统一在这里，各工具不再各写一份 try/parse。
 */
final class TaskIds {

    private TaskIds() {
    }

    /** userId 字符串 → Long；空/非法返回 null（工具层给人话提示） */
    static Long parseUserId(String userId) {
        return parseLong(userId);
    }

    /** 通用 Long 解析：空/非法返回 null */
    static Long parseLong(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** JsonNode → Integer：非数值（缺失/null）返回 null（asInt 只收原生 int 默认值） */
    static Integer intOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.asInt() : null;
    }
}
