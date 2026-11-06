package com.llmetor.dataagent.dataagent.file;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketPolicyArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

/**
 * MinIO 文件存储服务（M5）。
 *
 * <p>桶策略 publicRead：预览/图片缩略图直接经 preview 接口或桶 URL 读取，与原版一致。
 * ensureBucket 在每次上传前检查（幂等，秒级元数据操作）——不在启动时强制连通，
 * MinIO 宕机时应用照常启动、上传明确报错（E02）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinioService {

    private final MinioClient minioClient;

    @Value("${data-agent.minio.endpoint}")
    private String endpoint;

    @Value("${data-agent.minio.bucket-name}")
    private String bucketName;

    /** 确保桶存在；不存在则创建并设公读策略（幂等） */
    public void ensureBucket() {
        try {
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Principal\":{\"AWS\":[\"*\"]},\"Action\":[\"s3:GetObject\"],"
                        + "\"Resource\":[\"arn:aws:s3:::" + bucketName + "/*\"]}]}";
                minioClient.setBucketPolicy(SetBucketPolicyArgs.builder()
                        .bucket(bucketName).config(policy).build());
                log.info("[file] MinIO bucket 已创建并设公读: {}", bucketName);
            }
        } catch (Exception e) {
            throw new RuntimeException("MinIO 初始化失败: " + e.getMessage(), e);
        }
    }

    /** 上传文件，返回完整 URL（file-{uuid}.{ext} 的对象名由 FileManageService 生成） */
    public String uploadFile(MultipartFile file, String objectName) throws Exception {
        ensureBucket();
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucketName)
                .object(objectName)
                .stream(file.getInputStream(), file.getSize(), -1)
                .contentType(file.getContentType())
                .build());
        return String.format("%s/%s/%s", trimSlash(endpoint), bucketName, objectName);
    }

    /** 下载文件流（analyzeFile 图片识别 / preview 接口共用） */
    public InputStream downloadFile(String objectName) throws Exception {
        return minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucketName)
                .object(objectName)
                .build());
    }

    /** 删除对象（删除主流程：MinIO 失败仅 warn，DB 照删，见 FileManageService#deleteFile） */
    public void deleteFile(String objectName) throws Exception {
        minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(bucketName)
                .object(objectName)
                .build());
    }

    private String trimSlash(String endpoint) {
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }
}
