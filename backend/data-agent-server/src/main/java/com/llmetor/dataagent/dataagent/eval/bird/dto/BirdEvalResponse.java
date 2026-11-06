package com.llmetor.dataagent.dataagent.eval.bird.dto;

/**
 * BIRD 评测单题响应（M9 自原版 dodo-agentx 零修改移植）。
 */
public record BirdEvalResponse(
        boolean success,
        String sql,
        String error,
        long durationMs
) {
}
