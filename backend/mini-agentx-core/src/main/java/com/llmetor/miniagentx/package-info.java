/**
 * mini-agentx-core：自研 Agent 内核（纯框架模块，无业务依赖）。
 *
 * <p>M0 只建骨架，M1 按 docs/02-spring-ai-agentx-core功能清单.md 交付：
 * <ul>
 *   <li>ReAct 主循环（internalToolExecutionEnabled(false)、轮次 while、chunk 聚合、
 *       toolCall 分片合并、工具并发执行按序回灌、maxRounds forceFinal）</li>
 *   <li>7 种流式事件 + Reactor Sinks（每流必以 Paused 或 Complete 收尾）</li>
 *   <li>ThinkingMode.THINK_TAG：&lt;think&gt; 标签流式状态机解析（跨 chunk 前缀匹配）</li>
 *   <li>RunnableParams（toolParams 白名单注入 + ToolContext）</li>
 *   <li>会话三表持久化（original/working 双视图、CAS 幂等）</li>
 *   <li>打断（AgentTaskManager + interrupt → 快照 → Paused）</li>
 *   <li>trace 审计、结构化输出（BeanOutputConverter + JsonRepairUtil）</li>
 *   <li>模型接入 MiniMax M3（OpenAI 兼容端点）</li>
 * </ul>
 */
package com.llmetor.miniagentx;
