package com.llmetor.dataagent.dataagent.file;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 文件管理服务（M5 上传主流程编排）。
 *
 * <p>流程：启动自动建表（幂等补列补索引）→ 上传：UUID fileId → DB PROCESSING →
 * MinIO（objectName=file-{uuid去横线}.{ext}）→ 类型路由：
 * <ul>
 *   <li>文本类：Tika 解析；全文 ≥ large-file-threshold → 切分 + 向量化（embed=1，
 *       PgVector 失败回退 embed=0 不阻断）；小文件只存截断文本</li>
 *   <li>图片：只落存储不识别，延迟到 analyzeFile 按需多模态（省成本），结果懒写回 extracted_text</li>
 *   <li>其他：仅元数据</li>
 * </ul>
 * 失败：状态置 FAILED 并抛出。另承担文件-会话双阶段关联（linkToConversation / linkToSession）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileManageService {

    /** 类型路由的文本类扩展名（Tika 统一解析） */
    private static final Set<String> TEXT_EXTS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "md", "csv", "json", "html", "htm", "xml",
            "rtf", "odt", "ods", "odp", "eml", "msg", "log", "yml", "yaml", "java", "py"
    );
    /** 图片扩展名（延迟到 analyzeFile 多模态识别） */
    private static final Set<String> IMAGE_EXTS = Set.of(
            "jpg", "jpeg", "png", "gif", "bmp", "webp"
    );

    private final DataSource dataSource;
    private final MinioService minioService;
    private final FileParserService fileParserService;
    private final EmbeddingService embeddingService;
    private final AgentxFileMapper agentxFileMapper;

    @Value("${data-agent.file.large-file-threshold:10000}")
    private int largeFileThreshold;

    /** 启动时自动建表（M-H1 PG 版：原生 IF NOT EXISTS 幂等），应用自管 schema，无需手工脚本 */
    @PostConstruct
    void ensureTableExists() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agentx_file (
                    id              BIGINT       NOT NULL,
                    file_id         VARCHAR(64)  NOT NULL,
                    file_name       VARCHAR(255) NOT NULL,
                    file_type       VARCHAR(32),
                    file_size       BIGINT,
                    minio_path      VARCHAR(512),
                    extracted_text  TEXT,
                    status          VARCHAR(16)  NOT NULL,
                    embed           SMALLINT     NOT NULL DEFAULT 0,
                    conversation_id VARCHAR(64),
                    session_id      BIGINT,
                    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
                    updated_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    CONSTRAINT uk_file_id UNIQUE (file_id)
                )
                """);
        tryExecute(jdbc, "ALTER TABLE agentx_file ADD COLUMN IF NOT EXISTS session_id BIGINT DEFAULT NULL");
        tryExecute(jdbc, "CREATE INDEX IF NOT EXISTS idx_agentx_file_session ON agentx_file (session_id)");
        tryExecute(jdbc, "CREATE INDEX IF NOT EXISTS idx_agentx_file_conv ON agentx_file (conversation_id)");
        log.info("[file] 表 agentx_file 已就绪");
    }

    /** 静默执行 DDL（补列/建索引），已存在则跳过 */
    private void tryExecute(JdbcTemplate jdbc, String sql) {
        try {
            jdbc.execute(sql);
        } catch (Exception e) {
            log.debug("[file] DDL 跳过（可能已存在）: {} | err={}", sql, e.getMessage());
        }
    }

    // ==================== 文件-会话双阶段关联 ====================

    /**
     * 阶段一：stream 启动时把本轮上传组立即关联到会话——本轮 system prompt 反查得到。
     */
    @Transactional(rollbackFor = Exception.class)
    public void linkToConversation(List<String> fileIds, String conversationId) {
        if (noLink(fileIds, conversationId)) {
            return;
        }
        LambdaUpdateWrapper<AgentxFile> wrapper = new LambdaUpdateWrapper<AgentxFile>()
                .in(AgentxFile::getFileId, fileIds)
                .set(AgentxFile::getConversationId, conversationId);
        int updated = agentxFileMapper.update(null, wrapper);
        log.info("[file] 已 link 文件到会话: convId={}, files={}, updated={}",
                conversationId, fileIds.size(), updated);
    }

    /**
     * 阶段二：Complete/Paused 事件后补写 session_id（历史回放按轮取附件）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void linkToSession(List<String> fileIds, Long sessionId, String conversationId) {
        if (fileIds == null || fileIds.isEmpty() || sessionId == null) {
            return;
        }
        LambdaUpdateWrapper<AgentxFile> wrapper = new LambdaUpdateWrapper<AgentxFile>()
                .in(AgentxFile::getFileId, fileIds)
                .set(AgentxFile::getSessionId, sessionId);
        if (StringUtils.hasText(conversationId)) {
            wrapper.set(AgentxFile::getConversationId, conversationId);
        }
        int updated = agentxFileMapper.update(null, wrapper);
        log.info("[file] 已 link 文件到 session: sessionId={}, convId={}, files={}, updated={}",
                sessionId, conversationId, fileIds.size(), updated);
    }

    private boolean noLink(List<String> fileIds, String conversationId) {
        return fileIds == null || fileIds.isEmpty() || !StringUtils.hasText(conversationId);
    }

    /** 按会话查全部 SUCCESS 文件（时间升序）——system prompt 会话文件段 + analyzeFile 注入判定用 */
    public List<AgentxFile> listByConversationId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return List.of();
        }
        return agentxFileMapper.selectList(
                new LambdaQueryWrapper<AgentxFile>()
                        .eq(AgentxFile::getConversationId, conversationId)
                        .eq(AgentxFile::getStatus, "SUCCESS")
                        .orderByAsc(AgentxFile::getCreatedAt));
    }

    /** 按轮次批量查附件元数据（历史会话回放用） */
    public List<AgentxFile> listBySessionId(Long sessionId) {
        if (sessionId == null) {
            return List.of();
        }
        return agentxFileMapper.selectList(
                new LambdaQueryWrapper<AgentxFile>().eq(AgentxFile::getSessionId, sessionId));
    }

    // ==================== 上传 / 查询 / 删除 ====================

    @Transactional(rollbackFor = Exception.class)
    public AgentxFile uploadFile(MultipartFile file) {
        String fileId = UUID.randomUUID().toString();
        String fileType = extractExt(file.getOriginalFilename());
        log.info("[file] 开始上传: fileId={}, name={}, type={}, size={}",
                fileId, file.getOriginalFilename(), fileType, file.getSize());

        AgentxFile entity = new AgentxFile();
        entity.setFileId(fileId);
        entity.setFileName(file.getOriginalFilename());
        entity.setFileType(fileType);
        entity.setFileSize(file.getSize());
        entity.setStatus("PROCESSING");
        entity.setEmbed(0);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        agentxFileMapper.insert(entity);

        try {
            String objectName = generateObjectName(fileId, fileType);
            entity.setMinioPath(minioService.uploadFile(file, objectName));

            if (isTextFile(fileType)) {
                handleTextFile(file, entity);
            } else if (isImageFile(fileType)) {
                // 图片不在上传时识别（拖慢上传 + 烧模型成本），留给 analyzeFile 按需调多模态
                log.info("[file] 图片文件仅落 MinIO + DB，识别延迟到 analyzeFile: fileId={}", fileId);
            } else {
                log.info("[file] 非文本非图片类型: {}，仅存储元数据", fileType);
            }

            entity.setStatus("SUCCESS");
            entity.setUpdatedAt(LocalDateTime.now());
            agentxFileMapper.updateById(entity);
            log.info("[file] 上传成功: fileId={}, embed={}", fileId, entity.getEmbed());
            return entity;
        } catch (Exception e) {
            log.error("[file] 上传失败: fileId={}", fileId, e);
            entity.setStatus("FAILED");
            entity.setUpdatedAt(LocalDateTime.now());
            agentxFileMapper.updateById(entity);
            throw new RuntimeException("文件上传失败: " + e.getMessage(), e);
        }
    }

    /** 文本类：Tika 解析 → 大文件切分向量化（失败回退 embed=0）/ 小文件存截断文本 */
    private void handleTextFile(MultipartFile file, AgentxFile entity) {
        FileParserService.ParseResult parseResult = fileParserService.parse(file);
        String fullText = parseResult.getFullText();
        entity.setExtractedText(parseResult.getTruncatedText());

        if (fullText.length() >= largeFileThreshold) {
            if (!embeddingService.isAvailable()) {
                log.info("[file] 大文件但 PgVector 不可用，仅存截断文本: fileId={}", entity.getFileId());
                return;
            }
            try {
                List<Document> chunks = new OverlapParagraphTextSplitter(500, 50)
                        .apply(List.of(new Document(fullText)));
                embeddingService.embedAndStore(chunks, entity.getFileId());
                entity.setEmbed(1);
                log.info("[file] 大文件已向量化: fileId={}, chunks={}", entity.getFileId(), chunks.size());
            } catch (Exception e) {
                // 向量化失败回退 embed=0，不阻断上传（E01 降级契约）
                log.warn("[file] 向量化失败，回退直接加载: fileId={}, err={}", entity.getFileId(), e.getMessage());
            }
        }
    }

    public AgentxFile getFileInfo(String fileId) {
        AgentxFile entity = findByFileId(fileId);
        if (entity == null) {
            throw new IllegalArgumentException("文件不存在: " + fileId);
        }
        return entity;
    }

    /** 从 MinIO 下载原始字节（analyzeFile 多模态识别 / preview 接口用） */
    public byte[] downloadFileBytes(String fileId) {
        AgentxFile entity = getFileInfo(fileId);
        if (!StringUtils.hasText(entity.getMinioPath())) {
            throw new IllegalStateException("文件未上传到对象存储: " + fileId);
        }
        try (InputStream is = minioService.downloadFile(extractObjectName(entity.getMinioPath()))) {
            return is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("从 MinIO 下载文件失败: " + e.getMessage(), e);
        }
    }

    /** 图片识别结果懒写回 extracted_text（下次 analyzeFile 同图直接命中缓存） */
    public void saveExtractedText(String fileId, String text) {
        AgentxFile entity = findByFileId(fileId);
        if (entity == null) {
            return;
        }
        entity.setExtractedText(text);
        entity.setUpdatedAt(LocalDateTime.now());
        agentxFileMapper.updateById(entity);
    }

    /** 删除：先 MinIO 后 DB；MinIO 失败仅 warn 继续（DB 是元数据事实源，孤儿对象不阻断） */
    @Transactional(rollbackFor = Exception.class)
    public void deleteFile(String fileId) {
        AgentxFile entity = findByFileId(fileId);
        if (entity == null) {
            throw new IllegalArgumentException("文件不存在: " + fileId);
        }
        try {
            if (StringUtils.hasText(entity.getMinioPath())) {
                minioService.deleteFile(extractObjectName(entity.getMinioPath()));
            }
        } catch (Exception e) {
            log.warn("[file] MinIO 删除失败（继续删 DB）: fileId={}, err={}", fileId, e.getMessage());
        }
        agentxFileMapper.delete(new QueryWrapper<AgentxFile>().eq("file_id", fileId));
        log.info("[file] 文件已删除: fileId={}", fileId);
    }

    private AgentxFile findByFileId(String fileId) {
        return agentxFileMapper.selectOne(new QueryWrapper<AgentxFile>().eq("file_id", fileId));
    }

    public boolean isTextFile(String ext) {
        return ext != null && TEXT_EXTS.contains(ext.toLowerCase(Locale.ROOT));
    }

    public boolean isImageFile(String ext) {
        return ext != null && IMAGE_EXTS.contains(ext.toLowerCase(Locale.ROOT));
    }

    private static String extractExt(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private static String generateObjectName(String fileId, String fileType) {
        return "file-" + fileId.replace("-", "") + "." + fileType;
    }

    private static String extractObjectName(String fullPath) {
        if (fullPath == null || !fullPath.contains("/")) {
            return fullPath;
        }
        return fullPath.substring(fullPath.lastIndexOf('/') + 1);
    }
}
