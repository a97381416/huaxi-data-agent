package com.llmetor.dataagent.dataagent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * agentx_chat_model 表实体（对话大模型配置管理，启动时自动建表，
 * 见 {@link ChatModelManager#ensureTableExists}）。
 *
 * <p>DB 是对话模型的权威源：启动/增删改启停后由 ChatModelManager 重建
 * {@link org.springframework.ai.chat.model.ChatModel} 实例缓存（热生效），
 * DodoAgent 每请求按前端传的 code 解析（application.yml 的 spring.ai.openai.*
 * 仅作首次启动种子与库空时的兜底）。
 *
 * <p>定位：只管<b>对话模型</b>（chat）。Embedding/多模态属基础设施配置，
 * 仍走 application.yml（data-agent.embeddings / data-agent.multimodal）。
 */
@Data
@TableName("agentx_chat_model")
public class AgentxChatModel implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 模型标识（唯一；创建后不可改——前端对话选模型传的就是它，也是实例缓存的键） */
    @TableField("code")
    private String code;

    /** 显示名（对话页选择器/管理页展示） */
    @TableField("name")
    private String name;

    /** 厂商（展示用，如 MiniMax / DeepSeek / 阿里云） */
    @TableField("provider")
    private String provider;

    /** OpenAI 兼容端点 Base URL（ completions 路径默认 {base_url}{completions_path} ） */
    @TableField("base_url")
    private String baseUrl;

    /** chat/completions 路径（默认 /v1/chat/completions；智谱 GLM 等 /v4 风格端点填 /chat/completions） */
    @TableField("completions_path")
    private String completionsPath;

    /** API Key 原文入库（内网学习项目不做加密，同 agentx_mcp_server 口径），接口层脱敏回传 */
    @TableField("api_key")
    private String apiKey;

    /** 真实模型名（透传给 OpenAI 兼容端点的 model 参数） */
    @TableField("model")
    private String model;

    /** 采样温度（0~2，默认 0.7 对齐 yml 主模型） */
    @TableField("temperature")
    private Double temperature;

    /** 连通测试/非流式调用读超时（毫秒） */
    @TableField("timeout_ms")
    private Integer timeoutMs;

    /** 1=启用（进选择器与解析范围）0=停用 */
    @TableField("enabled")
    private Integer enabled;

    /** 1=默认模型（全局唯一，由 ChatModelManager 维护；code 未传/失效时的落点） */
    @TableField("is_default")
    private Integer isDefault;

    /** 展示排序（小在前；默认移交也按它挑第一个启用模型） */
    @TableField("sort")
    private Integer sort;

    /** 备注（对话页选择器里的说明行，可空） */
    @TableField("remark")
    private String remark;

    /** 关思考请求参数（JSON 对象，前端关"深度思考"时注入请求体 extra_body；null = 不支持请求级关闭）。
     *  默认 {"enable_thinking":false}（Qwen/DashScope 风格）；GLM 风格为 {"thinking":{"type":"disabled"}} */
    @TableField("thinking_off_param")
    private String thinkingOffParam;

    /** 来源：yml=首次启动种子 / manual=管理界面手动创建 */
    @TableField("source")
    private String source;

    /** 最近一次连通测试失败原因（截断存储；成功时清空） */
    @TableField("last_error")
    private String lastError;

    @TableField("last_check_time")
    private LocalDateTime lastCheckTime;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
