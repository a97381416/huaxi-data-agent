package com.llmetor.dataagent.dataagent.file;

import com.llmetor.dataagent.common.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 文件接口（M5）：上传 / 预览 / 删除。
 *
 * <p>登录校验由 SaTokenConfig 全局拦截覆盖。MinIO 连通性只在真实操作时触网——
 * 宕机时上传明确报错（R.fail），服务不崩（E02 降级契约）。
 */
@Slf4j
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    /** 预览 MIME 按扩展名映射（其余一律 octet-stream 触发下载） */
    private static final Map<String, MediaType> IMAGE_MIME = Map.of(
            "jpg", MediaType.IMAGE_JPEG,
            "jpeg", MediaType.IMAGE_JPEG,
            "png", MediaType.IMAGE_PNG,
            "gif", MediaType.IMAGE_GIF,
            "bmp", MediaType.parseMediaType("image/bmp"),
            "webp", MediaType.parseMediaType("image/webp")
    );

    private final FileManageService fileManageService;

    @Value("${data-agent.file.max-size-mb:100}")
    private long maxSizeMb;

    /** 文件上传（单文件；单轮个数上限由前端按 file.max-count 控制） */
    @PostMapping("/upload")
    public R<UploadResponse> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return R.paramError("文件不能为空");
        }
        long maxBytes = maxSizeMb * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            return R.paramError("文件不能超过 " + maxSizeMb + "MB");
        }
        log.info("[file] 收到上传: name={}, size={}", file.getOriginalFilename(), file.getSize());
        try {
            return R.ok(UploadResponse.from(fileManageService.uploadFile(file)));
        } catch (Exception e) {
            log.error("[file] 上传失败", e);
            return R.fail("文件上传失败: " + e.getMessage());
        }
    }

    /** 预览：原始字节 + 扩展名 MIME 映射 + 7 天浏览器缓存 */
    @GetMapping("/{fileId}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable String fileId) {
        try {
            AgentxFile meta = fileManageService.getFileInfo(fileId);
            byte[] bytes = fileManageService.downloadFileBytes(fileId);
            MediaType mime = IMAGE_MIME.getOrDefault(
                    meta.getFileType() == null ? "" : meta.getFileType().toLowerCase(),
                    MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.ok()
                    .contentType(mime)
                    .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
                    .contentLength(bytes.length)
                    .body(bytes);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.warn("[file] 预览失败: fileId={}, err={}", fileId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /** 删除：先 MinIO 后 DB（MinIO 失败仅 warn 不 500） */
    @DeleteMapping("/{fileId}")
    public R<Void> delete(@PathVariable String fileId) {
        try {
            fileManageService.deleteFile(fileId);
            return R.ok();
        } catch (IllegalArgumentException e) {
            return R.notFound(e.getMessage());
        } catch (Exception e) {
            log.error("[file] 删除失败: fileId={}", fileId, e);
            return R.fail("删除失败: " + e.getMessage());
        }
    }
}
