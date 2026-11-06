package com.llmetor.dataagent.dataagent.dto;

import java.util.List;

/**
 * 流式问答请求体（M3；M5 增加 fileIds；M6 增加 online）。
 *
 * @param query          用户问题（recovery 恢复请求可为空，不新起执行）
 * @param conversationId 会话窗口 ID
 * @param recovery       是否恢复请求：true 时不新起执行，接回该会话进行中的流（断线/刷新后续传）
 * @param startSeq       恢复时从第几个事件开始收；null/0/1 表示从头全量补发，N 表示增量续传
 * @param fileIds        本次新上传的文件 ID 列表（M5，可空；启动时 link 到会话，结束后按轮补 link）
 * @param online         联网开关（M6）：true 时注入 Tavily 联网搜索工具（未配 key 自动降级不注入）
 */
public record ChatRequest(String query, String conversationId, Boolean recovery, Long startSeq,
                          List<String> fileIds, Boolean online) {
}
