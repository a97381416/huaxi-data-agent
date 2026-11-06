package com.llmetor.dataagent.dataagent.prompt;

import com.llmetor.dataagent.dataagent.file.AgentxFile;
import com.llmetor.dataagent.dataagent.file.FileManageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话文件段构建器（M5）：把会话内的文件分两组写进 system prompt——
 * 【本次新上传】（一组，整体）+【历史文件】（按 session_id 分多轮，从旧到新），附指代触发规则。
 *
 * <p>指代消解口径：'这个/这些/它们/分析下' → 本次整组；'上一篇/上一个' → 历史最新一轮；
 * 明说文件名/序号 → 指定加载。D05 验收：运行时 prompt 含本次组 + 历史按轮分组及触发规则。
 */
@Component
@RequiredArgsConstructor
public class SessionFilesPromptBuilder {

    private final FileManageService fileManageService;

    /**
     * 拼装会话文件段；无文件返回空串。
     *
     * @param conversationId 会话 ID
     * @param currentFileIds 本次请求新上传的 fileId 列表（可空）
     */
    public String build(String conversationId, List<String> currentFileIds) {
        if (conversationId == null || conversationId.isBlank()) {
            return "";
        }
        List<AgentxFile> files = fileManageService.listByConversationId(conversationId);
        if (files.isEmpty()) {
            return "";
        }

        Set<String> currentSet = currentFileIds == null ? Set.of() : new LinkedHashSet<>(currentFileIds);
        List<AgentxFile> currentFiles = new ArrayList<>();
        Map<Long, List<AgentxFile>> historyBySession = new LinkedHashMap<>();
        for (AgentxFile f : files) {
            if (currentSet.contains(f.getFileId())) {
                currentFiles.add(f);
            } else {
                historyBySession.computeIfAbsent(f.getSessionId(), k -> new ArrayList<>()).add(f);
            }
        }
        int historyCount = historyBySession.values().stream().mapToInt(List::size).sum();

        List<String> parts = new ArrayList<>();
        parts.add("## 当前会话文件（重要）");
        parts.add("");

        if (currentFiles.isEmpty()) {
            parts.add("（本次无新文件上传，仅可追问以下历史文件）");
            parts.add("");
        } else {
            parts.add("### 本次新上传（" + currentFiles.size() + " 个，是一个整体）");
            parts.add("");
            parts.add(renderGroup(currentFiles));
            parts.add("");
        }

        if (historyCount > 0) {
            parts.add("### 历史文件（" + historyCount + " 个，按上传轮次分组，从旧到新）");
            parts.add("");
            int sessionIdx = 0;
            int sessionTotal = historyBySession.size();
            for (Map.Entry<Long, List<AgentxFile>> entry : historyBySession.entrySet()) {
                sessionIdx++;
                List<AgentxFile> groupFiles = entry.getValue();
                String tag = entry.getKey() == null ? "未知轮次" : "第 " + sessionIdx + " 轮";
                if (entry.getKey() != null && sessionTotal > 1) {
                    if (sessionIdx == 1) {
                        tag += "（最早）";
                    } else if (sessionIdx == sessionTotal) {
                        tag += "（最新）";
                    }
                }
                parts.add("【" + tag + "，" + groupFiles.size() + " 个】");
                parts.add(renderGroup(groupFiles));
                parts.add("");
            }
        }

        parts.add("【触发规则】");
        parts.add("- 用户说\"这个/这些/它们/总结一下/讲了什么/分析下\"等指代词或泛指时，默认指【本次新上传】整组，必须挨个调 analyzeFile(fileId, question) 加载");
        parts.add("- 用户说\"上一篇/上一个/刚才的文档\"时，默认指【历史文件】里最新一轮的全部");
        parts.add("- 用户明说某个文件名/序号时，按指定的加载");
        parts.add("- 用户问题没明示文件但语义上可能指文件时，默认尝试调 analyzeFile");
        parts.add("- 普通闲聊（天气、写代码、打招呼）不要调");

        return String.join("\n", parts);
    }

    /** 渲染一组文件清单，组内编号从 1 开始 */
    private String renderGroup(List<AgentxFile> groupFiles) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < groupFiles.size(); i++) {
            AgentxFile f = groupFiles.get(i);
            lines.add((i + 1) + ". fileId: " + f.getFileId()
                    + "，文件名: " + f.getFileName()
                    + "，类型: " + f.getFileType());
        }
        return String.join("\n", lines);
    }
}
