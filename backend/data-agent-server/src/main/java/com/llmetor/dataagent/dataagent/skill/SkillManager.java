package com.llmetor.dataagent.dataagent.skill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.llmetor.dataagent.dataagent.skill.mapper.AgentxSkillMapper;
import com.llmetor.miniagentx.utils.FrontmatterUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skills 管理器（M6，docs/01 §7.1）——上传校验、启停、删除、启用目录查询。
 *
 * <p>职责分界：内容存取与目录↔DB 同步委托 {@link SkillContentStore}（standalone 本地目录版）；
 * 本类负责 agentx_skill 表生命周期（启动建表）、上传编排（zip 校验→解压→SKILL.md 定位→
 * frontmatter 提取→内容存储→DB upsert）、手动新建/编辑/详情/导出（内置技能只读且不可删除，
 * 手动/上传可编辑删除）、启停/删除、以及给 DodoAgent 的
 * {@link #getEnabledSkillDirs()}（每请求实时查，停用立即生效，M6-B03）。
 */
@Slf4j
@Component
public class SkillManager {

    private static final String SKILL_FILE = "SKILL.md";

    /** 技能来源：builtin=仓库目录同步入库 / upload=zip 上传 / manual=管理界面手动创建（仅 manual 可编辑内容） */
    public static final String SOURCE_BUILTIN = "builtin";
    public static final String SOURCE_UPLOAD = "upload";
    public static final String SOURCE_MANUAL = "manual";

    /** 手动新建/编辑的正文大小上限（200KB，SKILL.md 语义上足够大） */
    private static final int MAX_CONTENT_LENGTH = 200 * 1024;

    /** 描述长度上限（对齐 agentx_skill.description VARCHAR(3000)） */
    private static final int MAX_DESCRIPTION_LENGTH = 3000;

    private final Path skillsDirectory;
    private final JdbcTemplate jdbcTemplate;
    private final AgentxSkillMapper skillMapper;
    private final SkillContentStore contentStore;

    public SkillManager(@Value("${skills.directory:./skills}") String skillsDirectory,
                        DataSource dataSource,
                        AgentxSkillMapper skillMapper,
                        SkillContentStore contentStore) {
        this.skillsDirectory = Path.of(skillsDirectory).toAbsolutePath().normalize();
        // M5 踩坑固化的纪律：主库注入点一律拿 DataSource 自建 JdbcTemplate（见 README M5 不变量 6）
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.skillMapper = skillMapper;
        this.contentStore = contentStore;
    }

    @PostConstruct
    void init() {
        ensureTableExists();
        contentStore.sync();
    }

    /** 定时同步（每 3 分钟）：目录新增入库 / 删除清孤儿 / frontmatter 变更刷新描述（M6-A02） */
    @Scheduled(fixedDelay = 180_000, initialDelay = 180_000)
    void scheduledSync() {
        contentStore.sync();
    }

    /** 启动自动建表（M-H1 PG 版：原生 IF NOT EXISTS 幂等，不再依赖异常吞掉；updated_at 由应用层维护） */
    private void ensureTableExists() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agentx_skill (
                    id           BIGINT       NOT NULL,
                    name         VARCHAR(500) NOT NULL,
                    skill_path   VARCHAR(3000) DEFAULT NULL,
                    description  VARCHAR(3000) DEFAULT '',
                    enabled      SMALLINT     NOT NULL DEFAULT 1,
                    file_name    VARCHAR(255) DEFAULT NULL,
                    source       VARCHAR(20)  DEFAULT NULL,
                    created_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
                    updated_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id)
                )
                """);
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_agentx_skill_name ON agentx_skill (name)");
        jdbcTemplate.execute("ALTER TABLE agentx_skill ADD COLUMN IF NOT EXISTS source VARCHAR(20) DEFAULT NULL");
        try {
            // 存量回填：uploadZip 写过 file_name → upload；目录同步入库（file_name 为空）→ builtin
            jdbcTemplate.execute("UPDATE agentx_skill SET source = CASE "
                    + "WHEN file_name IS NOT NULL THEN '" + SOURCE_UPLOAD + "' "
                    + "ELSE '" + SOURCE_BUILTIN + "' END WHERE source IS NULL");
        } catch (Exception e) {
            log.debug("[SkillManager] source 存量回填跳过: {}", e.getMessage());
        }
        log.info("[SkillManager] agentx_skill 表就绪（skills 目录: {}）", skillsDirectory);
    }

    /** 列出全部技能（含 enabled 状态、存储路径与来源） */
    public List<SkillInfo> list() {
        List<AgentxSkill> skills = skillMapper.selectList(null);
        List<SkillInfo> result = new ArrayList<>();
        for (AgentxSkill s : skills) {
            result.add(new SkillInfo(s.getName(),
                    s.getSkillPath() == null ? "" : s.getSkillPath(),
                    s.getDescription() == null ? "" : s.getDescription(),
                    s.getEnabled() != null && s.getEnabled() == 1,
                    s.getSource() == null ? SOURCE_BUILTIN : s.getSource()));
        }
        return result;
    }

    /**
     * 所有 enabled=1 的技能目录绝对路径——DodoAgent 每次请求构建 SkillsTool 的唯一入口
     * （实时查 DB：停用立即从工具清单消失，M6-B03；目录被手动删掉则跳过并 warn）。
     */
    public List<String> getEnabledSkillDirs() {
        List<AgentxSkill> enabled = skillMapper.selectList(
                new LambdaQueryWrapper<AgentxSkill>().eq(AgentxSkill::getEnabled, 1));
        List<String> dirs = new ArrayList<>();
        for (AgentxSkill s : enabled) {
            String path = skillsDirectory.resolve(s.getName()).toAbsolutePath().toString();
            if (Files.isDirectory(Path.of(path))) {
                dirs.add(path);
            } else {
                log.warn("[SkillManager] enabled skill 目录不存在: {} → {}", s.getName(), path);
            }
        }
        return dirs;
    }

    /**
     * 上传 zip 压缩包并解压到 skills 目录（编排顺序见类注释）。
     * 新技能默认 enabled=1；覆盖上传保留原 enabled 状态（M6-A06）。
     */
    public SkillInfo uploadZip(MultipartFile file) throws IOException {
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(".zip")) {
            throw new IllegalArgumentException("仅支持 .zip 文件");
        }

        // 1. 解压到 skills 目录下的临时目录（Zip Slip 防护在 extract 内，M6-A04）
        Files.createDirectories(skillsDirectory);
        Path tempDir = Files.createTempDirectory(skillsDirectory, "skill-upload-");
        try {
            SkillZipUtils.extract(file.getInputStream(), tempDir);

            // 2. 三层内定位 SKILL.md（zip 根目录结构不固定，M6-A03）
            Path skillMd = SkillZipUtils.findFile(tempDir, SKILL_FILE, 3);
            if (skillMd == null) {
                throw new IllegalArgumentException("压缩包中未找到 SKILL.md 文件");
            }

            // 3. frontmatter 提取 name（缺省用目录名）/description
            Path skillSourceDir = skillMd.getParent();
            String content = Files.readString(skillMd);
            String name = FrontmatterUtils.extract(content, "name");
            if (name == null || name.isBlank()) {
                name = skillSourceDir.getFileName().toString();
            }
            name = name.trim();
            validateSkillName(name);

            String description = FrontmatterUtils.extract(content, "description");
            if (description == null) {
                description = "";
            }

            // 4. 存储内容（standalone=复制进 skills 目录/{name}，覆盖旧版本）
            contentStore.save(name, skillSourceDir);

            // 5. upsert DB（skill_path 存本地目录绝对路径；source 新增记 upload、覆盖保留原值）
            String storedPath = contentStore.storedPath(name);
            AgentxSkill existing = skillMapper.selectOne(
                    new LambdaQueryWrapper<AgentxSkill>().eq(AgentxSkill::getName, name));
            boolean enabled;
            String source;
            if (existing == null) {
                AgentxSkill entity = new AgentxSkill();
                entity.setName(name);
                entity.setSkillPath(storedPath);
                entity.setDescription(description.trim());
                entity.setEnabled(1);
                entity.setFileName(originalName);
                entity.setSource(SOURCE_UPLOAD);
                skillMapper.insert(entity);
                enabled = true;
                source = SOURCE_UPLOAD;
                log.info("[SkillManager] 新 skill 上传: {} (enabled=1, source=upload)", name);
            } else {
                skillMapper.update(null, new LambdaUpdateWrapper<AgentxSkill>()
                        .eq(AgentxSkill::getName, name)
                        .set(AgentxSkill::getSkillPath, storedPath)
                        .set(AgentxSkill::getDescription, description.trim())
                        .set(AgentxSkill::getFileName, originalName));
                enabled = existing.getEnabled() != null && existing.getEnabled() == 1;
                source = existing.getSource() == null ? SOURCE_UPLOAD : existing.getSource();
                log.info("[SkillManager] skill 覆盖上传: {} (enabled={}, source={})", name, enabled, source);
            }

            // 6. 通知（standalone 空操作；cluster 实现广播各节点立即同步）
            contentStore.notifyChanged();

            return new SkillInfo(name, storedPath, description.trim(), enabled, source);
        } finally {
            SkillZipUtils.deleteRecursively(tempDir);
        }
    }

    /**
     * 技能详情：SKILL.md 全文原文（含 frontmatter，前端渲染/源码双视图展示）。
     * 技能或文件不存在抛 IllegalArgumentException → Controller 转 R.fail。
     */
    public SkillDetail getContent(String name) throws IOException {
        validateSkillName(name);
        AgentxSkill skill = requireSkill(name);
        Path skillMd = skillsDirectory.resolve(name).resolve(SKILL_FILE);
        if (!Files.isRegularFile(skillMd)) {
            throw new IllegalArgumentException("SKILL.md 不存在: " + name);
        }
        return new SkillDetail(name,
                skill.getDescription() == null ? "" : skill.getDescription(),
                skill.getEnabled() != null && skill.getEnabled() == 1,
                skill.getSource() == null ? SOURCE_BUILTIN : skill.getSource(),
                Files.readString(skillMd));
    }

    /**
     * 管理界面手动新建技能（表单：名称 + 描述 + Markdown 正文）：
     * 自动拼 frontmatter 落盘（仅 SKILL.md 单文件，附属资源走 zip 上传），
     * 新技能 enabled=1、source=manual（唯一可编辑来源）。
     */
    public SkillInfo createManual(String name, String description, String content) throws IOException {
        name = name == null ? "" : name.trim();
        validateSkillName(name);
        // 换行/空格会破坏 frontmatter 单行 key:value 解析
        if (name.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("技能名不能包含空格或换行: " + name);
        }
        if (skillMapper.selectOne(new LambdaQueryWrapper<AgentxSkill>().eq(AgentxSkill::getName, name)) != null
                || Files.isDirectory(skillsDirectory.resolve(name))) {
            throw new IllegalArgumentException("技能已存在: " + name);
        }
        String desc = boundedSingleLine(description, "描述");
        String body = boundedContent(content);

        Map<String, Object> frontmatter = new LinkedHashMap<>();
        frontmatter.put("name", name);
        frontmatter.put("description", desc);
        contentStore.writeSkillFile(name, FrontmatterUtils.assemble(frontmatter, body));

        String storedPath = contentStore.storedPath(name);
        AgentxSkill entity = new AgentxSkill();
        entity.setName(name);
        entity.setSkillPath(storedPath);
        entity.setDescription(desc);
        entity.setEnabled(1);
        entity.setSource(SOURCE_MANUAL);
        skillMapper.insert(entity);
        contentStore.notifyChanged();
        log.info("[SkillManager] 手动新建 skill: {} (enabled=1, source=manual)", name);
        return new SkillInfo(name, storedPath, desc, true, SOURCE_MANUAL);
    }

    /**
     * 编辑技能内容（描述 + 正文）：内置技能（source=builtin，含历史空值回退）拒绝，手动/上传可编辑；
     * enabled 不动。正文可携带完整 frontmatter 头（编辑界面展示完整文件）——带了就以提交的头为准
     * （含用户增删的其他键；name 仍强制为 path name），description 缺失时回退表单字段；
     * 未带头时回退既有文件头的其他键 + 表单 description，头永远不会丢。
     */
    public SkillInfo updateContent(String name, String description, String content) throws IOException {
        validateSkillName(name);
        AgentxSkill skill = requireSkill(name);
        if (SOURCE_BUILTIN.equals(skill.getSource() == null ? SOURCE_BUILTIN : skill.getSource())) {
            throw new IllegalArgumentException("内置技能不支持编辑");
        }
        String body = boundedContent(content);
        Map<String, Object> submitted = FrontmatterUtils.parseAll(body);

        Map<String, Object> frontmatter = new LinkedHashMap<>();
        if (!submitted.isEmpty()) {
            frontmatter.putAll(submitted);
        } else {
            Path skillMd = skillsDirectory.resolve(name).resolve(SKILL_FILE);
            if (Files.isRegularFile(skillMd)) {
                frontmatter.putAll(FrontmatterUtils.parseAll(Files.readString(skillMd)));
            }
        }
        String desc = boundedSingleLine(
                submitted.containsKey("description") ? String.valueOf(submitted.get("description")) : description,
                "描述");
        frontmatter.put("name", name);
        frontmatter.put("description", desc);
        contentStore.writeSkillFile(name, FrontmatterUtils.assemble(frontmatter, body));

        skillMapper.update(null, new LambdaUpdateWrapper<AgentxSkill>()
                .eq(AgentxSkill::getName, name)
                .set(AgentxSkill::getDescription, desc));
        contentStore.notifyChanged();
        log.info("[SkillManager] 编辑 skill 内容: {} (source=manual)", name);
        String storedPath = contentStore.storedPath(name);
        boolean enabled = skill.getEnabled() != null && skill.getEnabled() == 1;
        return new SkillInfo(name, storedPath, desc, enabled, SOURCE_MANUAL);
    }

    /** 按 name 取技能，不存在抛 IllegalArgumentException → Controller 转 R.fail */
    private AgentxSkill requireSkill(String name) {
        AgentxSkill skill = skillMapper.selectOne(
                new LambdaQueryWrapper<AgentxSkill>().eq(AgentxSkill::getName, name));
        if (skill == null) {
            throw new IllegalArgumentException("skill 不存在: " + name);
        }
        return skill;
    }

    /** 描述单行化（换行折叠为空格，frontmatter 单行解析约束）+ 长度限制 */
    private String boundedSingleLine(String text, String field) {
        String result = text == null ? "" : text.replaceAll("\\s*\\r?\\n\\s*", " ").trim();
        if (result.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(field + "过长（最多 " + MAX_DESCRIPTION_LENGTH + " 字符）");
        }
        return result;
    }

    /** 正文大小限制（200KB） */
    private String boundedContent(String content) {
        String body = content == null ? "" : content;
        if (body.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("技能正文超过大小限制（200KB）");
        }
        return body;
    }

    /**
     * 导出技能为 zip 字节（含 SKILL.md 与全部附属资源；根目录即技能目录内容，
     * 可直接被上传重新导入）。技能或目录不存在抛 IllegalArgumentException → Controller 404。
     */
    public byte[] exportZip(String name) throws IOException {
        validateSkillName(name);
        requireSkill(name);
        Path skillDir = skillsDirectory.resolve(name);
        if (!Files.isDirectory(skillDir)) {
            throw new IllegalArgumentException("skill 目录不存在: " + name);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SkillZipUtils.zip(skillDir, out);
        log.info("[SkillManager] 导出 skill: {} ({} bytes)", name, out.size());
        return out.toByteArray();
    }

    /** 切换技能启用状态（M6-A07；不存在抛 IllegalArgumentException → Controller 404） */
    public boolean toggleEnabled(String name, boolean enabled) {
        AgentxSkill existing = skillMapper.selectOne(
                new LambdaQueryWrapper<AgentxSkill>().eq(AgentxSkill::getName, name));
        if (existing == null) {
            throw new IllegalArgumentException("skill 不存在: " + name);
        }
        int newValue = enabled ? 1 : 0;
        if (existing.getEnabled() != null && existing.getEnabled() == newValue) {
            return enabled;
        }
        skillMapper.update(null, new LambdaUpdateWrapper<AgentxSkill>()
                .eq(AgentxSkill::getName, name)
                .set(AgentxSkill::getEnabled, newValue));
        log.info("[SkillManager] skill 切换: {} → enabled={}", name, enabled);
        return enabled;
    }

    /**
     * 删除技能（目录内容 + DB 记录；目录已不存在时由定时同步清孤儿兜底）。
     * 内置技能（仓库分发，删除后下次同步又会入库）拒绝删除，如需失效请停用。
     */
    public boolean delete(String name) throws IOException {
        validateSkillName(name);
        AgentxSkill skill = requireSkill(name);
        if (SOURCE_BUILTIN.equals(skill.getSource() == null ? SOURCE_BUILTIN : skill.getSource())) {
            throw new IllegalArgumentException("内置技能不支持删除，如需使其失效请停用");
        }
        contentStore.delete(name);
        int deleted = skillMapper.delete(
                new LambdaQueryWrapper<AgentxSkill>().eq(AgentxSkill::getName, name));
        if (deleted > 0) {
            contentStore.notifyChanged();
        }
        log.info("[SkillManager] skill 删除: {} (db={})", name, deleted > 0);
        return deleted > 0;
    }

    /**
     * 校验技能名（防路径穿越，M6-A05）：禁 {@code .. / \ :}，
     * 且 resolve 后必须仍在 skills 目录内（双层防护）。
     */
    void validateSkillName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("skill 名称不能为空");
        }
        if (name.contains("..") || name.contains("/") || name.contains("\\") || name.contains(":")) {
            throw new IllegalArgumentException("非法 skill 名称: " + name);
        }
        Path resolved = skillsDirectory.resolve(name).normalize();
        if (!resolved.startsWith(skillsDirectory)) {
            throw new IllegalArgumentException("非法 skill 名称: " + name);
        }
    }
}
