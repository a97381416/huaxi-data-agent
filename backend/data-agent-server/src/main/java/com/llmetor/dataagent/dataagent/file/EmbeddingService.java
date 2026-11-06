package com.llmetor.dataagent.dataagent.file;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 向量化 + RAG 检索服务（M5）。
 *
 * <p>PgVectorStore（表 vector_file_info，dim=1024 / COSINE / HNSW）+
 * RAG 四步检索：① CompressionQueryTransformer 压缩问题 → ② MultiQueryExpander 扩展
 * 3 变体 + 原 query（共 4）→ ③ 每变体 similaritySearch(topK, filter: fileid=={id}) →
 * ④ 按 Document id 去重合并。
 *
 * <p>降级契约（E01）：pgvector JdbcTemplate 或 EmbeddingModel 任一缺失、或 store 初始化
 * 失败（PG 宕机/扩展未装），vectorStore 为 null——embedAndStore 跳过（embed=0），
 * ragRetrieve 返回空列表，上传与问答主流程都不阻断。
 */
@Slf4j
@Service
public class EmbeddingService {

    private static final String VECTOR_TABLE = "vector_file_info";
    /** DashScope embedding 单请求条数上限内的小批量（9/批） */
    private static final int EMBEDDING_BATCH_SIZE = 9;

    @Autowired(required = false)
    @Qualifier("pgVectorJdbcTemplate")
    private JdbcTemplate pgVectorJdbcTemplate;

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    private final ChatModel chatModel;

    @Value("${data-agent.embeddings.dimensions:1024}")
    private int dimensions;

    @Value("${data-agent.embeddings.rag-top-k:5}")
    private int ragTopK;

    private volatile PgVectorStore vectorStore;

    public EmbeddingService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** 容器就绪后构建 store；失败降级（不抛出，应用照常启动） */
    @Autowired
    void init() {
        if (pgVectorJdbcTemplate == null || embeddingModel == null) {
            log.warn("[file] PgVector/EmbeddingModel 未配置，文件向量化与 RAG 不可用，大文件走直接加载");
            return;
        }
        try {
            PgVectorStore store = PgVectorStore.builder(pgVectorJdbcTemplate, embeddingModel)
                    .dimensions(dimensions)
                    .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                    .indexType(PgVectorStore.PgIndexType.HNSW)
                    .initializeSchema(true)
                    .removeExistingVectorStoreTable(false)
                    .vectorTableName(VECTOR_TABLE)
                    .maxDocumentBatchSize(100)
                    .build();
            store.afterPropertiesSet();
            this.vectorStore = store;
            log.info("[file] PgVectorStore 初始化完成: 表={} dim={} topK={}", VECTOR_TABLE, dimensions, ragTopK);
        } catch (Exception e) {
            log.warn("[file] PgVectorStore 初始化失败，向量化/RAG 降级不可用: {}", e.getMessage());
        }
    }

    public boolean isAvailable() {
        return vectorStore != null;
    }

    /** 切分产物注入 fileid/chunkId 元数据后分批入库 */
    public void embedAndStore(List<Document> documents, String fileId) {
        if (vectorStore == null) {
            log.info("[file] PgVector 不可用，跳过向量化（embed 保持 0）: fileId={}", fileId);
            return;
        }
        for (int i = 0; i < documents.size(); i++) {
            documents.get(i).getMetadata().put("fileid", fileId);
            documents.get(i).getMetadata().put("chunkId", i);
        }
        for (int i = 0; i < documents.size(); i += EMBEDDING_BATCH_SIZE) {
            List<Document> batch = documents.subList(i, Math.min(i + EMBEDDING_BATCH_SIZE, documents.size()));
            vectorStore.doAdd(batch);
        }
        log.info("[file] 向量化完成: fileId={}, chunks={}", fileId, documents.size());
    }

    /**
     * RAG 四步检索；任何一步失败返回空列表降级（analyzeFile 收到后给 LLM 友好提示）。
     */
    public List<String> ragRetrieve(String fileId, String question) {
        if (vectorStore == null || !StringUtils.hasText(fileId) || !StringUtils.hasText(question)) {
            return Collections.emptyList();
        }
        try {
            Query query = Query.builder().text(question).build();
            // ChatClient.mutate() 出 Builder：压缩器与扩展器共享同一底层模型配置
            ChatClient.Builder chatClientBuilder = ChatClient.create(chatModel).mutate();

            // ① 压缩：把含指代/上下文的问题压成独立问题
            CompressionQueryTransformer compressor = CompressionQueryTransformer.builder()
                    .chatClientBuilder(chatClientBuilder)
                    .build();
            Query compressed = compressor.transform(query);
            log.info("[file] RAG 问题压缩: {}", compressed.text());

            // ② 扩展：3 个变体 + 原 query，共 4 路
            QueryExpander expander = MultiQueryExpander.builder()
                    .chatClientBuilder(chatClientBuilder)
                    .numberOfQueries(3)
                    .includeOriginal(true)
                    .build();
            List<Query> expandedQueries = expander.expand(compressed);

            // ③④ 每变体按 fileid 过滤检索 topK，按 Document id 去重合并
            FilterExpressionBuilder builder = new FilterExpressionBuilder();
            Filter.Expression filter = builder.eq("fileid", fileId).build();

            List<String> results = new ArrayList<>();
            Set<String> seenIds = new HashSet<>();
            for (Query eq : expandedQueries) {
                List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                        .query(eq.text())
                        .topK(ragTopK)
                        .filterExpression(filter)
                        .build());
                for (Document doc : docs) {
                    if (seenIds.add(doc.getId())) {
                        results.add(doc.getText());
                    }
                }
            }
            log.info("[file] RAG 检索完成: fileId={}, 变体数={}, 去重后={}", fileId, expandedQueries.size(), results.size());
            return results;
        } catch (Exception e) {
            log.warn("[file] RAG 检索失败，降级返回空: fileId={}, err={}", fileId, e.getMessage());
            return Collections.emptyList();
        }
    }
}
