package com.llmetor.dataagent.dataagent.prompt;


import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 系统主提示词——正文外置在 classpath:prompts/system-prompt.md，类加载时读入缓存。
 *
 * <p>提示词统一放 resources/prompts/ 目录管理，正文改动不需要改 Java 代码、重新编译即可生效（重启后生效）；
 * 后续新增提示词也放该目录，保持"提示词不写死在代码里"的约定。
 */
@Slf4j
public final class SystemPrompt {

    /** 主提示词资源路径（resources/prompts/system-prompt.md） */
    private static final String BASE_PROMPT_RESOURCE = "prompts/system-prompt.md";

    private static final String BASE_PROMPT = load();

    private SystemPrompt() {
    }

    /** 拼装系统提示词：BASE_PROMPT（身份 + Skills 机制 + 原则 + 输出规范） + 运行时用户上下文 */
    public static String build(String userContext) {
        StringBuilder sb = new StringBuilder(BASE_PROMPT);
        if (userContext != null && !userContext.isBlank()) {
            sb.append(userContext);
        }
        return sb.toString();
    }

    private static String load() {
        try (InputStream in = new ClassPathResource(BASE_PROMPT_RESOURCE).getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            log.info("[data-agent] 主提示词加载完成：{}（{} 字符）", BASE_PROMPT_RESOURCE, text.length());
            return text;
        } catch (IOException e) {
            throw new IllegalStateException("加载 " + BASE_PROMPT_RESOURCE + " 失败", e);
        }
    }
}
