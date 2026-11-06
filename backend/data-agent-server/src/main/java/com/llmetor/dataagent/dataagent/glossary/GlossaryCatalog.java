package com.llmetor.dataagent.dataagent.glossary;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 静态 YAML 术语表加载器（classpath:schema/glossary.yml，启动时一次加载）。
 *
 * <p>这是原版 B 轨字典的 glossary 简化实现——M-Schema 实时内省负责表结构，
 * 本类只负责业务术语口径（term + description + sqlFragment + synonyms）。
 */
@Slf4j
@Component
public class GlossaryCatalog {

    private static final String GLOSSARY_RESOURCE = "schema/glossary.yml";

    private final List<GlossaryDesc> terms;

    public GlossaryCatalog() {
        this.terms = load();
    }

    public List<GlossaryDesc> listAll() {
        return terms;
    }

    private List<GlossaryDesc> load() {
        try (InputStream in = new ClassPathResource(GLOSSARY_RESOURCE).getInputStream()) {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(in);
            Object section = root == null ? null : root.get("glossary");
            if (!(section instanceof List<?> rawList)) {
                log.warn("[data-agent] glossary.yml 无 glossary 段或格式不对，术语表为空");
                return List.of();
            }
            List<GlossaryDesc> out = new ArrayList<>();
            for (Object item : rawList) {
                if (item instanceof Map<?, ?> m) {
                    out.add(new GlossaryDesc(
                            str(m.get("term")),
                            str(m.get("description")),
                            str(m.get("sqlFragment")),
                            synonyms(m.get("synonyms"))));
                }
            }
            log.info("[data-agent] glossary 加载完成：{} 条术语", out.size());
            return List.copyOf(out);
        } catch (IOException e) {
            throw new IllegalStateException("加载 " + GLOSSARY_RESOURCE + " 失败", e);
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> synonyms(Object v) {
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (v instanceof String s && !s.isBlank()) {
            return List.of(s);
        }
        return List.of();
    }
}
