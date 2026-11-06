package com.llmetor.dataagent.dataagent.mschema;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SchemaProvider A 轨实现：M-Schema 实时内省（Redis 缓存）。
 */
@Component
public class MschemaSchemaProvider implements SchemaProvider {

    private final MschemaCacheService cacheService;

    public MschemaSchemaProvider(MschemaCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Override
    public String listTables() {
        return MschemaFormatter.formatTableList(cacheService.get());
    }

    @Override
    public String describeTables(List<String> tableNames) {
        return MschemaFormatter.formatTables(cacheService.get(), tableNames);
    }
}
