package com.llmetor.miniagentx.interrupt;

import com.llmetor.miniagentx.model.PauseState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 中断快照存储——InMemory 实现（TTL 7 天，进程重启丢）。
 *
 * <p>M1 用内存版即可（docs/03：resume 留到 M3）；M3 交付 JdbcPauseStateStore
 * （agentx_pause_state 表）替换。
 */
public class InMemoryPauseStateStore implements PauseStateStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryPauseStateStore.class);

    private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    private record Entry(PauseState state, long savedAt) {
    }

    @Override
    public void save(PauseState state) {
        store.put(state.getConversationId(), new Entry(state, System.currentTimeMillis()));
        log.info("中断快照已保存(内存): convId={}, round={}, safePoint={}",
                state.getConversationId(), state.getCurrentRound(), state.getSafePoint());
    }

    @Override
    public PauseState get(String conversationId) {
        Entry e = store.get(conversationId);
        if (e == null) {
            return null;
        }
        if (System.currentTimeMillis() - e.savedAt() > TTL_MS) {
            store.remove(conversationId);
            return null;
        }
        return e.state();
    }

    @Override
    public boolean delete(String conversationId) {
        return store.remove(conversationId) != null;
    }

    @Override
    public boolean has(String conversationId) {
        return get(conversationId) != null;
    }
}
