package com.llmetor.dataagent.dataagent.eval.dto;

import lombok.Data;

/**
 * 诊断 Agent 结构化输出（内核 RunnableParams.outputType + BeanOutputConverter 目标）。
 */
@Data
public class AgentDiagnoseOutput {

    /** 根源错误所在 session 编号 */
    private String rootSessionId;
    /** 根源错误轮次：第一次出错的轮，不是最后出错轮 */
    private Integer rootRound;
    /** 归因四选一：模型能力/提示词/工具/框架 */
    private String attribution;
    /** trace 证据（session、round、item_index、消息原文片段） */
    private String evidence;
    /** 问题分析 */
    private String analysis;
    /** 修改建议（可执行） */
    private String suggestion;
}
