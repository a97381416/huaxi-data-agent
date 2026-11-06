package com.llmetor.dataagent.dataagent.safety;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 敏感字段脱敏（SQL 安全链第 ⑤ 步，docs/01 §2.4）：对查询结果按 JDBC 列元数据
 * 原地替换敏感列值为 {@code ********}。
 *
 * <p>三级匹配（docs/01 §2.4）：{@code 表名.列名} 全名匹配 → 纯列名兜底 → label 兜底。
 * 已知局限：AS 别名改写后的列丢失表归属（label 兜底能覆盖别名=列名的场景），
 * 记录在复刻缺陷清单，不修。
 *
 * <p>同一份 {@code mask-fields} 配置三处复用：本过滤器（结果集脱敏）、
 * MschemaIntrospector（示例值采集跳过）、UserContextBuilder（档案展示脱敏）。
 */
@Slf4j
@Service
public class SensitiveFilter {

    public static final String MASK = "********";

    /** 配置的 {@code 表.列} 全名集合（小写） */
    private final Set<String> fullNames = new HashSet<>();
    /** 配置的纯列名集合（小写，兜底匹配：无表归属信息时仍能拦住已知敏感列） */
    private final Set<String> bareColumns = new HashSet<>();

    private final boolean enabled;

    public SensitiveFilter(
            @Value("${data-agent.sensitive-filter.enabled:false}") boolean enabled,
            @Value("${data-agent.sensitive-filter.mask-fields:sys_user.password,user_profile.id_card,user_profile.home_address}")
            List<String> maskFields) {
        this.enabled = enabled;
        for (String field : maskFields) {
            if (field == null || field.isBlank()) {
                continue;
            }
            String trimmed = field.trim().toLowerCase();
            int dot = trimmed.lastIndexOf('.');
            if (dot >= 0) {
                fullNames.add(trimmed);
                bareColumns.add(trimmed.substring(dot + 1));
            } else {
                bareColumns.add(trimmed);
            }
        }
        log.info("[sensitive-filter] 脱敏初始化 | enabled={} | 全名={} | 纯列名={}",
                enabled, fullNames, bareColumns);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 某纯列名是否在 mask-fields 配置里（档案展示脱敏等复用场景用） */
    public boolean isSensitiveBareColumn(String columnName) {
        if (columnName == null) {
            return false;
        }
        return bareColumns.contains(columnName.toLowerCase(Locale.ROOT).trim());
    }

    /** 结果集脱敏（原地修改 rows 中的敏感值；失败仅 warn 不阻断） */
    public void filter(SqlResult result) {
        if (!enabled || result == null || !result.success() || result.rows().isEmpty()) {
            return;
        }
        try {
            int maskedCells = 0;
            for (Map<String, Object> row : result.rows()) {
                for (SqlResult.ColumnMeta meta : result.columnMetas()) {
                    Object value = row.get(meta.label());
                    if (value == null || MASK.equals(value)) {
                        continue;
                    }
                    if (isSensitive(meta)) {
                        row.put(meta.label(), MASK);
                        maskedCells++;
                    }
                }
            }
            if (maskedCells > 0) {
                log.info("[sensitive-filter] 已脱敏 {} 个敏感单元格", maskedCells);
            }
        } catch (Exception e) {
            // 脱敏是纵深防御的一环，故障不应炸掉已成功执行的查询；但要留痕
            log.warn("[sensitive-filter] 脱敏处理异常（结果未脱敏）: {}", e.toString());
        }
    }

    /** 列是否敏感：表.列 全名 → 纯列名兜底 → label 兜底（docs/01 §2.4 匹配顺序） */
    private boolean isSensitive(SqlResult.ColumnMeta meta) {
        String table = meta.tableName() == null ? "" : meta.tableName().toLowerCase();
        String column = meta.columnName() == null ? "" : meta.columnName().toLowerCase();
        String label = meta.label() == null ? "" : meta.label().toLowerCase();

        if (!table.isEmpty() && !column.isEmpty() && fullNames.contains(table + "." + column)) {
            return true;
        }
        return bareColumns.contains(column) || bareColumns.contains(label);
    }
}
