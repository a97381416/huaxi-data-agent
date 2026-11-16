package com.llmetor.dataagent.dataagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 对话模型新建/编辑请求体。
 *
 * <p>凭据"不修改"语义（编辑弹窗 Key 框回显脱敏值，原样回传即视为未修改）：
 * {@code apiKey} 为 null / 空串 / 含 "***" → 保持库中原值；其他 → 覆盖。
 * 非凭据字段：null → 保持原值（前端始终传当前表单值，等价全量更新）。
 *
 * @param code        模型标识（编辑时忽略，code 不可改）
 * @param name        显示名
 * @param provider    厂商
 * @param baseUrl          OpenAI 兼容端点 Base URL
 * @param completionsPath  chat/completions 路径（空 = 默认 /v1/chat/completions；GLM 等 /v4 风格端点用 /chat/completions）
 * @param apiKey      API Key（null/空/含 *** = 不修改）
 * @param model       真实模型名
 * @param temperature 采样温度（0~2）
 * @param timeoutMs   读超时毫秒（1000~600000）
 * @param isDefault   设为默认模型（true 时默认移交到本模型；false/null 保持现状——
 *                    系统始终保证有且仅有一个默认，取消默认只能靠"把默认设给别人"）
 * @param sort        展示排序
 * @param remark      备注
 * @param thinkingOffParam 关思考请求参数（JSON 对象，如 {"enable_thinking":false}；空 = 不支持请求级关闭）
 * @param enabled     仅创建时生效（true 默认；编辑走 /toggle 接口）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatModelUpsertRequest(
        String code,
        String name,
        String provider,
        String baseUrl,
        String completionsPath,
        String apiKey,
        String model,
        Double temperature,
        Integer timeoutMs,
        Boolean isDefault,
        Integer sort,
        String remark,
        String thinkingOffParam,
        Boolean enabled) {

    /** 凭据是否为"保持不变"语义（null/空串/含掩码标记 ***） */
    public boolean apiKeyUnchanged() {
        return apiKey == null || apiKey.isBlank() || apiKey.contains("***");
    }
}
