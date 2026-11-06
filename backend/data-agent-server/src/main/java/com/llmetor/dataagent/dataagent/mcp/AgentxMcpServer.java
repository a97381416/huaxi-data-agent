package com.llmetor.dataagent.dataagent.mcp;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * agentx_mcp_server 表实体（M6 补充：MCP 服务管理，启动时自动建表，
 * 见 {@link McpServerManager#ensureTableExists}）。
 *
 * <p>DB 是 MCP 服务的权威源：启动/增删改启停后由 McpServerManager 建客户端入
 * {@link McpClientRegistry}，DodoAgent 每请求按注册表版本号同步工具（热生效）。
 * application.yml 的 data-agent.chart.* / tavily.* 仅作首次启动种子。
 */
@Data
@TableName("agentx_mcp_server")
public class AgentxMcpServer implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 服务标识（唯一；创建后不可改——它是注册表与目录里的键） */
    @TableField("name")
    private String name;

    /** 传输方式：STREAMABLE / SSE / STDIO（常量见 McpServerManager） */
    @TableField("transport")
    private String transport;

    /** STREAMABLE=服务端点；SSE=Base URL；STDIO=NULL */
    @TableField("url")
    private String url;

    /** SSE 专属端点路径（默认 /sse） */
    @TableField("sse_endpoint")
    private String sseEndpoint;

    /** STDIO 专属：启动命令（Windows 下如 npx.cmd） */
    @TableField("command")
    private String command;

    /** STDIO 专属：启动参数，JSON 数组字符串，如 ["-y","包名"] */
    @TableField("args")
    private String args;

    /** STDIO 专属：环境变量，JSON 对象字符串（可含密钥，接口层脱敏） */
    @TableField("env")
    private String env;

    /** HTTP 系认证：NONE / BEARER（STDIO 强制 NONE，凭据走 env） */
    @TableField("auth_type")
    private String authType;

    /** Bearer 凭据原文入库（内网学习项目不做加密），接口层脱敏回传 */
    @TableField("auth_token")
    private String authToken;

    /** 工具调用/握手超时（毫秒） */
    @TableField("timeout_ms")
    private Integer timeoutMs;

    /** 注入方式：ALWAYS=延迟层经 tool_search 发现 / ONLINE_TOGGLE=受对话页联网开关控制（常驻层） */
    @TableField("inject_mode")
    private String injectMode;

    /** 1=启用（进注册表）0=停用（客户端已关闭，工具消失） */
    @TableField("enabled")
    private Integer enabled;

    /** 来源：yml=首次启动种子 / manual=管理界面手动创建 */
    @TableField("source")
    private String source;

    /** 连接状态：OK / ERROR / UNKNOWN（停用回落 UNKNOWN） */
    @TableField("status")
    private String status;

    /** 最近一次连接失败原因（截断存储；STDIO 的 stderr 暂不落库，以异常消息为准） */
    @TableField("last_error")
    private String lastError;

    /** 最近一次成功拉取的工具个数 */
    @TableField("tool_count")
    private Integer toolCount;

    @TableField("last_check_time")
    private LocalDateTime lastCheckTime;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
