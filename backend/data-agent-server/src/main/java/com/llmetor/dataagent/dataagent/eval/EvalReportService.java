package com.llmetor.dataagent.dataagent.eval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 评测报告查询服务（M8）：读 scripts/agent/report/ 下的 runner 产出
 * （agent_results.report.md 基线报告 + agent_diagnosis_*.md 错题诊断报告），
 * 供前端"评测报告"页渲染。只读，文件名做白名单校验防路径穿越。
 */
@Slf4j
@Service
public class EvalReportService {

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_\\-.]+\\.md");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path reportDir;

    public EvalReportService(
            @Value("${data-agent.agent-eval.report-dir:../scripts/agent/report}") String reportDir) {
        this.reportDir = Path.of(reportDir);
    }

    /** 报告清单（按修改时间倒序） */
    public List<Map<String, Object>> list() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(reportDir)) {
            return out;
        }
        try (var stream = Files.list(reportDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing((Path p) -> {
                        try {
                            return Files.readAttributes(p, BasicFileAttributes.class).lastModifiedTime();
                        } catch (IOException e) {
                            return java.nio.file.attribute.FileTime.from(Instant.EPOCH);
                        }
                    }).reversed())
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        String title = name.replace("agent_results.report.md", "评测基线报告")
                                .replace("agent_diagnosis_", "错题诊断 ")
                                .replaceAll("\\.md$", "");
                        long size = 0;
                        String modifiedAt = "";
                        try {
                            var attr = Files.readAttributes(p, BasicFileAttributes.class);
                            size = attr.size();
                            modifiedAt = TS.format(attr.lastModifiedTime().toInstant().atZone(ZoneId.systemDefault()));
                        } catch (IOException ignored) {
                            // 清单容错：属性读不到就留空
                        }
                        out.add(Map.of("name", name, "title", title, "size", size, "modifiedAt", modifiedAt));
                    });
        }
        return out;
    }

    /** 报告原文（markdown 文本） */
    public String content(String name) throws IOException {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("非法文件名: " + name);
        }
        Path p = reportDir.resolve(name).normalize();
        if (!p.startsWith(reportDir) || !Files.isRegularFile(p)) {
            throw new IllegalArgumentException("报告不存在: " + name);
        }
        return Files.readString(p);
    }
}
