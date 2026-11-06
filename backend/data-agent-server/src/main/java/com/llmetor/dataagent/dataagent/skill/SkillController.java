package com.llmetor.dataagent.dataagent.skill;

import com.llmetor.dataagent.common.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Skills 管理 API（M6，仅 admin——/api/skills/** 拦截器在 SaTokenConfig，M0 规则先行）。
 */
@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
@Slf4j
public class SkillController {

    private final SkillManager skillManager;

    /** 列出全部技能（含 enabled 状态） */
    @GetMapping
    public R<List<SkillInfo>> list() {
        return R.ok(skillManager.list());
    }

    /** 上传 zip 压缩包（新技能默认启用，覆盖上传保留原状态） */
    @PostMapping("/upload")
    public R<SkillInfo> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return R.fail("文件不能为空");
        }
        try {
            return R.ok(skillManager.uploadZip(file));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.error("[skills] 上传失败: {}", e.getMessage(), e);
            return R.fail("上传失败: " + e.getMessage());
        }
    }

    /** 技能详情：SKILL.md 全文原文（前端渲染/源码双视图展示） */
    @GetMapping("/{name}/content")
    public R<SkillDetail> content(@PathVariable String name) {
        try {
            return R.ok(skillManager.getContent(name));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.error("[skills] 读取详情失败: {}", e.getMessage(), e);
            return R.fail("读取详情失败: " + e.getMessage());
        }
    }

    /** 手动新建技能（表单：名称 + 描述 + 正文，自动拼 frontmatter 落盘） */
    @PostMapping
    public R<SkillInfo> create(@RequestBody SkillUpsertRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            return R.fail("技能名不能为空");
        }
        try {
            return R.ok(skillManager.createManual(request.name(), request.description(), request.content()));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.error("[skills] 新建失败: {}", e.getMessage(), e);
            return R.fail("新建失败: " + e.getMessage());
        }
    }

    /** 导出技能 zip（含 SKILL.md 与全部附属资源；技能不存在 404） */
    @GetMapping("/{name}/export")
    public ResponseEntity<byte[]> export(@PathVariable String name) {
        try {
            byte[] bytes = skillManager.exportZip(name);
            // RFC 5987 编码（技能名可含中文），并保留 filename*= 形式供现代浏览器取 UTF-8 名
            String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + encoded + ".zip\"; filename*=UTF-8''" + encoded + ".zip")
                    .contentLength(bytes.length)
                    .body(bytes);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("[skills] 导出失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /** 编辑技能内容（内置技能拒绝，手动/上传技能可编辑） */
    @PutMapping("/{name}/content")
    public R<SkillInfo> updateContent(@PathVariable String name, @RequestBody SkillUpsertRequest request) {
        try {
            return R.ok(skillManager.updateContent(name, request.description(), request.content()));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.error("[skills] 编辑失败: {}", e.getMessage(), e);
            return R.fail("编辑失败: " + e.getMessage());
        }
    }

    /** 切换技能启用/禁用状态 */
    @PutMapping("/{name}/toggle")
    public R<Void> toggle(@PathVariable String name, @RequestParam boolean enabled) {
        try {
            skillManager.toggleEnabled(name, enabled);
            return R.ok();
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.error("[skills] 切换状态失败: {}", e.getMessage(), e);
            return R.fail("操作失败: " + e.getMessage());
        }
    }

    /** 删除技能（目录 + DB 记录） */
    @DeleteMapping("/{name}")
    public R<Void> delete(@PathVariable String name) {
        try {
            boolean ok = skillManager.delete(name);
            return ok ? R.ok() : R.fail("skill 不存在: " + name);
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.error("[skills] 删除失败: {}", e.getMessage(), e);
            return R.fail("删除失败: " + e.getMessage());
        }
    }
}
