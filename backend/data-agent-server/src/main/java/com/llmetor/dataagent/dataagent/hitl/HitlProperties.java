package com.llmetor.dataagent.dataagent.hitl;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 一期 HITL 配置属性（灰度开关 + 有效期 + 同类放行上限）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "data-agent.hitl")
public class HitlProperties {

    /** 全局灰度开关（默认开启） */
    private boolean enabled = true;

    /** 业务决策单默认有效期（分钟），默认 30 分钟 */
    private int defaultTtlMinutes = 30;

    /** 二期 HITL：快路径在线原地等待超时（秒），默认 60 秒；超时自动降级为慢路径 Park & Resume */
    private int inlineAwaitSeconds = 60;

    /** 会话级同类授权最大自动放行次数上限，默认 20 次 */
    private int grantMaxUses = 20;

    /** 会话级同类授权硬超时（小时），默认 2 小时 */
    private int grantTtlHours = 2;

    /** 按工具名粒度关闭 HITL（灰度回退名单） */
    private List<String> disabledTools = new ArrayList<>();
}
