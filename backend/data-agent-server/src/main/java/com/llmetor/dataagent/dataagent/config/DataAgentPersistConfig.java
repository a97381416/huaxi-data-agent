package com.llmetor.dataagent.dataagent.config;

import com.llmetor.miniagentx.interrupt.JdbcPauseStateStore;
import com.llmetor.miniagentx.interrupt.PauseStateStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * M3 持久化设施装配：中断快照 JDBC 存储（agentx_pause_state 表）。
 *
 * <p>M1/M2 用 InMemoryPauseStateStore（重启丢、无消费方）；M3 resume 闭环上线后
 * 换 JDBC 实现（接口不变，构造即自动建表），DodoAgent 与 SessionService 共享单例。
 */
@Configuration
public class DataAgentPersistConfig {

    @Bean
    public PauseStateStore pauseStateStore(DataSource dataSource) {
        return new JdbcPauseStateStore(dataSource);
    }
}
