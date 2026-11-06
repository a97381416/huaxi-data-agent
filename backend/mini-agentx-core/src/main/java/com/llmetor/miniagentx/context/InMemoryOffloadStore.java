package com.llmetor.miniagentx.context;

import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 OffloadStore：无 DB Agent 与单测用；进程重启即失（可接受——
 * offload 原文只服务"同会话后续轮次取回"，跨进程丢失退化为取不到原文）。
 */
public class InMemoryOffloadStore implements OffloadStore {

    private final Map<String, List<Message>> store = new ConcurrentHashMap<>();

    @Override
    public void save(String conversationId, String uuid, List<Message> messages) {
        store.put(key(conversationId, uuid), new ArrayList<>(messages));
    }

    @Override
    public List<Message> load(String conversationId, String uuid) {
        List<Message> messages = store.get(key(conversationId, uuid));
        return messages == null ? null : List.copyOf(messages);
    }

    private static String key(String conversationId, String uuid) {
        return conversationId + "::" + (uuid == null ? "" : uuid);
    }

    /** 便于测试/调试：生成新 uuid（真实形态就是随机 UUID 字符串） */
    public static String newUuid() {
        return UUID.randomUUID().toString();
    }
}
