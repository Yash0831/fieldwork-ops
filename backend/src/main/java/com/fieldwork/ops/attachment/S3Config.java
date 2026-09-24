package com.fieldwork.ops.attachment;

import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * Builds the singleton {@link S3Client} used by the attachment module.
 *
 * <p>When {@code app.s3.endpoint} is set (LocalStack in dev/test) the
 * client talks to that endpoint instead of AWS; when it is empty the
 * SDK resolves the real regional S3 endpoint, so the same build runs
 * against production S3 with no code change. Path-style addressing is
 * forced on: LocalStack (and most S3 emulators) require it, and real
 * AWS S3 accepts it, so one setting works for both.
 */
@Configuration
@EnableConfigurationProperties(S3Properties.class)
public class S3Config {

    @Bean
    public S3Client s3Client(S3Properties properties) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                properties.accessKey(), properties.secretKey())))
                .forcePathStyle(true);
        String endpoint = properties.endpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint.strip()));
        }
        return builder.build();
    }
}
