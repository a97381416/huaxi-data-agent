package com.llmetor.dataagent.dataagent.eval.bird;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmetor.dataagent.dataagent.eval.bird.sqlite.BirdDatabaseDescriptionLoader;
import com.llmetor.dataagent.dataagent.eval.bird.sqlite.BirdDatabaseDescriptionLoader.ColumnDescription;
import com.llmetor.dataagent.dataagent.eval.bird.sqlite.SqliteQueryExecutor;
import com.llmetor.dataagent.dataagent.eval.bird.sqlite.SqliteQueryExecutor.ExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes and semantically verifies one BIRD SQL draft.
 *
 * <p>The SQL is always executed first and exactly as written. A blank
 * {@code requestedColumns} list selects probe mode, while a non-empty list
 * enables final-answer checks against the actual execution metadata.</p>
 *
 * <p>M9 自原版 dodo-agentx 逐行移植（除包名/import 外零改动）。"绝不改写 SQL、绝不注入
 * LIMIT、绝不截断结果"是反馈闭环的根基——工具是探针不是守门员。</p>
 */
@Slf4j
public class BirdVerifySqlTool {

    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\r\\n]*");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:''|[^'])*'");

    private static final Pattern NON_ISO_DATE_LITERAL = Pattern.compile(
            "'(\\d{4}/\\d{1,2}(?:/\\d{1,2})?|\\d{1,2}/\\d{1,2}/\\d{4})'");

    private static final Pattern ABSENCE_REQUEST = Pattern.compile(
            "\\b(?:is null|not null|missing|without|empty|absent)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern NULL_CONDITION = Pattern.compile(
            "\\bIS\\s+(?:NOT\\s+)?NULL\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern DIVISION = Pattern.compile(
            "(?:(?:`([^`]+)`|\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_]*))\\.)?"
                    + "(?:`([^`]+)`|\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_]*))"
                    + "\\s*/\\s*"
                    + "(?:(?:`([^`]+)`|\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_]*))\\.)?"
                    + "(?:`([^`]+)`|\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_]*))");

    private static final Pattern CAST_AS_REAL = Pattern.compile(
            "CAST\\s*\\([^()]*AS\\s+REAL\\s*\\)", Pattern.CASE_INSENSITIVE);

    private static final Pattern REAL_PROMOTION = Pattern.compile(
            "[\\d.]+\\s*\\*\\s*$");

    private static final Pattern JOIN_EQUALITY = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*(?:`([^`]+)`|\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_]*))"
                    + "\\s*=\\s*"
                    + "([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*(?:`([^`]+)`|\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_]*))");

    private static final Pattern TABLE_REF = Pattern.compile(
            "\\b(?:FROM|JOIN)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+(?:AS\\s+)?([A-Za-z_][A-Za-z0-9_]*)",
            Pattern.CASE_INSENSITIVE);

    private final String sqlitePath;
    private final SqliteQueryExecutor executor;
    private final String requestContext;
    private final ObjectMapper objectMapper;
    private final Map<String, Map<String, ColumnDescription>> columnDescriptions;
    private final Set<String> textColumnNames;
    private final Set<String> integerColumnNames;
    private final Map<String, Set<String>> fkColumnsByTable;
    private final Map<String, Set<String>> fkTargetsByTable;

    public BirdVerifySqlTool(String sqlitePath,
                             SqliteQueryExecutor executor,
                             String requestContext,
                             ObjectMapper objectMapper) {
        this.sqlitePath = sqlitePath;
        this.executor = executor;
        this.requestContext = requestContext == null ? "" : requestContext;
        this.objectMapper = objectMapper;
        this.columnDescriptions = BirdDatabaseDescriptionLoader.load(sqlitePath);
        this.textColumnNames = new HashSet<>();
        this.integerColumnNames = new HashSet<>();
        this.fkColumnsByTable = new HashMap<>();
        this.fkTargetsByTable = new HashMap<>();
        loadColumnMetadata();
    }

    @Tool(name = "verifySql",
            description = "Execute one SQLite SELECT/WITH statement exactly as written, then return its complete "
                    + "result and structured semantic diagnostics. Pass requestedColumns=[] to probe real values, "
                    + "join shape, or an empty result. Pass the non-empty exact output field list in SELECT order "
                    + "to verify a final answer. The tool never rewrites SQL or injects LIMIT, permissions, "
                    + "read-only transformation, timeout, formatting, or row truncation.")
    public String verifySql(
            @ToolParam(description = "SQLite SELECT/WITH statement to execute exactly as written")
            String sql,
            @ToolParam(description = "Empty for a probe; otherwise exact requested output fields in SELECT order")
            List<String> requestedColumns) {
        try {
            return objectMapper.writeValueAsString(verify(sql, requestedColumns));
        } catch (JsonProcessingException e) {
            return "{\"passed\":false,\"errors\":[{\"rule\":\"RESULT_SERIALIZATION\","
                    + "\"message\":\"Failed to serialize the verification result.\","
                    + "\"fix\":\"Simplify output aliases or expressions, then call verifySql again.\"}]}";
        }
    }

    private VerificationResult verify(String sql, List<String> requestedColumns) {
        if (sql == null || sql.isBlank()) {
            ExecutionResult execution = new ExecutionResult(
                    false, "The SQL statement is blank.", List.of(), List.of(), 0, 0);
            return new VerificationResult(false, execution, List.of(new ValidationError(
                    "BLANK_SQL", "The SQL statement is blank.",
                    "Write one SQLite SELECT/WITH statement.")));
        }

        ExecutionResult execution = executor.execute(sql);
        if (!execution.ok()) {
            return new VerificationResult(false, execution, List.of(new ValidationError(
                    "SQLITE_EXECUTION",
                    execution.error(),
                    "Fix the reported SQLite error with schema information, then call verifySql again.")));
        }

        List<String> requested = requestedColumns == null
                ? List.of()
                : requestedColumns.stream()
                  .map(value -> value == null ? "" : value.trim())
                  .toList();
        if (requested.isEmpty()) {
            return new VerificationResult(true, execution, List.of());
        }

        List<ValidationError> errors = new ArrayList<>();
        String inspectableSql = stripLiteralsAndComments(sql);
        addProjectionErrors(requested, execution.columns(), errors);
        addInferredNullError(requested, inspectableSql, errors);
        addDateLiteralErrors(sql, errors);
        addUnusefulColumnErrors(inspectableSql, errors);
        addTextIdentifierErrors(sql, errors);
        addIntegerDivisionErrors(inspectableSql, errors);
        addDisplayNameJoinErrors(inspectableSql, errors);
        return new VerificationResult(errors.isEmpty(), execution, List.copyOf(errors));
    }

    private void addProjectionErrors(List<String> requested,
                                     List<String> resultColumns,
                                     List<ValidationError> errors) {
        if (requested.stream().anyMatch(String::isBlank)) {
            errors.add(new ValidationError(
                    "PROJECTION_CONTRACT",
                    "requestedColumns contains a blank field name.",
                    "Use exact schema field names or stable output aliases, then call verifySql again."));
            return;
        }

        if (resultColumns.size() != requested.size()) {
            errors.add(new ValidationError(
                    "PROJECTION_COUNT",
                    "The SELECT returns " + resultColumns.size() + " column(s), but requestedColumns has "
                            + requested.size() + ".",
                    "Change SELECT to output exactly [" + String.join(", ", requested)
                            + "] in that order."));
            return;
        }

        List<Integer> positions = projectionPositions(requested, resultColumns, errors);
        if (positions.size() != requested.size()) {
            return;
        }
        if (requested.size() > 1 && orderDiffers(positions)) {
            errors.add(new ValidationError(
                    "PROJECTION_ORDER",
                    "The SELECT column order differs from requestedColumns.",
                    "Reorder SELECT to [" + String.join(", ", requested) + "]."));
        }
    }

    private List<Integer> projectionPositions(List<String> requested,
                                              List<String> resultColumns,
                                              List<ValidationError> errors) {
        List<Integer> positions = new ArrayList<>();
        for (String field : requested) {
            int position = uniquePosition(normalized(field), resultColumns);
            if (position < 0) {
                errors.add(new ValidationError(
                        "PROJECTION_NAME",
                        "No unique result column matches requested field " + field + ".",
                        "Alias that output value as " + field
                                + ", or correct requestedColumns to the value requested by the question."));
                continue;
            }
            positions.add(position);
        }
        return List.copyOf(positions);
    }

    private boolean orderDiffers(List<Integer> positions) {
        for (int i = 0; i < positions.size(); i++) {
            if (positions.get(i) != i) {
                return true;
            }
        }
        return false;
    }

    private int uniquePosition(String requested, List<String> resultColumns) {
        int found = -1;
        int matches = 0;
        for (int i = 0; i < resultColumns.size(); i++) {
            if (normalized(resultColumns.get(i)).equals(requested)) {
                found = i;
                matches++;
            }
        }
        return matches == 1 ? found : -1;
    }

    private void addInferredNullError(List<String> requestedColumns,
                                      String sql,
                                      List<ValidationError> errors) {
        String request = requestContext + " Requested values: " + String.join(", ", requestedColumns);
        if (ABSENCE_REQUEST.matcher(request).find() || !NULL_CONDITION.matcher(sql).find()) {
            return;
        }
        errors.add(new ValidationError(
                "INFERRED_NULL_FILTER",
                "The SQL adds an IS NULL or IS NOT NULL condition not required by the question/evidence.",
                "Remove that condition unless absence is explicitly requested."));
    }

    private void addDateLiteralErrors(String sql, List<ValidationError> errors) {
        Matcher matcher = NON_ISO_DATE_LITERAL.matcher(sql);
        if (!matcher.find()) {
            return;
        }
        errors.add(new ValidationError(
                "DATE_LITERAL_FORMAT",
                "Date literal '" + matcher.group(1) + "' uses slashes; BIRD databases usually "
                        + "store dates as ISO-8601 text 'YYYY-MM-DD' and SQLite compares TEXT "
                        + "lexicographically, so a slash literal usually excludes matching rows.",
                "Probe `SELECT DISTINCT <date_col>` to confirm the stored format, then normalize "
                        + "the literal to 'YYYY-MM-DD' or compare years with STRFTIME('%Y', column)."));
    }

    private void addUnusefulColumnErrors(String sql, List<ValidationError> errors) {
        for (Map.Entry<String, Map<String, ColumnDescription>> table : columnDescriptions.entrySet()) {
            for (Map.Entry<String, ColumnDescription> column : table.getValue().entrySet()) {
                String value = normalized(column.getValue().valueDescription());
                if (!"unuseful".equals(value) && !"notuseful".equals(value)) {
                    continue;
                }
                if (containsIdentifier(sql, column.getKey())
                        && !containsIdentifier(requestContext, column.getKey())) {
                    errors.add(new ValidationError(
                            "UNUSEFUL_METADATA_COLUMN",
                            "The SQL uses BIRD metadata-unuseful column " + table.getKey() + "."
                                    + column.getKey() + ".",
                            "Remove that filter, grouping, or branch unless the question/evidence names it."));
                }
            }
        }
    }

    private void addTextIdentifierErrors(String sql, List<ValidationError> errors) {
        for (String column : textColumnNames) {
            String token = identifierAlternation(column);
            String qualified = "(?:[A-Za-z_][A-Za-z0-9_]*\\.)?";
            if (matches(sql, "['\"]0+['\"]\\s*\\|\\|\\s*" + qualified + token
                    + "|" + qualified + token + "\\s*\\|\\|\\s*['\"]0+['\"]")) {
                errors.add(new ValidationError(
                        "TEXT_IDENTIFIER_PADDING",
                        "Text identifier " + column + " is padded with leading zeroes.",
                        "Compare the identifier as stored and remove the padding."));
            }
            if (matches(sql, "CAST\\s*\\(\\s*" + qualified + token
                    + "\\s+AS\\s+(?:INTEGER|INT)\\s*\\)")) {
                errors.add(new ValidationError(
                        "TEXT_IDENTIFIER_CAST",
                        "Text identifier " + column + " is cast to INTEGER.",
                        "Remove the CAST and compare the original text value."));
            }
            if (matches(sql, "(?:SUBSTR|SUBSTRING)\\s*\\(\\s*" + qualified + token + "\\s*,")) {
                errors.add(new ValidationError(
                        "TEXT_IDENTIFIER_SUBSTR",
                        "Text identifier " + column + " is reshaped with SUBSTR/SUBSTRING.",
                        "Remove the substring operation unless evidence explicitly requires it."));
            }
            if (matches(sql, "(?:LTRIM|TRIM)\\s*\\(\\s*" + qualified + token
                    + "\\s*,\\s*['\"]0['\"]\\s*\\)")) {
                errors.add(new ValidationError(
                        "TEXT_IDENTIFIER_TRIM",
                        "Leading zeroes are removed from text identifier " + column + ".",
                        "Remove LTRIM/TRIM and compare the original text value."));
            }
        }
    }

    /**
     * INTEGER 列相除会被 SQLite 截断，提示改用 REAL 除法。
     */
    private void addIntegerDivisionErrors(String sql, List<ValidationError> errors) {
        List<int[]> realSpans = new ArrayList<>();
        Matcher realMatcher = CAST_AS_REAL.matcher(sql);
        while (realMatcher.find()) {
            realSpans.add(new int[]{realMatcher.start(), realMatcher.end()});
        }
        Matcher matcher = DIVISION.matcher(sql);
        while (matcher.find()) {
            String left = firstGroup(matcher, 4, 5, 6);
            String right = firstGroup(matcher, 10, 11, 12);
            if (!integerColumnNames.contains(left) || !integerColumnNames.contains(right)) {
                continue;
            }
            if (REAL_PROMOTION.matcher(sql.substring(0, matcher.start())).find()
                    || realSpans.stream().anyMatch(span ->
                    matcher.start() >= span[0] && matcher.start() < span[1])) {
                continue;
            }
            errors.add(new ValidationError(
                    "INTEGER_DIVISION",
                    "Column " + left + " / " + right + " divides two INTEGER columns, "
                            + "so SQLite truncates the quotient toward zero.",
                    "Wrap the numerator in CAST(... AS REAL) or multiply it by 1.0, "
                            + "unless an integer quotient is intended."));
            return;
        }
    }

    /**
     * 两表存在汇合的声明外键却用展示名互连时提示改走代码列。
     */
    private void addDisplayNameJoinErrors(String sql, List<ValidationError> errors) {
        Map<String, String> aliasToTable = new HashMap<>();
        Matcher refMatcher = TABLE_REF.matcher(sql);
        while (refMatcher.find()) {
            aliasToTable.put(refMatcher.group(2).toLowerCase(), refMatcher.group(1));
        }
        Matcher matcher = JOIN_EQUALITY.matcher(sql);
        while (matcher.find()) {
            String aliasA = matcher.group(1);
            String columnA = firstGroup(matcher, 2, 3, 4);
            String aliasB = matcher.group(5);
            String columnB = firstGroup(matcher, 6, 7, 8);
            String tableA = aliasToTable.getOrDefault(aliasA.toLowerCase(), aliasA);
            String tableB = aliasToTable.getOrDefault(aliasB.toLowerCase(), aliasB);
            if (tableA.equalsIgnoreCase(tableB)
                    || !textColumnNames.contains(columnA) || !textColumnNames.contains(columnB)
                    || fkColumnsByTable.getOrDefault(tableA, Set.of()).contains(columnA)
                    || fkColumnsByTable.getOrDefault(tableB, Set.of()).contains(columnB)) {
                continue;
            }
            Set<String> targetsA = fkTargetsByTable.getOrDefault(tableA, Set.of());
            Set<String> targetsB = fkTargetsByTable.getOrDefault(tableB, Set.of());
            boolean directFk = targetsA.stream().anyMatch(t -> t.startsWith(tableB + "."))
                    || targetsB.stream().anyMatch(t -> t.startsWith(tableA + "."));
            String shared = targetsA.stream().filter(targetsB::contains).findFirst().orElse(null);
            if (!directFk && shared == null) {
                continue;
            }
            String evidence = directFk
                    ? "A declared foreign key connects " + tableA + " and " + tableB
                    : "Tables " + tableA + " and " + tableB + " both declare foreign keys onto " + shared;
            errors.add(new ValidationError(
                    "JOIN_DISPLAY_NAME",
                    evidence + ", but this SQL joins them on text columns "
                            + aliasA + "." + columnA + " = " + aliasB + "." + columnB + ".",
                    "Join through the declared code columns instead; display-name joins drop "
                            + "or duplicate rows whenever names repeat. Probe both join shapes "
                            + "to compare row counts if unsure."));
            return;
        }
    }

    private String firstGroup(Matcher matcher, int... groups) {
        for (int group : groups) {
            String value = matcher.group(group);
            if (value != null) {
                return value;
            }
        }
        return "";
    }

    /**
     * 加载列类型与声明外键，供事实型校验使用。
     */
    private void loadColumnMetadata() {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath);
             Statement statement = connection.createStatement();
             ResultSet tables = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")) {
            while (tables.next()) {
                String table = tables.getString(1);
                try (Statement columnStatement = connection.createStatement();
                     ResultSet rs = columnStatement.executeQuery("PRAGMA table_info(" + quote(table) + ")")) {
                    while (rs.next()) {
                        String type = rs.getString("type");
                        if (type == null) {
                            continue;
                        }
                        String upper = type.toUpperCase();
                        if (upper.contains("TEXT")) {
                            textColumnNames.add(rs.getString("name"));
                        } else if (upper.contains("INT")) {
                            integerColumnNames.add(rs.getString("name"));
                        }
                    }
                }
                try (Statement fkStatement = connection.createStatement();
                     ResultSet rs = fkStatement.executeQuery("PRAGMA foreign_key_list(" + quote(table) + ")")) {
                    Set<String> fkColumns = new HashSet<>();
                    Set<String> fkTargets = new HashSet<>();
                    while (rs.next()) {
                        String from = rs.getString("from");
                        String target = rs.getString("table") + "." + rs.getString("to");
                        fkColumns.add(from);
                        fkTargets.add(target);
                    }
                    fkColumnsByTable.put(table, fkColumns);
                    fkTargetsByTable.put(table, fkTargets);
                }
            }
        } catch (Exception e) {
            log.debug("[bird-eval] cannot inspect column metadata: {}", e.getMessage());
        }
    }

    private boolean matches(String sql, String pattern) {
        return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(sql).find();
    }

    private String identifierAlternation(String identifier) {
        String quoted = Pattern.quote(identifier);
        return "(?:`" + quoted + "`|\"" + quoted + "\"|\\[" + quoted + "\\]|" + quoted + ")";
    }

    private boolean containsIdentifier(String sql, String identifier) {
        String token = identifierAlternation(identifier);
        return Pattern.compile("(?<![A-Za-z0-9_])" + token + "(?![A-Za-z0-9_])",
                Pattern.CASE_INSENSITIVE).matcher(sql).find();
    }

    private String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private String normalized(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9]+", "");
    }

    private String stripLiteralsAndComments(String sql) {
        String withoutComments = BLOCK_COMMENT.matcher(
                LINE_COMMENT.matcher(sql).replaceAll(" ")).replaceAll(" ");
        return STRING_LITERAL.matcher(withoutComments).replaceAll("''");
    }

    private record ValidationError(String rule, String message, String fix) {
    }

    private record VerificationResult(boolean passed,
                                      ExecutionResult execution,
                                      List<ValidationError> errors) {
    }
}
