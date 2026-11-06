package com.llmetor.miniagentx.tools.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 延迟工具注册表（docs/02 §7 三件套之一）——tool_search 延迟工具发现的存储侧。
 *
 * <ul>
 *   <li><b>注册</b>：LinkedHashMap 保序；<b>重名注册 warn 并保留先注册者</b>
 *       （修正原框架"静默覆盖"缺陷，见 docs/03 §4 缺陷清单）</li>
 *   <li><b>catalog 预分词</b>：注册时构建、跨请求共享，搜索零重复分词开销</li>
 *   <li><b>每请求 Session</b>：{@link Session} 的 discoveredNames 请求内跨轮累积、
 *       请求间隔离——发现的工具本轮可见，下一轮即可调用，新请求重新发现</li>
 * </ul>
 *
 * <p>线程模型：注册集中在启动期（单线程）；Session 的搜索/发现可能并发于
 * 工具执行线程池，方法级同步。
 */
public class DeferredToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(DeferredToolRegistry.class);

    private final ToolSearchConfig config;
    /** 注册顺序即目录顺序 */
    private final Map<String, ToolCallback> deferred = new LinkedHashMap<>();
    /** 预分词 catalog（惰性失效重建；读多写少，synchronized 保护） */
    private Map<String, KeywordToolSearcher.CatalogEntry> catalog;

    public DeferredToolRegistry() {
        this(ToolSearchConfig.keywordDefault());
    }

    public DeferredToolRegistry(ToolSearchConfig config) {
        this.config = config == null ? ToolSearchConfig.keywordDefault() : config;
    }

    /** 注册一个延迟工具；重名 warn 并保留先注册者 */
    public synchronized void register(ToolCallback tool) {
        String name = tool.getToolDefinition().name();
        ToolCallback prev = deferred.putIfAbsent(name, tool);
        if (prev != null) {
            log.warn("[tool_search] 延迟工具重名注册被忽略: {}（保留先注册者）", name);
            return;
        }
        catalog = null; // 失效，下次搜索重建
        log.debug("[tool_search] 注册延迟工具: {}", name);
    }

    /** 批量注册 */
    public synchronized void registerAll(List<ToolCallback> tools) {
        if (tools != null) {
            tools.forEach(this::register);
        }
    }

    /**
     * 按名反注册（M6 MCP 服务管理动态卸载用：服务停用/删除/重建后旧工具出目录）；
     * 返回是否实际移除。运行期调用线程安全（方法级同步），变更后 catalog 惰性重建。
     */
    public synchronized boolean unregister(String name) {
        ToolCallback removed = deferred.remove(name);
        if (removed != null) {
            catalog = null;
            log.debug("[tool_search] 反注册延迟工具: {}", name);
            return true;
        }
        return false;
    }

    /** 批量反注册，返回实际移除个数 */
    public synchronized int unregisterAll(Collection<String> names) {
        int removed = 0;
        if (names != null) {
            for (String name : names) {
                if (unregister(name)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    public synchronized int size() {
        return deferred.size();
    }

    public synchronized List<String> names() {
        return List.copyOf(deferred.keySet());
    }

    /** 按名取延迟工具（含已注册未发现的；供执行器解析工具调用） */
    public synchronized ToolCallback get(String name) {
        return deferred.get(name);
    }

    /** 开启一次请求的发现会话 */
    public Session newSession() {
        return new Session();
    }

    /**
     * 每请求发现会话：请求内跨轮累积、请求间隔离。
     */
    public final class Session {

        private final Set<String> discoveredNames = new LinkedHashSet<>();

        /** 当前已发现的工具（保发现顺序；每轮重建 roundTools 时取用） */
        public synchronized List<ToolCallback> discoveredTools() {
            List<ToolCallback> list = new ArrayList<>(discoveredNames.size());
            for (String name : discoveredNames) {
                ToolCallback cb = DeferredToolRegistry.this.get(name);
                if (cb != null) {
                    list.add(cb);
                }
            }
            return list;
        }

        public synchronized boolean isDiscovered(String name) {
            return discoveredNames.contains(name);
        }

        /** 按名取延迟工具回调（供 tool_search 渲染定义、执行器解析调用） */
        public synchronized ToolCallback callbackOf(String name) {
            return DeferredToolRegistry.this.get(name);
        }

        /**
         * KEYWORD 搜索 + 登记发现：返回得分 >0 的 Top maxResults（已并入 discovered）。
         */
        public synchronized List<KeywordToolSearcher.SearchHit> search(String query) {
            List<KeywordToolSearcher.SearchHit> hits = searchOnly(query);
            for (KeywordToolSearcher.SearchHit hit : hits) {
                discoveredNames.add(hit.name());
            }
            if (!hits.isEmpty()) {
                log.info("[tool_search] 发现 {} 个工具: {}（query={}）",
                        hits.size(), hits.stream().map(KeywordToolSearcher.SearchHit::name).toList(), query);
            }
            return hits;
        }

        /** 只搜索不登记（供"预览"语义与单测） */
        public synchronized List<KeywordToolSearcher.SearchHit> searchOnly(String query) {
            List<String> queryTokens = KeywordToolSearcher.tokenize(query);
            List<KeywordToolSearcher.SearchHit> hits = new ArrayList<>();
            for (KeywordToolSearcher.CatalogEntry entry : catalog().values()) {
                double score = KeywordToolSearcher.score(entry, query, queryTokens);
                if (score > 0) {
                    hits.add(new KeywordToolSearcher.SearchHit(entry.name(), score));
                }
            }
            hits.sort(Comparator.comparingDouble(KeywordToolSearcher.SearchHit::score).reversed());
            return hits.subList(0, Math.min(hits.size(), config.maxResults()));
        }

        /** catalog 惰性重建（注册表变更后首次搜索时） */
        private synchronized Map<String, KeywordToolSearcher.CatalogEntry> catalog() {
            Map<String, KeywordToolSearcher.CatalogEntry> c = catalog;
            if (c == null) {
                c = new LinkedHashMap<>();
                for (Map.Entry<String, ToolCallback> e : deferred.entrySet()) {
                    c.put(e.getKey(), KeywordToolSearcher.CatalogEntry.of(
                            e.getKey(), e.getValue().getToolDefinition().description()));
                }
                catalog = c;
            }
            return c;
        }
    }
}
