package com.llmetor.dataagent.dataagent.eval.bird.sqlite;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BIRD database_description CSV loader。
 *
 * <p>BIRD 数据包每个库自带 {@code database_description/<table>.csv}：列别名/描述/值映射。
 * 本类只读该目录，缺目录返回空 Map（描述是增强信息不是必需品）。
 */
@Slf4j
public final class BirdDatabaseDescriptionLoader {

    private BirdDatabaseDescriptionLoader() {
    }

    public static Map<String, Map<String, ColumnDescription>> load(String sqlitePath) {
        Path dir = Path.of(sqlitePath).getParent();
        if (dir == null) {
            return Map.of();
        }
        Path descDir = dir.resolve("database_description");
        if (!Files.isDirectory(descDir)) {
            return Map.of();
        }

        Map<String, Map<String, ColumnDescription>> result = new LinkedHashMap<>();
        try (var files = Files.list(descDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".csv"))
                    .forEach(p -> readFile(p, result));
        } catch (IOException e) {
            log.debug("[bird-eval] database_description read failed {}: {}", descDir, e.getMessage());
        }
        return result;
    }

    private static void readFile(Path file, Map<String, Map<String, ColumnDescription>> result) {
        String fileName = file.getFileName().toString();
        String tableName = fileName.substring(0, fileName.length() - 4);
        try {
            List<List<String>> rows = parseCsv(file);
            if (rows.size() <= 1) {
                return;
            }
            Map<String, ColumnDescription> columns = new LinkedHashMap<>();
            for (int i = 1; i < rows.size(); i++) {
                List<String> row = rows.get(i);
                String originalName = cell(row, 0);
                if (originalName.isBlank()) {
                    continue;
                }
                columns.put(originalName, new ColumnDescription(
                        cell(row, 1),
                        cell(row, 2),
                        joinNonBlank(cell(row, 4), cell(row, 5))
                ));
            }
            if (!columns.isEmpty()) {
                result.put(tableName, columns);
            }
        } catch (Exception e) {
            log.debug("[bird-eval] column description read failed {}: {}", file, e.getMessage());
        }
    }

    private static List<List<String>> parseCsv(Path file) throws IOException {
        String text = readTextWithFallback(file);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n') {
                row.add(cell.toString());
                rows.add(row);
                row = new ArrayList<>();
                cell.setLength(0);
            } else if (ch != '\r') {
                cell.append(ch);
            }
        }
        if (!row.isEmpty() || cell.length() > 0) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    private static String cell(List<String> row, int index) {
        return index < row.size() && row.get(index) != null ? row.get(index).trim() : "";
    }

    private static String joinNonBlank(String a, String b) {
        if (a.isBlank()) return b;
        if (b.isBlank()) return a;
        return a + "\n" + b;
    }

    public record ColumnDescription(String alias, String description, String valueDescription) {
        public static ColumnDescription empty() {
            return new ColumnDescription("", "", "");
        }

        /**
         * 输出格式：
         * <description>
         * Values: <valueDescription>
         * alias 与原列名相同时不输出（废话）；description/valueDescription 完整不截断。
         */
        public String formattedComment(String originalName) {
            List<String> parts = new ArrayList<>();
            if (description != null && !description.isBlank()) {
                parts.add(description.trim());
            }
            String usefulAlias = usefulAlias(originalName);
            if (usefulAlias != null) {
                parts.add("alias: " + usefulAlias);
            }
            if (valueDescription != null && !valueDescription.isBlank()) {
                String value = valueDescription.trim();
                if ("unuseful".equalsIgnoreCase(value)) {
                    parts.add("Values: unuseful. Do not filter, group by, or branch on this field "
                            + "unless the question or evidence explicitly requires it.");
                } else {
                    parts.add("Values: " + value);
                }
            }
            return String.join("\n", parts);
        }

        /**
         * alias 与原列名（去空格/下划线/大小写）相同视为废话，不输出。
         */
        private String usefulAlias(String originalName) {
            if (alias == null || alias.isBlank()) return null;
            String a = norm(alias);
            String o = norm(originalName);
            if (a.isEmpty() || a.equals(o)) return null;
            return alias.trim();
        }

        private static String norm(String s) {
            return s == null ? "" : s.toLowerCase().replace("_", "").replace(" ", "").replace("-", "");
        }
    }

    /**
     * 读取 CSV 文本：
     * - 先尝试 UTF-8 严格解码（REPORT 模式）
     * - 失败回退 ISO-8859-1（Latin-1，对 Windows-1252 也基本兼容，0x95→•、0x96→–）
     * <p>
     * BIRD 数据集部分 CSV（european_football_2/*_Attributes.csv 等）含 Windows-1252 字符，
     * 默认 UTF-8 REPLACE 会把 0x95/0x96 替换成 U+FFFD，描述内容被损坏。
     */
    private static String readTextWithFallback(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }
}
