package com.llmetor.dataagent.dataagent.mschema;

import java.util.List;

/**
 * Schema 提供者接口——listTables / describeTables 工具的唯一数据入口。
 *
 * <p>M2 只实现 A 轨（M-Schema 实时内省 + Redis 缓存）；yml 静态字典轨（B 轨）不做，
 * 保留本接口抽象以便将来按 {@code data-agent.schema.provider} 配置切换。
 */
public interface SchemaProvider {

    /** 全库表清单（含中文描述 + 外键关联表） */
    String listTables();

    /** 指定表的字段详情（M-Schema 括号元组格式，含示例值与外键） */
    String describeTables(List<String> tableNames);
}
