package com.llmetor.dataagent.dataagent.tools;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * 任务列表管理工具（M3 新增，对齐原框架 TodoWriteTool）。
 *
 * <p>把隐式规划转为显式、可观测的工作流：LLM 对多步骤任务先建清单再逐步推进；
 * 前端从 TOOL_START 事件的参数侧信道解析 todos 渲染 todo 面板（工具结果本身无状态，
 * 不落库——todo 状态由每轮 TodoWrite 调用的全量参数表达）。
 */
public class TodoWriteTool {

    @Tool(name = "TodoWrite", description = """
            创建和管理结构化任务列表，用于跟踪多步骤任务的进度。

            ## 使用规则
            1. 收到多步骤任务（3 步以上）时，先调用此工具创建任务列表（全部 pending），再开始执行
            2. 每开始一个任务前，将其标记为 in_progress；每完成一个任务后，立即标记为 completed
            3. 执行过程中发现新步骤时，立即加入列表
            4. 简单单步任务、信息性问答不要使用

            ## 参数说明
            - content：祈使形式，描述需要做什么（如"统计各分类电影数量"）
            - status：pending（未开始）、in_progress（执行中）、completed（已完成）
            """)
    public String todoWrite(@ToolParam(description = "任务列表，包含所有任务的当前状态") List<TodoItem> todos) {
        if (todos == null || todos.isEmpty()) {
            throw new IllegalArgumentException("任务列表不能为空");
        }
        for (TodoItem item : todos) {
            if (item == null || item.content() == null || item.content().isBlank()) {
                throw new IllegalArgumentException("每个任务必须有有意义的 content");
            }
            if (item.status() == null) {
                throw new IllegalArgumentException("任务状态必须是 pending/in_progress/completed 之一");
            }
        }
        long inProgress = todos.stream().filter(t -> t.status() == Status.in_progress).count();
        if (inProgress > 1) {
            throw new IllegalArgumentException("同一时间只能有一个任务处于 in_progress 状态");
        }
        return "任务列表已成功更新。请使用任务列表跟踪你的进度，并继续执行当前的任务。";
    }

    /** 单条任务项（content + status 全量表达；无持久化，状态由每轮参数覆盖） */
    public record TodoItem(
            @ToolParam(description = "任务内容，祈使形式") String content,
            @ToolParam(description = "pending（未开始）、in_progress（执行中）、completed（已完成）") Status status) {
    }

    public enum Status {
        pending, in_progress, completed
    }

    /** 工厂方法：编程式构建 ToolCallback（与注解工具统一合入装配） */
    public static ToolCallback create() {
        return ToolCallbacks.from(new TodoWriteTool())[0];
    }
}
