package com.llmetor.miniagentx.interrupt;

import com.llmetor.miniagentx.model.PauseState;

/**
 * 中断快照存储接口。M1 实现：InMemory；M3 交付 JdbcPauseStateStore。
 */
public interface PauseStateStore {

    void save(PauseState state);

    PauseState get(String conversationId);

    /** @return true=删除成功（可作并发恢复 CAS） */
    boolean delete(String conversationId);

    boolean has(String conversationId);
}
