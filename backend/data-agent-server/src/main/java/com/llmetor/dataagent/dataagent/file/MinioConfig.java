package com.llmetor.dataagent.dataagent.file;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端装配（M5）。
 *
 * <p>只在配置了 endpoint 时创建 Bean；构造客户端不做连通性检查——MinIO 宕机时
 * 应用照常启动，首次上传才真实触网并明确报错（E02 降级契约）。
 */
@Configuration
@ConditionalOnProperty(name = "data-agent.minio.endpoint")
public class MinioConfig {

    @Bean
    public MinioClient minioClient(
            @Value("${data-agent.minio.endpoint}") String endpoint,
            @Value("${data-agent.minio.access-key}") String accessKey,
            @Value("${data-agent.minio.secret-key}") String secretKey) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
