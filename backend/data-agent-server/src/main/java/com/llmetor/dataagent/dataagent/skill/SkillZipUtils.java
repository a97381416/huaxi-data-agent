package com.llmetor.dataagent.dataagent.skill;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 技能 zip 包解压/打包与小文件工具（M6）。
 *
 * <p>extract 防止 Zip Slip 路径穿越攻击：entry 路径 resolve+normalize 后必须仍落在
 * 目标目录内，越界即拒绝（M6-A04）。zip 打包为 export 的逆过程，根目录即技能目录内容
 * （SKILL.md 在根），可直接被 upload 重新导入。
 */
final class SkillZipUtils {

    private SkillZipUtils() {
    }

    /**
     * 安全解压 zip 到目标目录（不存在会自动创建）。
     *
     * @throws IOException 解压失败或检测到越界路径（Zip Slip）
     */
    static void extract(InputStream zipStream, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        try (ZipInputStream zip = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path dest = targetDir.resolve(entry.getName()).normalize();
                if (!dest.startsWith(targetDir)) {
                    throw new IOException("zip 包含非法路径: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(zip, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
    }

    /**
     * 将技能目录打包为 zip 写入输出流（导出用）：相对路径条目（/ 分隔，UTF-8 文件名），
     * SKILL.md 位于 zip 根，空目录（如 references/）生成目录项。
     */
    static void zip(Path sourceDir, OutputStream out) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    String rel = sourceDir.relativize(dir).toString().replace('\\', '/');
                    if (!rel.isEmpty()) {
                        zos.putNextEntry(new ZipEntry(rel + "/"));
                        zos.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    zos.putNextEntry(new ZipEntry(sourceDir.relativize(file).toString().replace('\\', '/')));
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /**
     * 在目录 {@code maxDepth} 层内查找指定文件名（如 SKILL.md），找到返回其路径。
     * zip 的根目录结构不固定（有的多包一层），逐层下探定位。
     */
    static Path findFile(Path dir, String fileName, int maxDepth) throws IOException {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> stream = Files.walk(dir, maxDepth)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .findFirst()
                    .orElse(null);
        }
    }

    /** 递归删除目录/文件（上传临时目录清理用），失败静默（finally 清理场景） */
    static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // 临时目录清理失败无碍（OS 临时目录最终会回收）
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
