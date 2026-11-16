package com.llmetor.dataagent.dataagent.model;

import java.time.LocalDateTime;

/**
 * 对话模型配置 VO（/api/models 返回体，管理端）。
 *
 * <p>凭据脱敏契约：apiKey → maskSecret 形态（sk-***b4，同 agentx_mcp_server 口径）。
 * 前端回传含 "***"（或空）的值视为未修改，见 ChatModelManager#mergeSecret。
 *
 * @param id            主键
 * @param code          模型标识（唯一，创建后不可改；对话选模型传它）
 * @param name          显示名
 * @param provider      厂商
 * @param baseUrl          OpenAI 兼容端点 Base URL
 * @param completionsPath  chat/completions 路径（已归一化，默认 /v1/chat/completions）
 * @param apiKeyMasked  API Key 脱敏形态（未配置为空串）
 * @param model         真实模型名
 * @param temperature   采样温度
 * @param timeoutMs     读超时毫秒
 * @param enabled       是否启用
 * @param isDefault     是否默认模型
 * @param sort          展示排序
 * @param remark        备注
 * @param thinkingOffParam 关思考请求参数（JSON 对象，空 = 不支持请求级关闭）
 * @param source        yml / manual
 * @param lastError     最近一次连通测试失败原因
 * @param lastCheckTime 最近连通测试时间
 */
public record ChatModelInfo(
        Long id,
        String code,
        String name,
        String provider,
        String baseUrl,
        String completionsPath,
        String apiKeyMasked,
        String model,
        Double temperature,
        Integer timeoutMs,
        boolean enabled,
        boolean isDefault,
        Integer sort,
        String remark,
        String thinkingOffParam,
        String source,
        String lastError,
        LocalDateTime lastCheckTime,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
