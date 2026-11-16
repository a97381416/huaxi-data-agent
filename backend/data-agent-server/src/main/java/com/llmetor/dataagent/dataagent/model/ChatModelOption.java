package com.llmetor.dataagent.dataagent.model;

/**
 * 对话页模型选择器选项（GET /agent/models 返回体，登录即可，非 admin——
 * 管理端 /api/models 含 api-key 脱敏值等管理字段，此处仅暴露选择所需最小字段）。
 *
 * @param code      模型标识（对话请求体 model 字段传它）
 * @param name      显示名
 * @param provider  厂商
 * @param isDefault 是否默认模型（前端标"默认"标签 + 未选时落点提示）
 * @param remark    备注说明行（可空）
 */
public record ChatModelOption(String code, String name, String provider, boolean isDefault, String remark) {
}
