package com.llmetor.dataagent.dataagent.eval.bird;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmetor.dataagent.dataagent.eval.bird.sqlite.SqliteQueryExecutor;
import com.llmetor.dataagent.dataagent.eval.bird.sqlite.SqliteSchemaProvider;
import org.springframework.stereotype.Service;

/**
 * BIRD evaluation tool factory.
 *
 * <p>The bundle intentionally contains schema inspection and one dynamic SQL
 * verifier. Production SQL safety checks, permission rewrites, and result
 * truncation remain isolated in the regular data-agent.</p>
 *
 * <p>M9 自原版移植：工具为每题 new 的非 bean 实例（指向该题 SQLite 文件），不注册进
 * DodoAgent 工具清单，与生产工具零共享。</p>
 */
@Service
public class BirdEvalToolFactory {

    private final ObjectMapper objectMapper;

    public BirdEvalToolFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public BirdToolBundle create(String sqlitePath, String reviewContext) {
        SqliteSchemaProvider schemaProvider = new SqliteSchemaProvider(sqlitePath);
        SqliteQueryExecutor executor = new SqliteQueryExecutor(sqlitePath);

        return new BirdToolBundle(
                new BirdListTablesTool(schemaProvider),
                new BirdDescribeTablesTool(schemaProvider),
                new BirdVerifySqlTool(sqlitePath, executor, reviewContext, objectMapper)
        );
    }

    public String describeAllTables(String sqlitePath) {
        return new SqliteSchemaProvider(sqlitePath).describeAllTables();
    }

    public record BirdToolBundle(
            BirdListTablesTool listTablesTool,
            BirdDescribeTablesTool describeTablesTool,
            BirdVerifySqlTool verifySqlTool
    ) {
    }
}
