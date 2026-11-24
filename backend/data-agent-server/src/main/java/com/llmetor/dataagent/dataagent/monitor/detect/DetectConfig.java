package com.llmetor.dataagent.dataagent.monitor.detect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检测配置（03 方案 §4.3，D6/D7）：每问检测前读一次配置表（频率极低不缓存）——
 * 读取顺序 = <b>配置表行 → 代码默认值</b>（表缺行/解析失败回退默认，永不让检测因配置缺失挂掉）；
 * {@code enabled=false} 的规则跳过 eval。yaml 总开关（进程级熔断）不在本类，见 AnomalyDetectService。
 *
 * <p>params 合并口径：表行 params 覆盖同名 key，代码 defaultParams 补齐缺失 key——
 * 新增阈值参数发布后，旧配置行自动拿到默认值。
 */
@Slf4j
public final class DetectConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean globalEnabled;                 // yaml 总开关（进程级熔断）
    private final Map<String, RuleCfg> byRule = new HashMap<>();

    /** 单规则合并后配置（enabled = 表行覆盖代码默认；params = 表行覆盖 + 默认补齐） */
    public record RuleCfg(boolean enabled, Map<String, Object> params) {
    }

    public DetectConfig(boolean globalEnabled) {
        this.globalEnabled = globalEnabled;
    }

    /** 空配置（单测/兜底）：全部规则按代码默认值与默认开关执行 */
    public static DetectConfig defaults() {
        return new DetectConfig(true);
    }

    public boolean globalEnabled() {
        return globalEnabled;
    }

    /** 测试/临时注入用（包内可见）：绕过读库直接塞合并配置 */
    void putRuleCfg(String ruleId, boolean enabled, Map<String, Object> params) {
        byRule.put(ruleId, new RuleCfg(enabled, params));
    }

    /** 规则开关：配置表行 → 代码默认（缺行默认） */
    public boolean enabled(String ruleId, DetectRule rule) {
        RuleCfg cfg = byRule.get(ruleId);
        return cfg != null ? cfg.enabled() : (rule == null || rule.defaultEnabled());
    }

    public int intParam(String ruleId, String key, int def) {
        Object v = param(ruleId, key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? def : Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** 原始参数值（V2 旧键兼容读：defaultMs/maxRoundMs、statFields/statKeys、writeWhitelist/writeAllowedUsers） */
    public Object rawParam(String ruleId, String key) {
        return param(ruleId, key);
    }

    /**
     * 整型参数 + 旧键兼容（V2 改名参数一个版本期）：先读新键，缺失回退旧键，再回退默认。
     */
    public int intParamCompat(String ruleId, String newKey, String oldKey, int def) {
        Object v = param(ruleId, newKey);
        if (v == null) {
            v = param(ruleId, oldKey);
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? def : Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** 列表参数 + 旧键兼容：新键缺失或为空时回退旧键 */
    public List<String> listParamCompat(String ruleId, String newKey, String oldKey, List<String> def) {
        List<String> v = listParam(ruleId, newKey, def);
        if (v == def || v.isEmpty()) {
            List<String> old = listParam(ruleId, oldKey, List.of());
            if (!old.isEmpty()) {
                return old;
            }
        }
        return v;
    }

    public List<String> listParam(String ruleId, String key, List<String> def) {
        Object v = param(ruleId, key);
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                String s = String.valueOf(o);
                if (!s.isBlank()) {
                    out.add(s);
                }
            }
            return out.isEmpty() ? def : out;
        }
        if (v instanceof String s && !s.isBlank()) {
            return List.of(s);
        }
        return def;
    }

    /** 字符串参数（正则等；缺行/类型不符回退默认） */
    public String stringParam(String ruleId, String key, String def) {
        Object v = param(ruleId, key);
        return v instanceof String s && !s.isBlank() ? s : def;
    }

    /**
     * map 型参数（V2：perToolMs / expectedOutput / requiredArgs / apiTools）。
     * 缺失或非对象 → def（代码默认；def 为空 map 时按「未配置」处理）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> mapParam(String ruleId, String key, Map<String, Object> def) {
        Object v = param(ruleId, key);
        if (v instanceof Map<?, ?> m && !m.isEmpty()) {
            return (Map<String, Object>) m;
        }
        return def;
    }

    /** map 型参数（无代码默认版：缺失 → 空 map，按「未配置」处理） */
    public Map<String, Object> mapParam(String ruleId, String key) {
        return mapParam(ruleId, key, Map.of());
    }

    private Object param(String ruleId, String key) {
        RuleCfg cfg = byRule.get(ruleId);
        return cfg == null ? null : cfg.params().get(key);
    }

    // ==================== 读库加载 / seed ====================

    /**
     * 每问检测前调用：读 agentx_monitor_detect_rule 全表（规则行数 ~10，代价可忽略）。
     * 表不存在/查询失败 → 空配置（全部回退代码默认），检测不因此挂掉。
     */
    public static DetectConfig load(JdbcTemplate jdbc, boolean globalEnabled, List<DetectRule> rules) {
        DetectConfig cfg = new DetectConfig(globalEnabled);
        Map<String, RuleRow> rows;
        try {
            rows = readRuleRows(jdbc);
        } catch (Exception e) {
            log.warn("[detect] 配置表读取失败，全部回退代码默认: {}", e.getMessage());
            return cfg;
        }
        for (DetectRule rule : rules) {
            RuleRow row = rows.get(rule.id());
            if (row == null) {
                cfg.byRule.put(rule.id(), new RuleCfg(rule.defaultEnabled(),
                        new LinkedHashMap<>(rule.defaultParams())));
                continue;
            }
            Map<String, Object> merged = new LinkedHashMap<>(rule.defaultParams());
            if (row.params() != null) {
                merged.putAll(row.params());
            }
            cfg.byRule.put(rule.id(), new RuleCfg(row.enabled(), merged));
        }
        return cfg;
    }

    /** 配置表一行（enabled 列 + params 解析后的 Map） */
    record RuleRow(boolean enabled, Map<String, Object> params) {
    }

    private static Map<String, RuleRow> readRuleRows(JdbcTemplate jdbc) throws Exception {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT rule_id, enabled, params FROM agentx_monitor_detect_rule");
        Map<String, RuleRow> out = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String ruleId = String.valueOf(row.get("rule_id"));
            boolean enabled = Boolean.TRUE.equals(row.get("enabled"))
                    || "t".equalsIgnoreCase(String.valueOf(row.get("enabled")))
                    || "true".equalsIgnoreCase(String.valueOf(row.get("enabled")));
            Map<String, Object> params = parseParams(row.get("params"));
            out.put(ruleId, new RuleRow(enabled, params));
        }
        return out;
    }

    /** 首次启动 seed 全量规则行（缺行才插 ON CONFLICT DO NOTHING；默认阈值/开关取代码） */
    public static void seed(JdbcTemplate jdbc, List<DetectRule> rules) {
        for (DetectRule rule : rules) {
            try {
                String params;
                try {
                    params = MAPPER.writeValueAsString(rule.defaultParams());
                } catch (Exception e) {
                    params = "{}";
                }
                jdbc.update("""
                        INSERT INTO agentx_monitor_detect_rule (rule_id, enabled, params, updated_by)
                        VALUES (?, ?, ?::jsonb, 'system-seed')
                        ON CONFLICT (rule_id) DO NOTHING
                        """, rule.id(), rule.defaultEnabled(), params);
            } catch (Exception e) {
                log.warn("[detect] 规则行 seed 失败（不影响启动，检测回退代码默认）: ruleId={}, err={}",
                        rule.id(), e.getMessage());
            }
        }
    }

    /** 解析 params 列（jsonb 列经 JDBC 可能给 PGobject/字符串），解析失败返回 null（回退默认） */
    static Map<String, Object> parseParams(Object raw) {
        try {
            String json = String.valueOf(raw); // PGobject.toString() 即 JSON 原文（驱动 runtime 依赖，不引编译期类型）
            if (json == null || json.isBlank() || "null".equals(json)) {
                return null;
            }
            JsonNode node = MAPPER.readTree(json);
            if (!node.isObject()) {
                return null;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> out.put(e.getKey(), toJava(e.getValue())));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static Object toJava(JsonNode node) {
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode n : node) {
                list.add(toJava(n));
            }
            return list;
        }
        if (node.isObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> m.put(e.getKey(), toJava(e.getValue())));
            return m;
        }
        return node.asText();
    }
}
