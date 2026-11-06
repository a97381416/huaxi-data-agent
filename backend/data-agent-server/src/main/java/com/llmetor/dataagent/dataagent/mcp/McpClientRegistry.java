package com.llmetor.dataagent.dataagent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 客户端运行时注册表（M6 补充：MCP 服务管理）——唯一的 MCP 可变状态收敛处。
 *
 * <ul>
 *   <li><b>存什么</b>：服务名 → 已连通客户端 + 缓存的工具回调（SyncMcpToolCallbackProvider
 *       包装，线程安全可跨请求复用）。只有 enabled=1 且握手+拉工具成功的服务才在表内，
 *       停用/失败/删除即移除并关闭客户端</li>
 *   <li><b>version</b>：put/remove 递增，DodoAgent 每请求比对，变更后重建延迟层目录与
 *       联网常驻工具清单——这就是"增删改下一轮对话热生效"的信号源</li>
 *   <li><b>谁来写</b>：仅 McpServerManager（用户操作触发或启动异步连通）；
 *       谁来读：DodoAgent（每请求）与 McpController（工具预览）</li>
 * </ul>
 */
@Component
public class McpClientRegistry {

    /** 一个已连通服务的运行时快照（injectMode 随行快照，避免读 DB 判注入层） */
    public record Entry(String serverName, McpSyncClient client, ToolCallback[] tools, String injectMode) {
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /** 变更版本号：增删换任何客户端都 +1 */
    private volatile long version = 0;

    /** 放入/替换（热重建），返回被顶掉的旧实例（调用方负责 close） */
    public synchronized Entry put(String serverName, McpSyncClient client, ToolCallback[] tools, String injectMode) {
        Entry old = entries.put(serverName, new Entry(serverName, client, tools, injectMode));
        version++;
        return old;
    }

    /** 移除，返回被移除实例（调用方负责 close）；不存在返回 null（不递增 version） */
    public synchronized Entry remove(String serverName) {
        Entry old = entries.remove(serverName);
        if (old != null) {
            version++;
        }
        return old;
    }

    public synchronized Entry get(String serverName) {
        return entries.get(serverName);
    }

    public synchronized List<Entry> all() {
        return List.copyOf(entries.values());
    }

    /** 收集指定注入方式（ALWAYS / ONLINE_TOGGLE）的全部工具，按注册顺序拼接 */
    public synchronized List<ToolCallback> toolsOfInjectMode(String injectMode) {
        List<ToolCallback> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.injectMode().equals(injectMode)) {
                out.addAll(List.of(e.tools()));
            }
        }
        return out;
    }

    public long version() {
        return version;
    }

    /** 全量清空并返回待关闭客户端（仅 @PreDestroy 与单测用） */
    public synchronized List<McpSyncClient> clear() {
        List<McpSyncClient> clients = entries.values().stream().map(Entry::client).toList();
        entries.clear();
        version++;
        return clients;
    }
}
