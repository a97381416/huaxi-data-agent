package com.llmetor.dataagent.dataagent.eval.bird.dto;

/**
 * BIRD 评测单题请求（M9 自原版 dodo-agentx 零修改移植）。
 *
 * @param questionId  题目 ID（原样透传，用于结果回写）
 * @param dbId        BIRD 数据库 ID（如 california_schools）
 * @param question    自然语言问题
 * @param evidence    BIRD 外部知识提示（没有时传空串）
 * @param sqlitePath  本题 SQLite 文件绝对路径，由 Python runner 拼好传入
 * @param maxRounds   ReactAgent 最大轮次，不传走默认值
 */
public record BirdEvalRequest(
        String questionId,
        String dbId,
        String question,
        String evidence,
        String sqlitePath,
        Integer maxRounds
) {
}
