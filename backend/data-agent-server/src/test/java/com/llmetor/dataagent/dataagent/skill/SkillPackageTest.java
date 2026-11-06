package com.llmetor.dataagent.dataagent.skill;

import com.llmetor.dataagent.dataagent.skill.mapper.AgentxSkillMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 技能包工具单测（M6-A04/A05 对应）：
 * Zip Slip 路径穿越拒绝 / 正常解压 / SKILL.md 三层内定位 / 技能名校验。
 */
class SkillPackageTest {

    @TempDir
    Path tempDir;

    // ========== SkillZipUtils.extract ==========

    private java.io.ByteArrayInputStream zipOf(String... entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            for (int i = 0; i < entries.length; i += 2) {
                zip.putNextEntry(new ZipEntry(entries[i]));
                zip.write(entries[i + 1].getBytes());
                zip.closeEntry();
            }
        }
        return new java.io.ByteArrayInputStream(bos.toByteArray());
    }

    @Test
    void extractNormalZip() throws Exception {
        Path target = tempDir.resolve("out");
        SkillZipUtils.extract(zipOf(
                "my-skill/SKILL.md", "---\nname: my-skill\n---\nbody",
                "my-skill/scripts/run.py", "print(1)"), target);

        assertThat(target.resolve("my-skill/SKILL.md")).exists();
        assertThat(target.resolve("my-skill/scripts/run.py")).exists();
    }

    @Test
    void extractRejectsZipSlip() throws Exception {
        // entry 名带 ../ 路径穿越：resolve+normalize 越出目标目录必须拒绝（M6-A04）
        java.io.ByteArrayInputStream evil = zipOf("../../evil.md", "pwned");
        Path target = tempDir.resolve("out");
        assertThatThrownBy(() -> SkillZipUtils.extract(evil, target))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("非法路径");
        // 越界文件不落地
        assertThat(tempDir.resolve("evil.md")).doesNotExist();
        assertThat(Paths.get("").toAbsolutePath().resolve("evil.md")).doesNotExist();
    }

    // ========== SkillZipUtils.findFile ==========

    @Test
    void findFileWithinThreeLevels() throws Exception {
        Path root = tempDir.resolve("pkg");
        Path deep = root.resolve("a").resolve("b");
        Files.createDirectories(deep);
        Files.writeString(deep.resolve("SKILL.md"), "hello");

        assertThat(SkillZipUtils.findFile(root, "SKILL.md", 3)).isNotNull();
        assertThat(SkillZipUtils.findFile(root, "MISSING.md", 3)).isNull();
    }

    @Test
    void findFileBeyondDepthReturnsNull() throws Exception {
        Path root = tempDir.resolve("pkg2");
        Path deep = root.resolve("a").resolve("b").resolve("c").resolve("d");
        Files.createDirectories(deep);
        Files.writeString(deep.resolve("SKILL.md"), "deep");

        assertThat(SkillZipUtils.findFile(root, "SKILL.md", 3)).isNull();
    }

    // ========== SkillManager.validateSkillName（M6-A05） ==========

    private SkillManager newManager() {
        return new SkillManager(tempDir.resolve("skills").toString(),
                org.mockito.Mockito.mock(javax.sql.DataSource.class),
                org.mockito.Mockito.mock(AgentxSkillMapper.class),
                org.mockito.Mockito.mock(SkillContentStore.class));
    }

    @Test
    void validSkillNameAccepted() {
        newManager().validateSkillName("data-analysis");
        newManager().validateSkillName("my_skill.v2");
    }

    @Test
    void pathTraversalRejected() {
        SkillManager manager = newManager();
        assertThatThrownBy(() -> manager.validateSkillName("../evil")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.validateSkillName("a/b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.validateSkillName("a\\b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.validateSkillName("C:evil")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.validateSkillName(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.validateSkillName(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
