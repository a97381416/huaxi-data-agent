package com.llmetor.dataagent.dataagent.skill;

import com.llmetor.dataagent.dataagent.skill.mapper.AgentxSkillMapper;
import com.llmetor.miniagentx.utils.FrontmatterUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Skill 内容存储（本地目录版，{@code deploy.mode=standalone} 或未配置时生效）。
 *
 * <p>本地目录是权威源：上传复制到本地；启动/定时扫描目录同步进 DB——
 * 目录新增→入库（enabled=1，手动放目录也能被自动发现，M6-A01）、
 * 目录删除→清孤儿记录（M6-A08 联动）、两边都有→按 frontmatter 更新 description/path（保留 enabled）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "deploy.mode", havingValue = "standalone", matchIfMissing = true)
public class LocalFsSkillContentStore implements SkillContentStore {

    private static final String SKILL_FILE = "SKILL.md";

    private final Path skillsDirectory;
    private final AgentxSkillMapper skillMapper;

    public LocalFsSkillContentStore(@Value("${skills.directory:./skills}") String skillsDirectory,
                                    AgentxSkillMapper skillMapper) {
        this.skillsDirectory = Path.of(skillsDirectory).toAbsolutePath().normalize();
        this.skillMapper = skillMapper;
    }

    @Override
    public void save(String name, Path skillSourceDir) throws IOException {
        Path targetDir = skillsDirectory.resolve(name);
        if (Files.isDirectory(targetDir)) {
            FileSystemUtils.deleteRecursively(targetDir);
        }
        FileSystemUtils.copyRecursively(skillSourceDir.toFile(), targetDir.toFile());
    }

    @Override
    public void writeSkillFile(String name, String content) throws IOException {
        Path skillDir = skillsDirectory.resolve(name);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve(SKILL_FILE), content);
    }

    @Override
    public void delete(String name) throws IOException {
        Path skillDir = skillsDirectory.resolve(name);
        if (Files.isDirectory(skillDir)) {
            FileSystemUtils.deleteRecursively(skillDir);
        }
    }

    @Override
    public String storedPath(String name) {
        return skillsDirectory.resolve(name).toAbsolutePath().toString();
    }

    /** 目录 → DB：FS 有 DB 没有 → 插入（enabled=1）；FS 没有 DB 有 → 删孤儿；两边都有 → 必要时更新 description/path */
    @Override
    public void sync() {
        if (!Files.isDirectory(skillsDirectory)) {
            log.warn("[SkillManager] skills 目录不存在: {}", skillsDirectory);
            return;
        }

        List<String> fsNames = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDirectory, Files::isDirectory)) {
            for (Path dir : stream) {
                if (Files.isRegularFile(dir.resolve(SKILL_FILE))) {
                    fsNames.add(dir.getFileName().toString());
                }
            }
        } catch (IOException e) {
            log.warn("[SkillManager] 扫描 skills 目录失败: {}", e.toString());
        }

        List<AgentxSkill> dbSkills = skillMapper.selectList(null);

        for (String name : fsNames) {
            String absPath = skillsDirectory.resolve(name).toAbsolutePath().toString();
            String description = FrontmatterUtils.extractFromFile(
                    skillsDirectory.resolve(name).resolve(SKILL_FILE), "description");
            if (description == null) {
                description = "";
            }
            AgentxSkill existing = dbSkills.stream()
                    .filter(s -> s.getName().equals(name))
                    .findFirst()
                    .orElse(null);
            if (existing == null) {
                AgentxSkill entity = new AgentxSkill();
                entity.setName(name);
                entity.setSkillPath(absPath);
                entity.setDescription(description);
                entity.setEnabled(1);
                entity.setSource(SkillManager.SOURCE_BUILTIN);
                skillMapper.insert(entity);
                log.info("[SkillManager] 存量 skill 入库: {} (enabled=1, source=builtin)", name);
            } else {
                boolean descChanged = !description.equals(existing.getDescription());
                boolean pathChanged = !absPath.equals(existing.getSkillPath());
                if (descChanged || pathChanged) {
                    AgentxSkill update = new AgentxSkill();
                    update.setId(existing.getId());
                    if (descChanged) {
                        update.setDescription(description);
                    }
                    if (pathChanged) {
                        update.setSkillPath(absPath);
                    }
                    skillMapper.updateById(update);
                }
            }
        }

        for (AgentxSkill dbSkill : dbSkills) {
            if (!fsNames.contains(dbSkill.getName())) {
                skillMapper.deleteById(dbSkill.getId());
                log.warn("[SkillManager] 孤儿 DB 记录已清理（目录不存在）: {}", dbSkill.getName());
            }
        }
    }
}
