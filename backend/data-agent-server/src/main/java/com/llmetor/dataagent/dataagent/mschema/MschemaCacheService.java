package com.llmetor.dataagent.dataagent.mschema;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * M-Schema 缓存服务：启动自省 → Redis（TTL 7 天）+ 内存兜底；
 * 每天凌晨定时刷新；读取链 Redis → 内存 fallback → 兜底同步刷新 → 仍无则抛异常。
 */
@Slf4j
@Service
@EnableScheduling
public class MschemaCacheService {

    public static final String REDIS_KEY = "data-agent:mschema:schema";

    private final StringRedisTemplate redis;
    private final MschemaIntrospector introspector;
    private final ObjectMapper objectMapper;

    @Value("${data-agent.schema.mschema.exclude-tables:agentx_*}")
    private String excludeTablesConfig;

    /** M-H3 白名单：非空时只放行名单内表（精确名，小写），exclude 仍兜底 */
    @Value("${data-agent.schema.mschema.include-tables:}")
    private String includeTablesConfig;

    /** 内存兜底（Redis 不可用时使用） */
    private volatile Mschema fallback;

    public MschemaCacheService(StringRedisTemplate redis,
                               MschemaIntrospector introspector,
                               ObjectMapper objectMapper) {
        this.redis = redis;
        this.introspector = introspector;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    /** 定时刷新（默认凌晨 3 点） */
    @Scheduled(cron = "${data-agent.schema.mschema.refresh-cron:0 0 3 * * *}")
    public void refresh() {
        try {
            List<String> excludeTables = parseExcludeTables();
            List<String> includeTables = parseList(includeTablesConfig);
            Mschema schema = introspector.readSchema(null, null, excludeTables, includeTables);
            String json = objectMapper.writeValueAsString(schema);
            redis.opsForValue().set(REDIS_KEY, json, 7, TimeUnit.DAYS);
            this.fallback = schema;
            log.info("[data-agent] MSchema 刷新成功：{} 表 (白名单: {}张, 排除: {})，已缓存至 Redis",
                    schema.tables().size(),
                    includeTables.size(),
                    excludeTables.isEmpty() ? "无" : String.join(",", excludeTables));
        } catch (Exception e) {
            log.error("[data-agent] MSchema 刷新失败，将继续使用缓存", e);
        }
    }

    /** 获取当前 schema（优先 Redis，不可用时降级内存） */
    public Mschema get() {
        try {
            String json = redis.opsForValue().get(REDIS_KEY);
            if (json != null && !json.isEmpty()) {
                return objectMapper.readValue(json, Mschema.class);
            }
        } catch (Exception e) {
            log.debug("[data-agent] Redis 读取 MSchema 失败，降级到内存缓存: {}", e.getMessage());
        }
        if (fallback != null) {
            return fallback;
        }
        refresh();
        if (fallback != null) {
            return fallback;
        }
        throw new IllegalStateException("MSchema 不可用：Redis 和内存缓存均为空，请检查数据库连接");
    }

    private List<String> parseExcludeTables() {
        return parseList(excludeTablesConfig);
    }

    private List<String> parseList(String config) {
        if (config == null || config.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(config.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
