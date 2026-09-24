package com.fieldwork.ops.attachment;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S3 object-storage settings for ticket attachments, bound from
 * {@code app.s3} in application.yml (environment variables
 * {@code S3_ENDPOINT}, {@code S3_REGION}, {@code S3_BUCKET},
 * {@code S3_ACCESS_KEY}, {@code S3_SECRET_KEY},
 * {@code S3_MAX_FILE_SIZE}, {@code S3_ALLOWED_CONTENT_TYPES}).
 *
 * <p>In local dev the endpoint points at LocalStack
 * ({@code http://localstack:4566} inside compose,
 * {@code http://localhost:4566} outside it) and the {@code test} /
 * {@code test} credentials are what LocalStack expects. In production
 * the endpoint is left empty so the SDK resolves the real regional S3
 * endpoint — real deployments MUST set {@code S3_ACCESS_KEY} and
 * {@code S3_SECRET_KEY} (see {@code .env.example}).
 */
@ConfigurationProperties(prefix = "app.s3")
public record S3Properties(
        String endpoint,
        String region,
        String bucket,
        String accessKey,
        String secretKey,
        long maxFileSizeBytes,
        List<String> allowedContentTypes) {

    public S3Properties {
        if (region == null || region.isBlank()) {
            region = "us-east-1";
        }
        if (bucket == null || bucket.isBlank()) {
            bucket = "fieldwork-attachments";
        }
        if (accessKey == null || accessKey.isBlank()) {
            accessKey = "test";
        }
        if (secretKey == null || secretKey.isBlank()) {
            secretKey = "test";
        }
        if (maxFileSizeBytes <= 0) {
            maxFileSizeBytes = 10L * 1024 * 1024;
        }
        if (allowedContentTypes == null || allowedContentTypes.isEmpty()) {
            allowedContentTypes = List.of(
                    "image/jpeg", "image/png", "application/pdf", "text/plain");
        }
    }
}
