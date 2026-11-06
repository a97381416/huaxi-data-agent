package com.llmetor.dataagent.dataagent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测数据集校验服务（M8）：TestHub 自动判分用——校验 dataset.jsonl 的
 * 题量/难度配比/三池交错/多轮题/run_role/时间锚点，并把每条 gold_sql 对库复跑
 * （M8-A02"预执行固化、可复跑一致"的基本面证明：SQL 全部可执行）。
 *
 * <p>只读：对业务库只执行 SELECT；对数据集文件只读。
 */
@Slf4j
@Service
public class EvalDatasetCheckService {

    /** 难度交错模式（与 build_dataset.py 的 DIFFICULTY_PATTERN 一致：每 10 题 7 简单 + 2 中等 + 1 困难） */
    private static final String[] PATTERN = {"简单", "简单", "简单", "简单", "简单", "简单", "简单",
            "中等", "中等", "困难"};

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final String datasetPath;

    public EvalDatasetCheckService(ObjectMapper objectMapper, DataSource dataSource,
                                   @Value("${data-agent.agent-eval.dataset-path:../scripts/agent/dataset.jsonl}")
                                   String datasetPath) {
        this.objectMapper = objectMapper;
        this.jdbc = new JdbcTemplate(dataSource);
        this.datasetPath = datasetPath;
    }

    /** 校验结果（TestHub 断言直接取字段） */
    public Map<String, Object> check() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<JsonNode> rows;
        try {
            rows = readDataset(Path.of(datasetPath));
        } catch (Exception e) {
            out.put("found", false);
            out.put("error", "数据集不存在或不可读: " + datasetPath + "（先运行 scripts/agent/build_dataset.py）");
            return out;
        }

        out.put("found", true);
        out.put("path", datasetPath);
        out.put("total", rows.size());

        // 难度分布
        Map<String, Integer> difficulty = new LinkedHashMap<>();
        for (String d : new String[]{"简单", "中等", "困难"}) {
            difficulty.put(d, 0);
        }
        for (JsonNode r : rows) {
            difficulty.merge(r.path("difficulty").asText(""), 1, Integer::sum);
        }
        out.put("difficulty", difficulty);

        // 每 10 题一组的交错分布（任意截断比例稳定的基本面）
        List<Map<String, Integer>> chunks = new ArrayList<>();
        for (int i = 0; i < rows.size(); i += 10) {
            Map<String, Integer> chunk = new LinkedHashMap<>();
            for (String d : new String[]{"简单", "中等", "困难"}) {
                chunk.put(d, 0);
            }
            for (JsonNode r : rows.subList(i, Math.min(i + 10, rows.size()))) {
                chunk.merge(r.path("difficulty").asText(""), 1, Integer::sum);
            }
            chunks.add(chunk);
        }
        out.put("chunks", chunks);
        // 交错是否符合 7/2/1 模式
        boolean interleaveOk = chunks.stream().allMatch(c ->
                c.get("简单") == 7 && c.get("中等") == 2 && c.get("困难") == 1);
        out.put("interleaveOk", interleaveOk);

        // 多轮题与 run_role
        long multiRound = rows.stream().filter(r -> r.path("question").isArray()).count();
        out.put("multiRound", multiRound);
        Map<String, Integer> runRoles = new LinkedHashMap<>();
        for (JsonNode r : rows) {
            runRoles.merge(r.path("run_role").asText(""), 1, Integer::sum);
        }
        out.put("runRoles", runRoles);

        // 时间锚点题（数据锚点口径，不用 NOW()）
        long anchor = rows.stream().filter(r -> r.path("gold_sql").asText("")
                .contains("SELECT MAX(")).count();
        out.put("anchorQuestions", anchor);

        // gold_sql 全量对库复跑（M8-A02 可复跑一致）
        List<Map<String, Object>> failed = new ArrayList<>();
        int executable = 0;
        for (JsonNode r : rows) {
            String sql = r.path("gold_sql").asText("").trim();
            if (!sql.toUpperCase().startsWith("SELECT")) {
                failed.add(Map.of("question_id", r.path("question_id").asInt(), "error", "非 SELECT 语句"));
                continue;
            }
            try {
                jdbc.queryForList(sql);
                executable++;
            } catch (Exception e) {
                failed.add(Map.of("question_id", r.path("question_id").asInt(),
                        "error", String.valueOf(e.getMessage()).substring(0, Math.min(200, String.valueOf(e.getMessage()).length()))));
            }
        }
        out.put("goldExecutable", executable);
        out.put("goldFailed", failed);
        return out;
    }

    private List<JsonNode> readDataset(Path path) throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank()) {
                continue;
            }
            rows.add(objectMapper.readTree(line));
        }
        return rows;
    }
}
