package com.llmetor.dataagent.dataagent.dto;

import java.util.List;

/**
 * 流式问答请求体（M3；M5 增加 fileIds；M6 增加 online；本轮增加 model）。
 *
 * @param query          用户问题（recovery 恢复请求可为空，不新起执行）
 * @param conversationId 会话窗口 ID
 * @param recovery       是否恢复请求：true 时不新起执行，接回该会话进行中的流（断线/刷新后续传）
 * @param startSeq       恢复时从第几个事件开始收；null/0/1 表示从头全量补发，N 表示增量续传
 * @param fileIds        本次新上传的文件 ID 列表（M5，可空；启动时 link 到会话，结束后按轮补 link）
 * @param online         联网开关（M6）：true 时注入 Tavily 联网搜索工具（未配 key 自动降级不注入）
 * @param skill          用户指定的技能名（斜杠命令，可空）：非空时 SkillsTool 仅装该技能并注入优先加载指令
 * @param model          用户选择的对话模型 code（可空）：非空时按 agentx_chat_model 解析
 *                       （不存在/停用回退默认），空走默认模型；库空走 yml 兜底 bean
 * @param thinking       深度思考开关（可空 = 默认开启）：false 时按所选模型注入关思考请求参数
 *                       （agentx_chat_model.thinking_off_param，如 {"enable_thinking":false}）
 * @param agentMode      架构模式调试覆盖（可空）：single / multi；空则取 data-agent.agent.mode
 */
public record ChatRequest(String query, String conversationId, Boolean recovery, Long startSeq,
                          List<String> fileIds, Boolean online, String skill, String model,
                          Boolean thinking, String agentMode) {
}
